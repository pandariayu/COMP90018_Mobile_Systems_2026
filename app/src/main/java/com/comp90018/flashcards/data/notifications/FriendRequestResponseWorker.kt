package com.comp90018.flashcards.data.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.comp90018.flashcards.data.auth.AuthRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class FriendRequestResponseWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted parameters: WorkerParameters,
        private val handler: FriendRequestActionHandler,
        private val auth: AuthRepository,
        private val notifications: FriendRequestNotifications,
    ) : CoroutineWorker(context, parameters) {
        override suspend fun doWork(): Result {
            val uid = inputData.getString(FriendRequestNotifications.ACCOUNT_UID)
            val requestId = inputData.getString(FriendRequestNotifications.REQUEST_ID)
            val action = inputData.getString("response")
            if (uid == null || requestId == null || action == null) return Result.failure()
            return when (handler.respond(uid, requestId, action)) {
                FriendResponseOutcome.RETRY -> {
                    if (runAttemptCount < 5) {
                        Result.retry()
                    } else {
                        restore(uid, requestId)
                        Result.failure()
                    }
                }
                FriendResponseOutcome.FAILED -> {
                    restore(uid, requestId)
                    Result.failure()
                }
                else -> {
                    notifications.dismiss(uid, requestId)
                    Result.success()
                }
            }
        }

        private fun restore(
            uid: String,
            requestId: String,
        ) {
            if (auth.currentUid == uid) {
                notifications.show(uid, requestId, "", retry = true)
            } else {
                notifications.dismiss(uid, requestId)
            }
        }
    }
