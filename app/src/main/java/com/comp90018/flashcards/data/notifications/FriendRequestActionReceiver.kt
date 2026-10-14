package com.comp90018.flashcards.data.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.comp90018.flashcards.data.auth.AuthRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** The notification action queues durable work without opening an Activity. */
@AndroidEntryPoint
class FriendRequestActionReceiver : BroadcastReceiver() {
    @Inject lateinit var auth: AuthRepository

    @Inject lateinit var notifications: FriendRequestNotifications

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val uid = intent.getStringExtra(FriendRequestNotifications.ACCOUNT_UID)
        val requestId = intent.getStringExtra(FriendRequestNotifications.REQUEST_ID)
        val action = intent.action?.takeIf { it in setOf("accept", "decline") }
        if (uid == null || requestId == null || action == null) return
        if (auth.currentUid != uid) {
            notifications.dismiss(uid, requestId)
            return
        }
        val work =
            OneTimeWorkRequestBuilder<FriendRequestResponseWorker>()
                .setInputData(
                    Data
                        .Builder()
                        .putString(FriendRequestNotifications.ACCOUNT_UID, uid)
                        .putString(FriendRequestNotifications.REQUEST_ID, requestId)
                        .putString("response", action)
                        .build(),
                ).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        notifications.responding(uid, requestId, action)
        WorkManager
            .getInstance(
                context,
            ).enqueueUniqueWork("friend-response:$uid:$requestId", ExistingWorkPolicy.KEEP, work)
    }
}
