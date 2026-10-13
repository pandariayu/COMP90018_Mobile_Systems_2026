package com.comp90018.flashcards.ui.deck

import com.comp90018.flashcards.data.remote.DeckVisibility
import com.comp90018.flashcards.data.remote.RemoteDeck
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class PublicDeckSearchTest {
    private val decks =
        listOf(
            deck(id = "1", name = "Android quiz", author = "Ada Lovelace"),
            deck(id = "2", name = "Capitals", author = "Grace Hopper"),
        )

    @Test
    fun `blank search keeps every public deck`() {
        assertEquals(listOf("1", "2"), decks.matchingPublicSearch("  ").map { it.deckId })
    }

    @Test
    fun `search matches deck name or creator display name`() {
        assertEquals(listOf("1"), decks.matchingPublicSearch("android").map { it.deckId })
        assertEquals(listOf("2"), decks.matchingPublicSearch("GRACE").map { it.deckId })
        assertEquals(listOf("1"), decks.matchingPublicSearch(" lovelace ").map { it.deckId })
        assertEquals(emptyList<String>(), decks.matchingPublicSearch("biology").map { it.deckId })
    }

    @Test
    fun `legacy creator emails are not used for public search`() {
        val legacyDeck = decks.first().copy(authorEmail = "private@example.com", ownerDisplayName = null)
        assertEquals(emptyList<RemoteDeck>(), listOf(legacyDeck).matchingPublicSearch("private@example.com"))
        assertEquals(listOf(legacyDeck), listOf(legacyDeck).matchingPublicSearch("android"))
    }

    private fun deck(
        id: String,
        name: String,
        author: String,
    ) = RemoteDeck(
        deckId = id,
        name = name,
        ownerId = "uid",
        visibility = DeckVisibility.PUBLIC,
        updatedAt = Instant.EPOCH,
        cardCount = 1,
        ownerDisplayName = author,
    )
}
