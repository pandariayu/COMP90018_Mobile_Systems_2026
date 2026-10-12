package com.comp90018.flashcards.ui.deck

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comp90018.flashcards.data.local.entity.CardEntity
import com.comp90018.flashcards.data.local.entity.DeckEntity
import com.comp90018.flashcards.data.remote.DeckExchange
import com.comp90018.flashcards.data.remote.DeckVisibility
import com.comp90018.flashcards.domain.repository.CardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI State for the screens that show a deck before it is played.
 */
data class DeckSummaryUiState(
    val deckName: String = "",
    val deckDescription: String = "",
    val cardCount: Int = 0,
    val cards: List<CardEntity> = emptyList(),
    val visibility: DeckVisibility = DeckVisibility.PRIVATE,
    val copiedFromName: String? = null,
    val authorEmail: String = "",
    val publishBusy: Boolean = false,
    val message: String? = null,
)

/**
 * ViewModel for the deck page and the choose-mode page. Both show the deck's name and size.
 */
@HiltViewModel
class DeckSummaryViewModel
    @Inject
    constructor(
        private val repository: CardRepository,
        private val deckExchange: DeckExchange,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        // The deckId is passed via navigation arguments
        val deckId: String = checkNotNull(savedStateHandle["deckId"])

        private val deckState = MutableStateFlow<DeckEntity?>(null)
        private val visibility = MutableStateFlow(DeckVisibility.PRIVATE)
        private val publishBusy = MutableStateFlow(false)
        private val message = MutableStateFlow<String?>(null)

        val uiState: StateFlow<DeckSummaryUiState> =
            combine(
                deckState,
                repository.getCardsByDeckId(deckId),
                visibility,
                publishBusy,
                message,
            ) { deck, cards, cloudVisibility, busy, statusMessage ->
                DeckSummaryUiState(
                    deckName = deck?.name.orEmpty(),
                    deckDescription = deck?.description.orEmpty(),
                    cardCount = cards.size,
                    cards = cards,
                    visibility = cloudVisibility,
                    copiedFromName = deck?.copiedFromName,
                    authorEmail = deck?.authorEmail.orEmpty(),
                    publishBusy = busy,
                    message = statusMessage,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DeckSummaryUiState())

        init {
            viewModelScope.launch {
                deckState.value = repository.getDeckById(deckId)
                visibility.value = deckExchange.visibilityOf(deckId)
            }
        }

        fun publish() = changeVisibility(publish = true)

        fun unpublish() = changeVisibility(publish = false)

        fun consumeMessage() {
            message.value = null
        }

        private fun changeVisibility(publish: Boolean) {
            val currentDeck = deckState.value ?: return
            viewModelScope.launch {
                publishBusy.value = true
                val result = if (publish) deckExchange.publish(currentDeck) else deckExchange.unpublish(currentDeck)
                visibility.value = deckExchange.visibilityOf(deckId)
                message.value =
                    result.fold(
                        onSuccess = { if (publish) "Deck published." else "Deck is private again." },
                        onFailure = { error -> error.message ?: "Could not update this deck." },
                    )
                publishBusy.value = false
            }
        }

        fun updateDeck(
            name: String,
            description: String,
        ) {
            val currentDeck = deckState.value ?: return
            val updated = currentDeck.copy(name = name, description = description)
            viewModelScope.launch {
                repository.updateDeck(updated)
                deckState.value = updated
            }
        }

        fun deleteDeck(onDeleted: () -> Unit) {
            val currentDeck = deckState.value ?: return
            viewModelScope.launch {
                repository.deleteDeck(currentDeck)
                onDeleted()
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5000L
        }
    }
