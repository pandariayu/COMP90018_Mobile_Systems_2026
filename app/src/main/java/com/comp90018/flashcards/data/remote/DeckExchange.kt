package com.comp90018.flashcards.data.remote

import com.comp90018.flashcards.data.auth.AuthRepository
import com.comp90018.flashcards.data.local.entity.DeckEntity
import com.comp90018.flashcards.data.sync.DeckSyncCoordinator
import com.comp90018.flashcards.data.sync.DeckSyncOutcome
import com.comp90018.flashcards.data.sync.isUnreadableCloudDeck
import com.comp90018.flashcards.domain.repository.CardRepository
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publish makes an already uploaded deck public. Save-a-copy downloads a readable cloud deck
 * into Room under new ids.
 */
@Singleton
class DeckExchange
    @Inject
    constructor(
        private val remote: DeckRemoteDataSource,
        private val repository: CardRepository,
        private val deckSync: DeckSyncCoordinator,
        private val authRepository: AuthRepository,
        private val clock: Clock,
    ) {
        suspend fun visibilityOf(deckId: String): DeckVisibility =
            remote.getDeck(deckId, fromServer = true).getOrNull()?.visibility ?: DeckVisibility.PRIVATE

        suspend fun publish(deck: DeckEntity): Result<Unit> {
            val blocked = publishBlock(deck)
            return blocked ?: remote.setVisibility(deck.deckId, DeckVisibility.PUBLIC, clock.instant())
        }

        suspend fun unpublish(deck: DeckEntity): Result<Unit> =
            if (authRepository.currentUid != deck.ownerId) {
                Result.failure(IllegalStateException(OWNER_ONLY))
            } else {
                unpublishOwned(deck)
            }

        suspend fun listPublicDecks(): Result<List<RemoteDeck>> = remote.listPublicDecks()

        suspend fun saveCopy(sourceDeckId: String): Result<String> {
            val ownerId = authRepository.currentUid
            return if (ownerId == null) {
                Result.failure(IllegalStateException(SIGN_IN))
            } else {
                copyReadableDeck(ownerId, sourceDeckId)
            }
        }

        private suspend fun unpublishOwned(deck: DeckEntity): Result<Unit> {
            val remoteDeck =
                remote.getDeck(deck.deckId, fromServer = true).getOrElse { error ->
                    return if (error.isUnreadableCloudDeck()) Result.success(Unit) else Result.failure(error)
                }
            val denied = remoteDeck != null && remoteDeck.ownerId != deck.ownerId
            return when {
                denied -> Result.failure(IllegalStateException(OWNER_ONLY))
                remoteDeck == null -> Result.success(Unit)
                else -> remote.setVisibility(deck.deckId, DeckVisibility.PRIVATE, clock.instant())
            }
        }

        private suspend fun publishBlock(deck: DeckEntity): Result<Unit>? {
            if (authRepository.currentUid != deck.ownerId) {
                return Result.failure(IllegalStateException(OWNER_ONLY))
            }
            deckSync.deckChanged(deck.deckId, deck.ownerId)
            val offline = offlineMessage(deckSync.syncNow(deck.ownerId))
            return offline?.let { Result.failure(IllegalStateException(it)) }
        }

        private suspend fun copyReadableDeck(
            ownerId: String,
            sourceDeckId: String,
        ): Result<String> {
            val source = readCloudDeck(sourceDeckId).getOrElse { return Result.failure(it) }
            return storeLoadedCopy(ownerId, source)
        }

        private suspend fun storeLoadedCopy(
            ownerId: String,
            source: RemoteDeck,
        ): Result<String> {
            val cards =
                remote.listCards(source.deckId, fromServer = true).getOrElse { error ->
                    return Result.failure(error)
                }
            return Result.success(storeCopy(ownerId, source, cards))
        }

        private suspend fun readCloudDeck(sourceDeckId: String): Result<RemoteDeck> {
            val deck =
                remote.getDeck(sourceDeckId, fromServer = true).getOrElse { error ->
                    return Result.failure(error)
                }
            return if (deck == null) {
                Result.failure(IllegalStateException(UNAVAILABLE))
            } else {
                Result.success(deck)
            }
        }

        private suspend fun storeCopy(
            ownerId: String,
            source: RemoteDeck,
            cards: List<RemoteCard>,
        ): String {
            val newDeckId = UUID.randomUUID().toString()
            val (deck, localCards) =
                copiedDeck(
                    source = source,
                    cards = cards,
                    ownerId = ownerId,
                    newDeckId = newDeckId,
                    newCardIds = List(cards.size) { UUID.randomUUID().toString() },
                )
            val authorEmail = source.authorEmail?.takeIf { it.isNotBlank() } ?: authRepository.currentEmail.orEmpty()
            repository.insertDeckWithCards(deck.copy(authorEmail = authorEmail), localCards)
            return newDeckId
        }

        private fun offlineMessage(outcome: DeckSyncOutcome): String? =
            when (outcome) {
                DeckSyncOutcome.UP_TO_DATE -> null
                DeckSyncOutcome.DEFERRED -> NOT_CONFIGURED
                DeckSyncOutcome.RETRY -> OFFLINE
            }

        private companion object {
            const val OWNER_ONLY = "Only the owner can change who can see this deck."
            const val OFFLINE = "Connect to the internet to publish this deck."
            const val NOT_CONFIGURED = "Cloud storage is not configured."
            const val SIGN_IN = "Sign in to save a copy."
            const val UNAVAILABLE = "This deck is no longer available."
        }
    }
