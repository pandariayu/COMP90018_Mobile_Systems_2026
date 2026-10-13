package com.comp90018.flashcards.ui.deck

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.comp90018.flashcards.data.account.AccountRepository
import com.comp90018.flashcards.data.local.dao.DeckDao
import com.comp90018.flashcards.data.local.entity.DeckEntity
import com.comp90018.flashcards.data.social.CommunityDecks
import com.comp90018.flashcards.data.social.DeckAttribution
import com.comp90018.flashcards.data.social.DeckCopies
import com.comp90018.flashcards.data.social.FriendsRepository
import com.comp90018.flashcards.data.social.SocialFriend
import com.comp90018.flashcards.data.social.SocialSession
import com.comp90018.flashcards.domain.repository.CardRepository
import com.comp90018.flashcards.ui.social.SocialViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI State for the unified deck screen (`DeckPageScreen`) and mode selection screens.
 */
data class DeckSummaryUiState(
    val deckId: String = "",
    val deckName: String = "",
    val deckDescription: String = "",
    val cardCount: Int = 0,
    val cards: List<CardUiModel> = emptyList(),
    val isOwned: Boolean = true,
    val visibility: String = "private",
    val creatorDisplayName: String? = null,
    val copiedFrom: DeckAttribution? = null,
    val savedCopyId: String? = null,
    val pendingCopy: Boolean = false,
    val moreCards: Boolean = false,
    val friends: List<SocialFriend>? = null,
    val isLoading: Boolean = true,
)

/**
 * Unified ViewModel for `DeckPageScreen`, handling both local owned decks and remote community decks.
 */
@HiltViewModel
class DeckSummaryViewModel
    @Inject
    constructor(
        private val repository: CardRepository,
        private val decks: CommunityDecks,
        private val copies: DeckCopies,
        private val friendsRepository: FriendsRepository,
        private val localDeckDao: DeckDao,
        private val accounts: AccountRepository,
        private val savedStateHandle: SavedStateHandle,
        session: SocialSession,
    ) : SocialViewModel(session) {
        val deckId: String = checkNotNull(savedStateHandle["deckId"])

        private val state =
            MutableStateFlow(
                DeckSummaryUiState(
                    deckId = deckId,
                    savedCopyId = savedStateHandle["savedCopyId"],
                ),
            )
        val uiState = state.asStateFlow()

        private var currentLocalDeck: DeckEntity? = null

        init {
            refresh()
        }

        fun refresh() {
            viewModelScope.launch {
                state.update { it.copy(isLoading = true) }
                val owned = repository.getDeckById(deckId)
                if (owned != null) loadOwnedDeck(owned) else loadCommunityDeck()
            }
        }

        // the player's own deck, read from the local database and topped up with the cloud details
        private suspend fun CoroutineScope.loadOwnedDeck(owned: DeckEntity) {
            currentLocalDeck = owned
            val creatorDisplayName = accounts.cachedProfile(owned.ownerId)?.displayName
            state.update {
                it.copy(
                    deckName = owned.name,
                    deckDescription = owned.description,
                    creatorDisplayName = creatorDisplayName,
                    isOwned = true,
                    isLoading = false,
                )
            }

            // Observe local cards
            launch {
                repository.getCardsByDeckId(deckId).collectLatest { localCards ->
                    val uiCards = localCards.map { card -> card.toUiModel() }
                    state.update {
                        it.copy(
                            cardCount = localCards.size,
                            cards = uiCards,
                        )
                    }
                }
            }

            // Fetch remote metadata for owned deck if available
            runCatching {
                val cloud = decks.get(userId, deckId)
                state.update {
                    it.copy(
                        visibility = cloud.visibility,
                        copiedFrom = cloud.copiedFrom,
                        creatorDisplayName = cloud.ownerDisplayName ?: it.creatorDisplayName,
                    )
                }
            }
        }

        // someone else's deck, loaded from the cloud, with a check for a copy already saved
        private suspend fun loadCommunityDeck() {
            state.update { it.copy(isOwned = false) }
            val pending = copies.hasPending(userId, deckId)
            state.update { it.copy(pendingCopy = pending) }

            // Check if local DB already has a copy saved from this deck
            val localDecks = localDeckDao.listDecksForOwner(userId)
            val existingCopy =
                localDecks.find {
                    // Check if this local deck was copied from source deckId
                    it.deckId == savedStateHandle["savedCopyId"]
                }
            if (existingCopy != null) {
                state.update { it.copy(savedCopyId = existingCopy.deckId) }
            }

            runCatching {
                val remoteDeck = decks.prepare(userId, deckId)
                val remoteCards = decks.cards(userId, deckId, 0).map { it.toUiModel() }
                state.update {
                    it.copy(
                        deckName = remoteDeck.name,
                        cardCount = remoteDeck.cardCount,
                        visibility = remoteDeck.visibility,
                        copiedFrom = remoteDeck.copiedFrom,
                        creatorDisplayName = remoteDeck.ownerDisplayName,
                        cards = remoteCards,
                        moreCards = remoteCards.size == 50,
                        isLoading = false,
                    )
                }
            }.onFailure {
                state.update { currentState -> currentState.copy(isLoading = false) }
            }
        }

        fun saveCopy() =
            runAction("Copy saved to My Decks.") {
                state.update { it.copy(pendingCopy = true) }
                val newCopyId = copies.save(userId, deckId)
                savedStateHandle["savedCopyId"] = newCopyId
                state.update {
                    it.copy(
                        savedCopyId = newCopyId,
                        pendingCopy = false,
                    )
                }
            }

        fun moreCards() =
            runAction {
                val page = decks.cards(userId, deckId, state.value.cards.size)
                val newCards = page.map { it.toUiModel() }
                state.update {
                    it.copy(
                        cards = (it.cards + newCards).distinctBy { card -> card.cardId },
                        moreCards = page.size == 50,
                    )
                }
            }

        fun updateDeck(
            name: String,
            description: String,
        ) {
            val current = currentLocalDeck ?: return
            val updated = current.copy(name = name, description = description)
            viewModelScope.launch {
                repository.updateDeck(updated)
                currentLocalDeck = updated
                state.update { it.copy(deckName = name, deckDescription = description) }
            }
        }

        fun deleteDeck(onDeleted: () -> Unit) {
            val current = currentLocalDeck ?: return
            viewModelScope.launch {
                repository.deleteDeck(current)
                onDeleted()
            }
        }

        fun publish(public: Boolean) =
            runAction(if (public) "Deck published." else "Deck is private.") {
                val updatedCloud = decks.publish(userId, deckId, public)
                state.update { it.copy(visibility = updatedCloud.visibility) }
            }

        fun loadFriends() =
            runAction {
                val list = friendsRepository.friends(userId)
                state.update { it.copy(friends = list) }
            }

        fun share(
            recipientId: String,
            revoke: Boolean,
        ) = runAction(
            if (revoke) "Share revoked." else "Shared with friend.",
        ) {
            if (revoke) decks.revoke(userId, deckId, recipientId) else decks.share(userId, deckId, recipientId)
        }
    }
