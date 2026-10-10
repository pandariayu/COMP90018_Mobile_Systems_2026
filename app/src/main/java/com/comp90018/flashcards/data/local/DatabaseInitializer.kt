package com.comp90018.flashcards.data.local

import com.comp90018.flashcards.data.local.dao.CardDao
import com.comp90018.flashcards.data.local.dao.DeckDao
import com.comp90018.flashcards.data.local.entity.CardEntity
import com.comp90018.flashcards.data.local.entity.DeckEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inserts sample decks the first time a signed-in user has no decks.
 */
@Singleton
class DatabaseInitializer
    @Inject
    constructor(
        private val cardDao: CardDao,
        private val deckDao: DeckDao,
    ) {
        private val seedMutex = Mutex()

        suspend fun seedForUser(ownerId: String) {
            seedMutex.withLock {
                if (deckDao.countDecksForOwner(ownerId) > 0) {
                    return
                }

                // 1. Sample Deck
                val sampleDeckId = UUID.randomUUID().toString()
                deckDao.insertDeck(
                    DeckEntity(
                        deckId = sampleDeckId,
                        name = "Sample Deck",
                        description = "A sample deck to get you started with flashcards.",
                        ownerId = ownerId,
                    ),
                )
                val sampleCards =
                    listOf(
                        CardEntity(
                            cardId = UUID.randomUUID().toString(),
                            deckId = sampleDeckId,
                            front = "What is Android?",
                            back = "A mobile operating system.",
                        ),
                        CardEntity(
                            cardId = UUID.randomUUID().toString(),
                            deckId = sampleDeckId,
                            front = "What is Kotlin?",
                            back = "A modern programming language.",
                        ),
                    )
                sampleCards.forEach { card -> cardDao.insertCard(card) }

                // 2. Alphabet Deck (26 cards)
                val alphabetDeckId = UUID.randomUUID().toString()
                deckDao.insertDeck(
                    DeckEntity(
                        deckId = alphabetDeckId,
                        name = "Alphabet",
                        description = "26 letters of the alphabet",
                        ownerId = ownerId,
                    ),
                )
                val ordinals = listOf(
                    "1st", "2nd", "3rd", "4th", "5th", "6th", "7th", "8th", "9th", "10th",
                    "11th", "12th", "13th", "14th", "15th", "16th", "17th", "18th", "19th", "20th",
                    "21st", "22nd", "23rd", "24th", "25th", "26th",
                )
                val alphabetCards =
                    (1..26).map { i ->
                        val letter = ('A' + (i - 1)).toString()
                        CardEntity(
                            cardId = UUID.randomUUID().toString(),
                            deckId = alphabetDeckId,
                            front = "${ordinals[i - 1]} letter of the alphabet",
                            back = letter,
                        )
                    }
                alphabetCards.forEach { card -> cardDao.insertCard(card) }
            }
        }
    }
