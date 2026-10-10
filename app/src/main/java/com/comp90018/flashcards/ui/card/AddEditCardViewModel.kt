package com.comp90018.flashcards.ui.card

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comp90018.flashcards.data.local.entity.CardEntity
import com.comp90018.flashcards.domain.repository.CardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class AddEditCardUiState(
    val front: String = "",
    val back: String = "",
    val isEditing: Boolean = false,
    val isSaved: Boolean = false,
)

@HiltViewModel
class AddEditCardViewModel
    @Inject
    constructor(
        private val repository: CardRepository,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val deckId: String = checkNotNull(savedStateHandle["deckId"])
        private val cardId: String? = savedStateHandle["cardId"]

        private val _uiState = MutableStateFlow(AddEditCardUiState(isEditing = cardId != null))
        val uiState: StateFlow<AddEditCardUiState> = _uiState.asStateFlow()

        init {
            if (cardId != null) {
                viewModelScope.launch {
                    repository.getCardById(cardId)?.let { card ->
                        _uiState.update { it.copy(front = card.front, back = card.back) }
                    }
                }
            }
        }

        fun onFrontChange(newValue: String) {
            _uiState.update { it.copy(front = newValue) }
        }

        fun onBackChange(newValue: String) {
            _uiState.update { it.copy(back = newValue) }
        }

        fun saveCard() {
            val state = uiState.value
            if (state.front.isBlank() || state.back.isBlank()) return

            viewModelScope.launch {
                if (cardId != null) {
                    repository.updateCard(
                        CardEntity(cardId = cardId, deckId = deckId, front = state.front, back = state.back),
                    )
                } else {
                    repository.insertCard(
                        CardEntity(
                            cardId = UUID.randomUUID().toString(),
                            deckId = deckId,
                            front = state.front,
                            back = state.back,
                        ),
                    )
                }
                _uiState.update { it.copy(isSaved = true) }
            }
        }

        fun deleteCard() {
            if (cardId == null) return
            viewModelScope.launch {
                repository.getCardById(cardId)?.let { card ->
                    repository.deleteCard(card)
                }
                _uiState.update { it.copy(isSaved = true) }
            }
        }
    }
