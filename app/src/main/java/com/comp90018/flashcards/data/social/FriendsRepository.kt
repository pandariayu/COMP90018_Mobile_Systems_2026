package com.comp90018.flashcards.data.social

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

data class IncomingFriendRequests(
    val requests: List<SocialRequest>,
    val readStartedAt: Long,
)

@Singleton
class FriendsRepository
    @Inject
    constructor(
        private val api: SocialApi,
    ) {
        private val incomingIds = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
        private val incomingSnapshots = MutableStateFlow<Map<String, IncomingFriendRequests>>(emptyMap())

        // Serialize reads and responses so an older poll cannot restore a resolved badge.
        private val requestMutex = Mutex()

        fun incomingRequestCount(uid: String) = incomingIds.map { it[uid].orEmpty().size }.distinctUntilChanged()

        fun incomingRequests(uid: String) = incomingSnapshots.map { it[uid] }.distinctUntilChanged()

        fun currentIncoming(uid: String): IncomingFriendRequests? = incomingSnapshots.value[uid]

        suspend fun profile(uid: String): SocialProfile? =
            try {
                socialJson.decodeFromString<SocialProfile>(api.request(uid, "GET", "/v1/me"))
            } catch (error: SocialApiException) {
                if (error.code == "profile_required") null else throw error
            }

        suspend fun saveProfile(
            uid: String,
            username: String,
            name: String,
        ): SocialProfile =
            socialJson.decodeFromString(
                api.request(
                    uid,
                    "PUT",
                    "/v1/me",
                    buildJsonObject {
                        put("username", username.trim())
                        put("display_name", name.trim())
                    },
                ),
            )

        suspend fun addFriend(
            uid: String,
            username: String,
        ) {
            val query = URLEncoder.encode(username.trim(), "UTF-8")
            val recipient =
                socialJson.decodeFromString<SocialProfile>(
                    try {
                        api.request(uid, "GET", "/v1/users/lookup?username=$query")
                    } catch (error: SocialApiException) {
                        if (error.code == "user_not_found") {
                            throw IllegalStateException("User not found", error)
                        }
                        throw error
                    },
                )
            api.request(uid, "POST", "/v1/friend-requests", buildJsonObject { put("recipient_id", recipient.id) })
        }

        suspend fun friends(uid: String): List<SocialFriend> = pages(uid, "/v1/friends?")

        suspend fun requests(
            uid: String,
            incoming: Boolean,
            status: String = "pending",
        ): List<SocialRequest> {
            val direction = if (incoming) "incoming" else "outgoing"
            val path = "/v1/friend-requests?direction=$direction&status=$status&"
            return if (incoming && status == "pending") {
                requestMutex.withLock {
                    val readStartedAt = System.currentTimeMillis()
                    val requests = pages<SocialRequest>(uid, path)
                    incomingIds.update { it + (uid to requests.map { request -> request.id }.toSet()) }
                    incomingSnapshots.update { it + (uid to IncomingFriendRequests(requests, readStartedAt)) }
                    requests
                }
            } else {
                pages(uid, path)
            }
        }

        suspend fun respond(
            uid: String,
            requestId: String,
            action: String,
        ) {
            requestMutex.withLock {
                api.request(
                    uid,
                    "POST",
                    "/v1/friend-requests/$requestId/respond",
                    buildJsonObject { put("action", action) },
                )
                incomingIds.update { it + (uid to (it[uid].orEmpty() - requestId)) }
                incomingSnapshots.update { snapshots ->
                    val snapshot = snapshots[uid]
                    if (snapshot == null) {
                        snapshots
                    } else {
                        snapshots +
                            (uid to snapshot.copy(requests = snapshot.requests.filterNot { it.id == requestId }))
                    }
                }
            }
        }

        suspend fun remove(
            uid: String,
            friendId: String,
        ) {
            api.request(uid, "DELETE", "/v1/friends/$friendId")
        }

        private suspend inline fun <reified T> pages(
            uid: String,
            path: String,
        ): List<T> {
            val rows = mutableListOf<T>()
            do {
                val page =
                    socialJson.decodeFromString<List<T>>(
                        api.request(uid, "GET", "${path}limit=100&offset=${rows.size}"),
                    )
                rows.addAll(page)
            } while (page.size == 100 && rows.size <= 10_000)
            return rows
        }
    }
