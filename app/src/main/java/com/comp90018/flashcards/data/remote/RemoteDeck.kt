package com.comp90018.flashcards.data.remote

import java.time.Instant

/**
 * Cloud deck document at `decks/{deckId}`. See [docs/cloud-deck-schema.md].
 */
data class RemoteDeck(
    val deckId: String,
    val name: String,
    val ownerId: String,
    val visibility: DeckVisibility,
    val updatedAt: Instant,
    val cardCount: Int,
    val sourceDeckId: String? = null,
    val authorEmail: String? = null,
)
