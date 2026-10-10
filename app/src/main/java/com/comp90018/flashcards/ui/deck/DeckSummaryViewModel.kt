package com.comp90018.flashcards.ui.deck

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comp90018.flashcards.data.local.entity.CardEntity
import com.comp90018.flashcards.data.local.entity.DeckEntity
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
)

/**
 * ViewModel for the deck page and the choose-mode page. Both show the deck's name and size.
 */
@HiltViewModel
class DeckSummaryViewModel
    @Inject
    constructor(
        private val repository: CardRepository,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        // The deckId is passed via navigation arguments
        val deckId: String = checkNotNull(savedStateHandle["deckId"])

        private val _deck = MutableStateFlow<DeckEntity?>(null)

        val uiState: StateFlow<DeckSummaryUiState> =
            combine(_deck, repository.getCardsByDeckId(deckId)) { deck, cards ->
                DeckSummaryUiState(
                    deckName = deck?.name.orEmpty(),
                    deckDescription = deck?.description.orEmpty(),
                    cardCount = cards.size,
                    cards = cards,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DeckSummaryUiState())

        init {
            viewModelScope.launch {
                _deck.value = repository.getDeckById(deckId)
            }
        }

        fun updateDeck(
            name: String,
            description: String,
        ) {
            val currentDeck = _deck.value ?: return
            val updated = currentDeck.copy(name = name, description = description)
            viewModelScope.launch {
                repository.updateDeck(updated)
                _deck.value = updated
            }
        }

        fun deleteDeck(onDeleted: () -> Unit) {
            val currentDeck = _deck.value ?: return
            viewModelScope.launch {
                repository.deleteDeck(currentDeck)
                onDeleted()
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5000L
        }
    }
