package com.comp90018.flashcards.data.remote

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class RemoteMappersTest {
    @Test
    fun `maps deck document fields`() {
        val updatedAt = Instant.parse("2026-10-08T02:00:00Z")
        val deck =
            RemoteDeckMapper.from(
                deckId = "deck-1",
                data =
                    mapOf(
                        "name" to "Biology",
                        "ownerId" to "uid-1",
                        "visibility" to "public",
                        "updatedAt" to Timestamp(updatedAt.epochSecond, updatedAt.nano),
                        "cardCount" to 3L,
                    ),
            )

        assertEquals("deck-1", deck.deckId)
        assertEquals("Biology", deck.name)
        assertEquals("uid-1", deck.ownerId)
        assertEquals(DeckVisibility.PUBLIC, deck.visibility)
        assertEquals(updatedAt, deck.updatedAt)
        assertEquals(3, deck.cardCount)
    }

    @Test
    fun `unknown visibility defaults to private`() {
        val deck = RemoteDeckMapper.from("d", mapOf("visibility" to "friends-only"))
        assertEquals(DeckVisibility.PRIVATE, deck.visibility)
    }

    @Test
    fun `maps card document fields`() {
        val card =
            RemoteCardMapper.from(
                deckId = "deck-1",
                cardId = "card-1",
                data =
                    mapOf(
                        "front" to "Q",
                        "back" to "A",
                        "position" to 2,
                        "frontImageUri" to "content://front",
                    ),
            )

        assertEquals("card-1", card.cardId)
        assertEquals("deck-1", card.deckId)
        assertEquals("Q", card.front)
        assertEquals("A", card.back)
        assertEquals(2, card.position)
        assertEquals("content://front", card.frontImageUri)
        assertNull(card.backImageUri)
    }

    @Test
    fun `deck create map stores catalogue fields only`() {
        val updatedAt = Instant.parse("2026-10-09T00:00:00Z")
        val map =
            RemoteDeck(
                deckId = "deck-1",
                name = "Biology",
                ownerId = "uid-1",
                visibility = DeckVisibility.PRIVATE,
                updatedAt = updatedAt,
                cardCount = 2,
            ).toFirestoreMap()

        assertEquals(setOf("name", "ownerId", "visibility", "updatedAt", "cardCount"), map.keys)
        assertEquals("Biology", map["name"])
        assertEquals("private", map["visibility"])
        assertEquals(2, map["cardCount"])
        assertEquals(updatedAt.epochSecond, (map["updatedAt"] as Timestamp).seconds)
    }

    @Test
    fun `deck update map leaves visibility and owner unchanged`() {
        val map =
            RemoteDeck(
                deckId = "deck-1",
                name = "Biology",
                ownerId = "uid-1",
                visibility = DeckVisibility.PRIVATE,
                updatedAt = Instant.parse("2026-10-09T00:00:00Z"),
                cardCount = 2,
            ).toContentUpdateMap()

        assertEquals(setOf("name", "updatedAt", "cardCount"), map.keys)
        assertFalse(map.containsKey("visibility"))
        assertFalse(map.containsKey("ownerId"))
    }

    @Test
    fun `card write map stores prompt fields only`() {
        val map =
            RemoteCard(
                cardId = "card-1",
                deckId = "deck-1",
                front = "Q",
                back = "A",
                position = 1,
                frontImageUri = "content://front",
            ).toFirestoreMap()

        assertEquals(setOf("front", "back", "position", "frontImageUri"), map.keys)
    }
}
