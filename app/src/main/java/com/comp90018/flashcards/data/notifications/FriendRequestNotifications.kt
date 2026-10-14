package com.comp90018.flashcards.data.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.comp90018.flashcards.MainActivity
import com.comp90018.flashcards.R
import com.comp90018.flashcards.data.social.IncomingFriendRequests
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Shared by push delivery and foreground refresh; one notification per request. */
@Singleton
class FriendRequestNotifications
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val manager = context.getSystemService(NotificationManager::class.java)
        private val preferences = context.getSharedPreferences("friend_notifications", Context.MODE_PRIVATE)

        fun createChannel() {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Friend requests", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "New friend requests with Accept and Decline actions"
                },
            )
        }

        @Synchronized
        fun show(
            uid: String,
            requestId: String,
            senderName: String,
            retry: Boolean = false,
        ) {
            if (!canNotify()) return
            val seen = preferences.getStringSet("seen_$uid", emptySet()).orEmpty()
            if (!retry && requestId in seen) return
            val notification =
                builder(uid, requestId, "New friend request")
                    .setContentText(
                        if (retry) {
                            "Could not update the request. Try again or open Friends."
                        } else {
                            "$senderName wants to be your friend."
                        },
                    ).addAction(0, "Accept", action(uid, requestId, "accept"))
                    .addAction(0, "Decline", action(uid, requestId, "decline"))
                    .build()
            manager.notify(tag(uid, requestId), NOTIFICATION_ID, notification)
            preferences.edit().putStringSet("seen_$uid", seen + requestId).apply()
        }

        fun responding(
            uid: String,
            requestId: String,
            action: String,
        ) {
            if (!canNotify()) return
            val title = if (action == "accept") "Accepting friend request…" else "Declining friend request…"
            manager.notify(
                tag(uid, requestId),
                NOTIFICATION_ID,
                builder(uid, requestId, title)
                    .setContentText("Your response will be sent when connected.")
                    .setOngoing(true)
                    .build(),
            )
        }

        fun dismiss(
            uid: String,
            requestId: String,
        ) {
            manager.cancel(tag(uid, requestId), NOTIFICATION_ID)
        }

        fun sync(
            uid: String,
            snapshot: IncomingFriendRequests,
        ) {
            val requests = snapshot.requests
            val pending = requests.map { tag(uid, it.id) }.toSet()
            manager.activeNotifications
                .filter {
                    it.tag?.startsWith("friend:$uid:") == true &&
                        it.tag !in pending &&
                        it.postTime <= snapshot.readStartedAt
                }.forEach { manager.cancel(it.tag, it.id) }
            requests.forEach { show(uid, it.id, it.sender.displayName) }
        }

        fun clearOtherAccounts(uid: String?) {
            manager.activeNotifications
                .filter {
                    it.tag?.startsWith("friend:") == true && (uid == null || !it.tag.startsWith("friend:$uid:"))
                }.forEach { manager.cancel(it.tag, it.id) }
        }

        private fun builder(
            uid: String,
            requestId: String,
            title: String,
        ): NotificationCompat.Builder {
            createChannel()
            val intent =
                Intent(context, MainActivity::class.java)
                    .setAction(OPEN_FRIENDS)
                    .setData(Uri.parse("clashcards://friends/$uid/$requestId"))
                    .putExtra(ACCOUNT_UID, uid)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val contentIntent =
                PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            return NotificationCompat
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_friend_notification)
                .setContentTitle(title)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
        }

        private fun action(
            uid: String,
            requestId: String,
            response: String,
        ): PendingIntent {
            val intent =
                Intent(context, FriendRequestActionReceiver::class.java)
                    .setAction(response)
                    .setData(Uri.parse("clashcards://friend-request/$uid/$requestId/$response"))
                    .putExtra(ACCOUNT_UID, uid)
                    .putExtra(REQUEST_ID, requestId)
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private fun canNotify(): Boolean =
            manager.areNotificationsEnabled() &&
                (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                )

        private fun tag(
            uid: String,
            requestId: String,
        ) = "friend:$uid:$requestId"

        companion object {
            const val ACCOUNT_UID = "account_uid"
            const val REQUEST_ID = "request_id"
            const val OPEN_FRIENDS = "com.comp90018.flashcards.OPEN_FRIENDS"
            private const val CHANNEL = "friend_requests"
            private const val NOTIFICATION_ID = 1
        }
    }
