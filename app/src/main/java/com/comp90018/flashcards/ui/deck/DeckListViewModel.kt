package com.comp90018.flashcards.ui.deck

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comp90018.flashcards.data.auth.AuthRepository
import com.comp90018.flashcards.data.auth.AuthState
import com.comp90018.flashcards.data.local.DatabaseInitializer
import com.comp90018.flashcards.data.local.entity.DeckEntity
import com.comp90018.flashcards.domain.repository.CardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class DeckListViewModel
    @Inject
    constructor(
        private val repository: CardRepository,
        private val authRepository: AuthRepository,
        private val databaseInitializer: DatabaseInitializer,
    ) : ViewModel() {
        val displayName: StateFlow<String> =
            authRepository.authState
                .map { state -> state.displayName() }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

        @OptIn(ExperimentalCoroutinesApi::class)
        val decks: StateFlow<List<DeckEntity>> =
            authRepository.authState
                .flatMapLatest { state ->
                    val uid = (state as? AuthState.SignedIn)?.user?.uid
                    if (uid == null) {
                        flowOf(emptyList())
                    } else {
                        repository.getAllDecks(uid)
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        init {
            viewModelScope.launch {
                val uid =
                    authRepository.authState
                        .mapNotNull { state -> (state as? AuthState.SignedIn)?.user?.uid }
                        .first()
                databaseInitializer.seedForUser(uid)
            }
        }

        fun createDeck(
            name: String,
            description: String = "",
        ) {
            val uid = authRepository.currentUid ?: return
            viewModelScope.launch {
                repository.insertDeck(
                    DeckEntity(
                        deckId = UUID.randomUUID().toString(),
                        name = name,
                        description = description,
                        ownerId = uid,
                    ),
                )
            }
        }

        fun deleteDeck(deck: DeckEntity) {
            viewModelScope.launch {
                repository.deleteDeck(deck)
            }
        }

        fun signOut() {
            viewModelScope.launch {
                authRepository.signOut()
            }
        }
    }

private fun AuthState.displayName(): String {
    val user = (this as? AuthState.SignedIn)?.user ?: return ""
    return user.displayName?.takeIf { it.isNotBlank() } ?: user.email.orEmpty()
}
