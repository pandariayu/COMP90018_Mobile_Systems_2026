package com.comp90018.flashcards.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.comp90018.flashcards.data.local.entity.CardEntity
import kotlinx.coroutines.flow.Flow

/**
 * Interface for Card database operations.
 */
@Dao
interface CardDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCard(card: CardEntity)

    @Query("SELECT * FROM cards WHERE deckId = :deckId")
    fun getCardsByDeckId(deckId: String): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards WHERE deckId = :deckId ORDER BY position ASC, cardId ASC")
    suspend fun listCardsByDeckId(deckId: String): List<CardEntity>

    @Query("SELECT * FROM cards WHERE cardId = :cardId")
    suspend fun getCardById(cardId: String): CardEntity?

    @Update
    suspend fun updateCard(card: CardEntity)

    @Delete
    suspend fun deleteCard(card: CardEntity)

    /** Deletes the card along with every user's FSRS state and review logs for it. */
    @Transaction
    suspend fun deleteCardAndReviews(card: CardEntity) {
        deleteCard(card)
        deleteFsrsStates(card.cardId)
        deleteReviewLogs(card.cardId)
    }

    @Query("DELETE FROM card_fsrs_states WHERE cardId = :cardId")
    suspend fun deleteFsrsStates(cardId: String)

    @Query("DELETE FROM review_logs WHERE cardId = :cardId")
    suspend fun deleteReviewLogs(cardId: String)

    @Query("SELECT COUNT(*) FROM cards")
    suspend fun getCardCount(): Int
}
