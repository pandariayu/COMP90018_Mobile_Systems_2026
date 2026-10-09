package com.comp90018.flashcards.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.comp90018.flashcards.data.local.dao.DeckSyncOutboxDao
import com.comp90018.flashcards.data.local.entity.DeckSyncOutboxEntity

/**
 * Separate from [FlashcardDatabase] so adding the outbox does not wipe existing decks.
 */
@Database(
    entities = [DeckSyncOutboxEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class DeckSyncDatabase : RoomDatabase() {
    abstract fun outboxDao(): DeckSyncOutboxDao
}
