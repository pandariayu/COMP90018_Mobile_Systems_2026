package com.comp90018.flashcards.data.notifications

import com.comp90018.flashcards.data.auth.AuthRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class FriendMessagingService : FirebaseMessagingService() {
    @Inject lateinit var auth: AuthRepository

    @Inject lateinit var notifications: FriendRequestNotifications

    @Inject lateinit var coordinator: FriendNotificationCoordinator

    override fun onNewToken(token: String) {
        coordinator.refresh()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val uid = message.data["recipient_uid"]
        val requestId = message.data["request_id"]
        if (message.data["type"] == "friend_request") {
            if (uid == null || requestId == null || auth.currentUid != uid) return
            if (runCatching { UUID.fromString(requestId) }.isFailure) return
            val name = message.data["sender_name"]?.take(80) ?: "Someone"
            notifications.show(uid, requestId, name)
        }
    }
}
