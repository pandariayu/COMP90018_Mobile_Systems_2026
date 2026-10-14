package com.comp90018.flashcards.data.notifications

import com.comp90018.flashcards.data.social.FriendsRepository
import com.comp90018.flashcards.data.social.SocialApiException
import com.comp90018.flashcards.data.social.SocialSession
import java.io.IOException
import javax.inject.Inject

enum class FriendResponseOutcome { COMPLETE, RETRY, STALE, FAILED, ACCOUNT_CHANGED }

class FriendRequestActionHandler
    @Inject
    constructor(
        private val session: SocialSession,
        private val friends: FriendsRepository,
    ) {
        suspend fun respond(
            uid: String,
            requestId: String,
            action: String,
        ): FriendResponseOutcome {
            if (action !in setOf("accept", "decline")) return FriendResponseOutcome.FAILED
            return try {
                session.requireUser(uid)
                friends.respond(uid, requestId, action)
                FriendResponseOutcome.COMPLETE
            } catch (_: IllegalStateException) {
                FriendResponseOutcome.ACCOUNT_CHANGED
            } catch (error: SocialApiException) {
                when {
                    error.code == "request_not_found" || error.code == "request_not_pending" ->
                        FriendResponseOutcome.STALE
                    error.status >= 500 || error.status == 429 -> FriendResponseOutcome.RETRY
                    else -> FriendResponseOutcome.FAILED
                }
            } catch (_: IOException) {
                FriendResponseOutcome.RETRY
            }
        }
    }
