package com.comp90018.flashcards.ui.deck

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comp90018.flashcards.data.auth.AuthRepository
import com.comp90018.flashcards.data.remote.DeckExchange
import com.comp90018.flashcards.data.remote.RemoteDeck
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PublicDeckUiState(
    val loading: Boolean = true,
    val decks: List<RemoteDeck> = emptyList(),
    val ownerId: String? = null,
    val savingId: String? = null,
    val query: String = "",
    val error: String? = null,
    val message: String? = null,
)

@HiltViewModel
class PublicDeckViewModel
    @Inject
    constructor(
        private val deckExchange: DeckExchange,
        private val authRepository: AuthRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(PublicDeckUiState())
        val uiState: StateFlow<PublicDeckUiState> = _uiState.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            viewModelScope.launch {
                _uiState.update { it.copy(loading = true, error = null) }
                val result = deckExchange.listPublicDecks()
                _uiState.update { state ->
                    result.fold(
                        onSuccess = { decks ->
                            state.copy(
                                loading = false,
                                decks = decks,
                                ownerId = authRepository.currentUid,
                                error = null,
                            )
                        },
                        onFailure = { error ->
                            state.copy(
                                loading = false,
                                error = error.message ?: "Could not load public decks.",
                            )
                        },
                    )
                }
            }
        }

        fun saveCopy(deckId: String) {
            viewModelScope.launch {
                _uiState.update { it.copy(savingId = deckId, message = null) }
                val result = deckExchange.saveCopy(deckId)
                _uiState.update { state ->
                    result.fold(
                        onSuccess = { state.copy(savingId = null, message = "Saved a copy to My Decks.") },
                        onFailure = { error ->
                            state.copy(
                                savingId = null,
                                message = error.message ?: "Could not save a copy.",
                            )
                        },
                    )
                }
            }
        }

        fun onQueryChange(query: String) {
            _uiState.update { it.copy(query = query) }
        }

        fun consumeMessage() {
            _uiState.update { it.copy(message = null) }
        }
    }
