package com.comp90018.flashcards.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.comp90018.flashcards.data.local.entity.DeckEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DeckDao {
    @Query("SELECT * FROM decks WHERE ownerId = :ownerId")
    fun getAllDecks(ownerId: String): Flow<List<DeckEntity>>

    @Query("SELECT COUNT(*) FROM decks WHERE ownerId = :ownerId")
    suspend fun countDecksForOwner(ownerId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeck(deck: DeckEntity)

    @Update
    suspend fun updateDeck(deck: DeckEntity)

    @Delete
    suspend fun deleteDeck(deck: DeckEntity)

    @Query("SELECT * FROM decks WHERE deckId = :deckId")
    suspend fun getDeckById(deckId: String): DeckEntity?

    @Query("SELECT * FROM decks WHERE ownerId = :ownerId")
    suspend fun listDecksForOwner(ownerId: String): List<DeckEntity>
}
