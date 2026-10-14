package com.comp90018.flashcards.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comp90018.flashcards.data.account.AccountProfiles
import com.comp90018.flashcards.data.account.AccountRepository
import com.comp90018.flashcards.data.auth.AuthRepository
import com.comp90018.flashcards.data.auth.AuthState
import com.comp90018.flashcards.data.auth.SignedInUser
import com.comp90018.flashcards.data.notifications.NotificationDeviceRegistration
import com.comp90018.flashcards.data.social.SocialApiException
import com.comp90018.flashcards.data.social.SocialProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SessionStage { LOADING, SIGNED_OUT, ONBOARDING, READY, UNAVAILABLE, DELETING }

data class AppSession(
    val stage: SessionStage = SessionStage.LOADING,
    val user: SignedInUser? = null,
    val profile: SocialProfile? = null,
    val error: String? = null,
)

@HiltViewModel
class SessionViewModel
    @Inject
    constructor(
        private val auth: AuthRepository,
        accounts: AccountRepository,
        private val devices: NotificationDeviceRegistration,
    ) : ViewModel() {
        private val coordinator = SessionCoordinator(auth.authState, accounts, viewModelScope)
        val session = coordinator.session

        fun retry() = coordinator.retry()

        fun signOut() {
            viewModelScope.launch {
                val uid = auth.currentUid
                if (uid != null) {
                    // Signing out remains available offline; delivery also checks the local account.
                    try {
                        devices.unregister(uid)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: java.io.IOException) {
                        // A stale token cannot show notifications for a signed-out account.
                    } catch (_: IllegalStateException) {
                        // The account already changed.
                    }
                }
                auth.signOut()
            }
        }
    }

class SessionCoordinator(
    authState: StateFlow<AuthState>,
    private val accounts: AccountProfiles,
    scope: CoroutineScope,
) {
    private val state = MutableStateFlow(AppSession())
    val session = state.asStateFlow()
    private val retries = MutableStateFlow(0)

    init {
        scope.launch {
            combine(authState, accounts.profileChanges, retries) { authState, _, _ -> authState }
                .collectLatest { current ->
                    when (current) {
                        is AuthState.Loading -> state.value = AppSession()
                        is AuthState.SignedOut -> state.value = AppSession(SessionStage.SIGNED_OUT)
                        is AuthState.SignedIn -> load(current.user)
                    }
                }
        }
    }

    fun retry() {
        retries.update { it + 1 }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun load(user: SignedInUser) {
        if (accounts.isDeleting(user.uid)) {
            state.value = AppSession(SessionStage.DELETING, user)
            return
        }
        val cached = accounts.cachedProfile(user.uid)
        state.value = if (cached != null) AppSession(SessionStage.READY, user, cached) else AppSession(user = user)
        try {
            val profile = accounts.loadProfile(user.uid)
            state.value =
                AppSession(if (profile == null) SessionStage.ONBOARDING else SessionStage.READY, user, profile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            state.value = failedSession(user, cached, error)
        }
    }

    private suspend fun failedSession(
        user: SignedInUser,
        cached: SocialProfile?,
        error: Exception,
    ): AppSession =
        if (error is SocialApiException && error.code == "account_deleting") {
            accounts.rememberDeletion(user.uid)
            AppSession(SessionStage.DELETING, user)
        } else if (cached != null) {
            AppSession(SessionStage.READY, user, cached)
        } else {
            AppSession(
                SessionStage.UNAVAILABLE,
                user,
                error = "Could not load your profile. Check your connection and retry.",
            )
        }
}
