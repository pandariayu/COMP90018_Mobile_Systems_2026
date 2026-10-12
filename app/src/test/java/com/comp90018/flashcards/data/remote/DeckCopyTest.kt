package com.comp90018.flashcards.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant

class DeckCopyTest {
    @Test
    fun `save a copy uses new ids and keeps the source cards`() {
        val source =
            RemoteDeck(
                deckId = "source-deck",
                name = "Capitals",
                ownerId = "owner",
                visibility = DeckVisibility.PUBLIC,
                updatedAt = Instant.parse("2026-10-09T00:00:00Z"),
                cardCount = 2,
            )
        val cards =
            listOf(
                RemoteCard(cardId = "c2", deckId = "source-deck", front = "Japan", back = "Tokyo", position = 1),
                RemoteCard(cardId = "c1", deckId = "source-deck", front = "Australia", back = "Canberra", position = 0),
            )

        val (deck, localCards) =
            copiedDeck(
                source = source,
                cards = cards,
                ownerId = "reader",
                newDeckId = "copy-deck",
                newCardIds = listOf("new-1", "new-0"),
            )

        assertEquals("copy-deck", deck.deckId)
        assertEquals("reader", deck.ownerId)
        assertEquals("Capitals", deck.name)
        assertEquals("source-deck", deck.sourceDeckId)
        assertEquals("Capitals", deck.copiedFromName)
        assertEquals(listOf("Australia", "Japan"), localCards.map { it.front })
        assertEquals(listOf("new-1", "new-0"), localCards.map { it.cardId })
        assertEquals("copy-deck", localCards.first().deckId)
        localCards.forEach { card ->
            assertNotEquals("c1", card.cardId)
            assertNotEquals("c2", card.cardId)
        }
    }
}
