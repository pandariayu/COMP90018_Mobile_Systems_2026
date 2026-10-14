package com.comp90018.flashcards.data.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.comp90018.flashcards.data.auth.AuthRepository
import com.comp90018.flashcards.data.auth.AuthState
import com.comp90018.flashcards.data.social.FriendsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FriendNotificationCoordinator
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val auth: AuthRepository,
        private val friends: FriendsRepository,
        private val notifications: FriendRequestNotifications,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        fun start() {
            notifications.createChannel()
            scope.launch {
                auth.authState.collectLatest { state ->
                    if (state == AuthState.Loading) return@collectLatest
                    val uid = (state as? AuthState.SignedIn)?.user?.uid
                    notifications.clearOtherAccounts(uid)
                    if (uid != null) {
                        refresh()
                        friends.incomingRequests(uid).filterNotNull().collect { requests ->
                            if (auth.currentUid == uid) notifications.sync(uid, requests)
                        }
                    }
                }
            }
        }

        fun refresh() {
            val uid = auth.currentUid ?: return
            friends.currentIncoming(uid)?.let { notifications.sync(uid, it) }
            val work =
                OneTimeWorkRequestBuilder<NotificationRegistrationWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            WorkManager
                .getInstance(
                    context,
                ).enqueueUniqueWork("friend-push-registration", ExistingWorkPolicy.REPLACE, work)
        }
    }
