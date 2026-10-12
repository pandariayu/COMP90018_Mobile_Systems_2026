package com.comp90018.flashcards.domain.repository

import com.comp90018.flashcards.data.local.entity.CardEntity
import com.comp90018.flashcards.data.local.entity.DeckEntity
import com.comp90018.flashcards.domain.fsrs.FsrsCard
import com.comp90018.flashcards.domain.model.CardWithState
import com.comp90018.flashcards.domain.model.Rating
import kotlinx.coroutines.flow.Flow

interface CardRepository {
    // Deck Operations
    fun getAllDecks(ownerId: String): Flow<List<DeckEntity>>

    suspend fun insertDeck(deck: DeckEntity)

    /**
     * Inserts a deck and its cards, then queues one cloud sync. Study progress is not copied.
     */
    suspend fun insertDeckWithCards(
        deck: DeckEntity,
        cards: List<CardEntity>,
    )

    suspend fun updateDeck(deck: DeckEntity)

    suspend fun deleteDeck(deck: DeckEntity)

    suspend fun getDeckById(deckId: String): DeckEntity?

    // Card Operations
    fun getCardsByDeckId(deckId: String): Flow<List<CardEntity>>

    /**
     * Cards in [deckId] that are due for [userId] now, including cards they have never studied.
     */
    fun getDueCards(
        userId: String,
        deckId: String,
    ): Flow<List<CardWithState>>

    suspend fun getCardById(cardId: String): CardEntity?

    suspend fun insertCard(card: CardEntity)

    suspend fun updateCard(card: CardEntity)

    suspend fun deleteCard(card: CardEntity)

    /**
     * Records that [userId] reviewed [cardId] with [rating], reschedules the card with FSRS
     * and returns its new state.
     */
    suspend fun reviewCard(
        userId: String,
        cardId: String,
        rating: Rating,
    ): FsrsCard
}
