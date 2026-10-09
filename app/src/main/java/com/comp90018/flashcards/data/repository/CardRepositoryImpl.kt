package com.comp90018.flashcards.data.repository

import com.comp90018.flashcards.data.local.dao.CardDao
import com.comp90018.flashcards.data.local.dao.DeckDao
import com.comp90018.flashcards.data.local.dao.ReviewDao
import com.comp90018.flashcards.data.local.entity.CardEntity
import com.comp90018.flashcards.data.local.entity.CardFsrsStateEntity
import com.comp90018.flashcards.data.local.entity.DeckEntity
import com.comp90018.flashcards.data.local.entity.ReviewLogEntity
import com.comp90018.flashcards.data.sync.DeckSyncCoordinator
import com.comp90018.flashcards.domain.fsrs.CardState
import com.comp90018.flashcards.domain.fsrs.FsrsCard
import com.comp90018.flashcards.domain.fsrs.FsrsScheduler
import com.comp90018.flashcards.domain.model.CardWithState
import com.comp90018.flashcards.domain.model.Rating
import com.comp90018.flashcards.domain.repository.CardRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Clock
import java.util.UUID
import javax.inject.Inject

/**
 * Implementation of CardRepository using Room database.
 */
class CardRepositoryImpl
    @Inject
    constructor(
        private val cardDao: CardDao,
        private val deckDao: DeckDao,
        private val reviewDao: ReviewDao,
        private val scheduler: FsrsScheduler,
        private val clock: Clock,
        private val deckSync: DeckSyncCoordinator,
    ) : CardRepository {
        override fun getAllDecks(ownerId: String): Flow<List<DeckEntity>> = deckDao.getAllDecks(ownerId)

        override suspend fun insertDeck(deck: DeckEntity) {
            deckDao.insertDeck(deck)
            deckSync.deckChanged(deck.deckId, deck.ownerId)
        }

        override suspend fun updateDeck(deck: DeckEntity) {
            deckDao.updateDeck(deck)
            deckSync.deckChanged(deck.deckId, deck.ownerId)
        }

        override suspend fun deleteDeck(deck: DeckEntity) {
            deckDao.deleteDeck(deck)
            deckSync.deckDeleted(deck.deckId, deck.ownerId)
        }

        override suspend fun getDeckById(deckId: String): DeckEntity? = deckDao.getDeckById(deckId)

        override fun getCardsByDeckId(deckId: String): Flow<List<CardEntity>> = cardDao.getCardsByDeckId(deckId)

        override fun getDueCards(
            userId: String,
            deckId: String,
        ): Flow<List<CardWithState>> =
            combine(
                cardDao.getCardsByDeckId(deckId),
                reviewDao.observeStatesForDeck(userId, deckId),
            ) { cards, states ->
                val now = clock.instant()
                val stateByCardId = states.associateBy { it.cardId }
                cards
                    .map { card ->
                        CardWithState(
                            card = card,
                            state = stateByCardId[card.cardId]?.toDomain() ?: FsrsCard.new(now),
                        )
                    }.filter { it.state.isDue(now) }
                    // Cards already being learned come first, most overdue first. New cards follow in deck order.
                    .sortedWith(
                        compareBy<CardWithState> { it.state.state == CardState.NEW }
                            .thenBy { it.state.due }
                            .thenBy { it.card.position },
                    )
            }

        override suspend fun getCardById(cardId: String): CardEntity? = cardDao.getCardById(cardId)

        override suspend fun insertCard(card: CardEntity) {
            cardDao.insertCard(card)
            markDeckChanged(card.deckId)
        }

        override suspend fun updateCard(card: CardEntity) {
            cardDao.updateCard(card)
            markDeckChanged(card.deckId)
        }

        override suspend fun deleteCard(card: CardEntity) {
            cardDao.deleteCardAndReviews(card)
            markDeckChanged(card.deckId)
        }

        private suspend fun markDeckChanged(deckId: String) {
            val ownerId = deckDao.getDeckById(deckId)?.ownerId ?: return
            deckSync.deckChanged(deckId, ownerId)
        }

        override suspend fun reviewCard(
            userId: String,
            cardId: String,
            rating: Rating,
        ): FsrsCard {
            val now = clock.instant()
            val current = reviewDao.getState(userId, cardId)?.toDomain() ?: FsrsCard.new(now)
            val next = scheduler.review(current, rating, now)
            reviewDao.recordReview(
                state = CardFsrsStateEntity.from(userId, cardId, next, updatedAt = now),
                log =
                    ReviewLogEntity(
                        reviewLogId = UUID.randomUUID().toString(),
                        userId = userId,
                        cardId = cardId,
                        rating = rating,
                        reviewedAt = now,
                    ),
            )
            return next
        }
    }
