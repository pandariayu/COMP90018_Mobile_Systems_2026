package com.comp90018.flashcards.data.auth

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.comp90018.flashcards.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google sign-in via Credential Manager.
 *
 * The app receives an ID token, Firebase verifies it, and the Firebase uid becomes the deck owner.
 * The Google password never reaches this app.
 */
@Singleton
class AuthRepository
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        private val firebaseAuthProvider: FirebaseAuthProvider,
    ) {
        private val firebaseAuth: FirebaseAuth?
            get() = firebaseAuthProvider.auth

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        val authState: StateFlow<AuthState> =
            callbackFlow {
                val auth = firebaseAuth
                if (auth == null) {
                    trySend(AuthState.SignedOut)
                    awaitClose { }
                    return@callbackFlow
                }
                val listener =
                    FirebaseAuth.AuthStateListener { current ->
                        trySend(current.currentUser.toAuthState())
                    }
                auth.addAuthStateListener(listener)
                awaitClose { auth.removeAuthStateListener(listener) }
            }.stateIn(scope, SharingStarted.Eagerly, AuthState.Loading)

        val currentUid: String?
            get() = firebaseAuth?.currentUser?.uid

        val currentEmail: String?
            get() = firebaseAuth?.currentUser?.email

        @Suppress("TooGenericExceptionCaught")
        suspend fun signInWithGoogle(context: Context): Result<Unit> {
            val auth = firebaseAuth
            val webClientId = context.getString(R.string.web_client_id)
            if (auth == null || !webClientId.isConfigured()) {
                return Result.failure(IllegalStateException(NOT_CONFIGURED_MESSAGE))
            }
            return try {
                val idToken = requestGoogleIdToken(context, webClientId)
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                auth.signInWithCredential(credential).await()
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: GetCredentialCancellationException) {
                Result.success(Unit)
            } catch (error: Exception) {
                Result.failure(error)
            }
        }

        suspend fun registerWithEmail(
            email: String,
            password: String,
        ): Result<Unit> =
            runAuth { auth ->
                auth.createUserWithEmailAndPassword(email.trim(), password).await()
            }

        suspend fun signInWithEmail(
            email: String,
            password: String,
        ): Result<Unit> =
            runAuth { auth ->
                auth.signInWithEmailAndPassword(email.trim(), password).await()
            }

        suspend fun sendPasswordReset(email: String): Result<Unit> =
            runAuth { auth ->
                auth.sendPasswordResetEmail(email.trim()).await()
            }

        suspend fun signOut() {
            firebaseAuth?.signOut()
            clearStoredCredential()
        }

        private suspend fun requestGoogleIdToken(
            context: Context,
            webClientId: String,
        ): String {
            val credentialManager = CredentialManager.create(context)
            return try {
                idTokenFrom(credentialManager.getCredential(context, googleIdRequest(webClientId)))
            } catch (_: NoCredentialException) {
                // One Tap has no saved credential yet. The button flow still shows the account picker.
                idTokenFrom(credentialManager.getCredential(context, signInButtonRequest(webClientId)))
            }
        }

        private fun googleIdRequest(webClientId: String): GetCredentialRequest {
            val option =
                GetGoogleIdOption
                    .Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(webClientId)
                    .setAutoSelectEnabled(false)
                    .build()
            return GetCredentialRequest.Builder().addCredentialOption(option).build()
        }

        private fun signInButtonRequest(webClientId: String): GetCredentialRequest {
            val option = GetSignInWithGoogleOption.Builder(webClientId).build()
            return GetCredentialRequest.Builder().addCredentialOption(option).build()
        }

        private fun idTokenFrom(response: GetCredentialResponse): String {
            val credential = response.credential
            if (
                credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                return GoogleIdTokenCredential.createFrom(credential.data).idToken
            }
            error("Google sign-in did not return an ID token.")
        }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun clearStoredCredential() {
            try {
                CredentialManager.create(appContext).clearCredentialState(ClearCredentialStateRequest())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Could not clear Google credentials", error)
            }
        }

        private fun FirebaseUser?.toAuthState(): AuthState {
            val user = this ?: return AuthState.SignedOut
            return AuthState.SignedIn(
                SignedInUser(
                    uid = user.uid,
                    displayName = user.displayName,
                    email = user.email,
                ),
            )
        }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun runAuth(block: suspend (FirebaseAuth) -> Unit): Result<Unit> {
            val auth = firebaseAuth ?: return Result.failure(IllegalStateException(NOT_CONFIGURED_MESSAGE))
            return try {
                block(auth)
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: FirebaseAuthException) {
                Result.failure(IllegalStateException(messageFor(error)))
            } catch (error: Exception) {
                Result.failure(error)
            }
        }

        private fun messageFor(error: FirebaseAuthException): String =
            when (error.errorCode) {
                "ERROR_EMAIL_ALREADY_IN_USE" -> "That email is already registered. Sign in instead."
                "ERROR_INVALID_EMAIL" -> "Enter a valid email address."
                "ERROR_WEAK_PASSWORD" -> "Password must be at least 6 characters."
                "ERROR_USER_NOT_FOUND",
                "ERROR_WRONG_PASSWORD",
                "ERROR_INVALID_CREDENTIAL",
                -> "Email or password is incorrect."
                "ERROR_OPERATION_NOT_ALLOWED" -> "Email sign-in is not enabled in Firebase."
                else -> error.message ?: "Sign-in failed"
            }

        private fun String.isConfigured(): Boolean = isNotBlank() && this != MISSING_WEB_CLIENT_ID

        private companion object {
            const val TAG = "AuthRepository"
            const val MISSING_WEB_CLIENT_ID = "MISSING_WEB_CLIENT_ID"
            const val NOT_CONFIGURED_MESSAGE =
                "Google sign-in is not configured. Add app/google-services.json and rebuild."
        }
    }
