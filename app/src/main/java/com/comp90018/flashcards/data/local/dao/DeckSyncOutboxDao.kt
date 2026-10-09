package com.comp90018.flashcards.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.comp90018.flashcards.data.local.entity.DeckSyncOutboxEntity

@Dao
interface DeckSyncOutboxDao {
    @Query(
        "SELECT * FROM deck_sync_outbox WHERE ownerId = :ownerId " +
            "ORDER BY enqueuedAtEpochMillis ASC, revision ASC",
    )
    suspend fun pendingForOwner(ownerId: String): List<DeckSyncOutboxEntity>

    @Query("SELECT * FROM deck_sync_outbox WHERE deckId = :deckId")
    suspend fun find(deckId: String): DeckSyncOutboxEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: DeckSyncOutboxEntity)

    @Query("DELETE FROM deck_sync_outbox WHERE deckId = :deckId AND revision = :revision")
    suspend fun deleteIfRevision(
        deckId: String,
        revision: Long,
    )

    @Transaction
    suspend fun enqueue(
        deckId: String,
        ownerId: String,
        operation: String,
        enqueuedAtEpochMillis: Long,
    ) {
        val revision = (find(deckId)?.revision ?: 0L) + 1L
        upsert(
            DeckSyncOutboxEntity(
                deckId = deckId,
                ownerId = ownerId,
                operation = operation,
                revision = revision,
                enqueuedAtEpochMillis = enqueuedAtEpochMillis,
            ),
        )
    }
}
