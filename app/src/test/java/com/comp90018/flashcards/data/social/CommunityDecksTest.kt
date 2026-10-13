package com.comp90018.flashcards.data.social

import com.comp90018.flashcards.data.local.dao.DeckDao
import com.comp90018.flashcards.data.sync.DeckSyncCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock
import java.time.Instant

class CommunityDecksTest {
    @Test
    fun `catalogue loads profile names and timestamps in one API request`() =
        runBlocking {
            val session = FakeSocialSession()
            val transport =
                RecordingTransport {
                    SocialHttpResponse(
                        200,
                        """
                        [
                          {"deckId":"1","name":"Android","ownerId":"bob","visibility":"public",
                           "cardCount":4,"ownerDisplayName":"Jason","updatedAt":"2026-10-13T09:00:00Z",
                           "createdAt":"2026-10-12T09:00:00Z","authorEmail":"legacy@example.com"},
                          {"deckId":"2","name":"Capitals","ownerId":"charlie","visibility":"public",
                           "cardCount":2,"ownerDisplayName":null,"updatedAt":"2026-10-13T08:00:00Z"}
                        ]
                        """.trimIndent(),
                    )
                }
            val repository =
                CommunityDecks(
                    api = SocialApi("https://example.test", session, transport),
                    local = mock<DeckDao>(),
                    sync = mock<DeckSyncCoordinator>(),
                    session = session,
                )

            val decks = repository.catalogue("alice")

            assertEquals(listOf("1", "2"), decks.map { it.deckId })
            assertEquals("Jason", decks.first().ownerDisplayName)
            assertEquals(Instant.parse("2026-10-13T09:00:00Z"), decks.first().updatedAt)
            assertEquals(Instant.parse("2026-10-12T09:00:00Z"), decks.first().createdAt)
            assertNull(decks.first().authorEmail)
            assertNull(decks.last().ownerDisplayName)
            assertNull(decks.last().createdAt)
            assertEquals(1, transport.calls.size)
            assertEquals("https://example.test/v1/decks/public", transport.calls.single().url)
        }
}
