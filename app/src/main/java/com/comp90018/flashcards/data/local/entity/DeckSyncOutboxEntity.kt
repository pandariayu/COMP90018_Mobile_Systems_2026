package com.comp90018.flashcards.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One pending cloud push or delete for a deck the signed-in user changed locally.
 */
@Entity(
    tableName = "deck_sync_outbox",
    indices = [Index(value = ["ownerId"])],
)
data class DeckSyncOutboxEntity(
    @PrimaryKey val deckId: String,
    val ownerId: String,
    val operation: String,
    val revision: Long,
    val enqueuedAtEpochMillis: Long,
)
