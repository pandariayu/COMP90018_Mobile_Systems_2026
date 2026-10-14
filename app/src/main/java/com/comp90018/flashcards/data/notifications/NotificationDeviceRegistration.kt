package com.comp90018.flashcards.data.notifications

import android.content.Context
import com.comp90018.flashcards.data.social.SocialApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationDeviceRegistration
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val api: SocialApi,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Excluded from Android backup so a restored installation gets a distinct identity.
        private val preferences = context.getSharedPreferences("notification_device", Context.MODE_PRIVATE)
        private val deviceId =
            preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
                check(preferences.edit().putString("device_id", it).commit())
            }

        suspend fun register(
            uid: String,
            token: String,
        ) {
            api.request(uid, "PUT", "/v1/notification-devices/$deviceId", buildJsonObject { put("token", token) })
        }

        suspend fun unregister(uid: String) {
            // Await outside the blocking HTTP coroutine so offline logout stays responsive.
            val removal = scope.async { api.request(uid, "DELETE", "/v1/notification-devices/$deviceId") }
            try {
                withTimeoutOrNull(3_000) { removal.await() }
            } finally {
                removal.cancel()
            }
        }
    }
