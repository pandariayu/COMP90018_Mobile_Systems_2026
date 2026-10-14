package com.comp90018.flashcards.data.notifications

import com.comp90018.flashcards.data.social.FriendsRepository
import com.comp90018.flashcards.data.social.SocialApi
import com.comp90018.flashcards.data.social.SocialHttpResponse
import com.comp90018.flashcards.data.social.SocialSession
import com.comp90018.flashcards.data.social.SocialTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FriendRequestActionHandlerTest {
    @Test
    fun `accept and decline send authenticated responses to the request endpoint`() =
        runBlocking {
            for (action in listOf("accept", "decline")) {
                val fixture = Fixture()
                val result = fixture.handler.respond("alice", "request-id", action)
                assertEquals(FriendResponseOutcome.COMPLETE, result)
                assertEquals(listOf("POST" to """{"action":"$action"}"""), fixture.calls)
            }
        }

    @Test
    fun `signed out or switched account cannot act on an old notification`() =
        runBlocking {
            for (uid in listOf(null, "bob")) {
                val fixture = Fixture().apply { session.uid = uid }
                val result = fixture.handler.respond("alice", "request-id", "accept")
                assertEquals(FriendResponseOutcome.ACCOUNT_CHANGED, result)
                assertTrue(fixture.calls.isEmpty())
            }
        }

    @Test
    fun `offline or server outage retries while stale requests are dismissed`() =
        runBlocking {
            val cases =
                listOf(
                    Triple(404, "request_not_found", FriendResponseOutcome.STALE),
                    Triple(409, "request_not_pending", FriendResponseOutcome.STALE),
                    Triple(403, "action_forbidden", FriendResponseOutcome.FAILED),
                    Triple(401, "invalid_token", FriendResponseOutcome.FAILED),
                    Triple(503, "storage_unavailable", FriendResponseOutcome.RETRY),
                    Triple(429, "rate_limited", FriendResponseOutcome.RETRY),
                )
            for ((status, code, expected) in cases) {
                val body = """{"error":{"code":"$code","message":"Try again"}}"""
                val fixture = Fixture(SocialHttpResponse(status, body))
                assertEquals(expected, fixture.handler.respond("alice", "request-id", "accept"))
            }
            val offline = Fixture().apply { failure = IOException("offline") }
            assertEquals(FriendResponseOutcome.RETRY, offline.handler.respond("alice", "request-id", "accept"))
        }

    @Test
    fun `unsupported actions cannot mutate the request`() =
        runBlocking {
            val fixture = Fixture()
            assertEquals(FriendResponseOutcome.FAILED, fixture.handler.respond("alice", "request-id", "cancel"))
            assertTrue(fixture.calls.isEmpty())
        }

    private class TestSession : SocialSession {
        override var uid: String? = "alice"

        override suspend fun token(
            ownerId: String,
            refresh: Boolean,
        ): String {
            requireUser(ownerId)
            return "verified-token"
        }
    }

    private class Fixture(
        val response: SocialHttpResponse = SocialHttpResponse(200, "{}"),
    ) {
        val session = TestSession()
        val calls = mutableListOf<Pair<String, String?>>()
        var failure: IOException? = null
        private val transport =
            object : SocialTransport {
                override suspend fun execute(
                    url: String,
                    method: String,
                    token: String,
                    body: String?,
                ): SocialHttpResponse {
                    assertEquals("https://example.test/v1/friend-requests/request-id/respond", url)
                    assertEquals("verified-token", token)
                    calls += method to body
                    failure?.let { throw it }
                    return response
                }
            }
        val handler =
            FriendRequestActionHandler(
                session,
                FriendsRepository(SocialApi("https://example.test", session, transport)),
            )
    }
}
