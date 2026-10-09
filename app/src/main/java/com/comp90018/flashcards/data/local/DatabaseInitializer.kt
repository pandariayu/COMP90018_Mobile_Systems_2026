package com.comp90018.flashcards.data.local

import com.comp90018.flashcards.data.local.dao.CardDao
import com.comp90018.flashcards.data.local.dao.DeckDao
import com.comp90018.flashcards.data.local.entity.CardEntity
import com.comp90018.flashcards.data.local.entity.DeckEntity
import com.comp90018.flashcards.data.sync.DeckSyncCoordinator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inserts a few short quizzes the first time this account is missing them, then queues a cloud sync.
 */
@Singleton
class DatabaseInitializer
    @Inject
    constructor(
        private val cardDao: CardDao,
        private val deckDao: DeckDao,
        private val deckSync: DeckSyncCoordinator,
    ) {
        private val seedMutex = Mutex()

        suspend fun seedForUser(ownerId: String) {
            seedMutex.withLock {
                SEED_DECKS.forEach { spec -> ensureDeck(ownerId, spec) }
            }
        }

        private suspend fun ensureDeck(
            ownerId: String,
            spec: SeedDeck,
        ) {
            val deckId = "seed-$ownerId-${spec.slug}"
            if (deckDao.getDeckById(deckId) != null) {
                return
            }
            deckDao.insertDeck(DeckEntity(deckId = deckId, name = spec.name, ownerId = ownerId))
            spec.cards.forEachIndexed { index, card ->
                cardDao.insertCard(
                    CardEntity(
                        cardId = "$deckId-$index",
                        deckId = deckId,
                        front = card.front,
                        back = card.back,
                        position = index,
                    ),
                )
            }
            deckSync.deckChanged(deckId, ownerId)
        }

        private data class SeedCard(
            val front: String,
            val back: String,
        )

        private data class SeedDeck(
            val slug: String,
            val name: String,
            val cards: List<SeedCard>,
        )

        private companion object {
            val SEED_DECKS =
                listOf(
                    SeedDeck(
                        slug = "kotlin",
                        name = "Kotlin quiz",
                        cards =
                            listOf(
                                SeedCard("What does val mean?", "A read-only variable."),
                                SeedCard("What is a data class?", "A class that mainly holds data."),
                                SeedCard("Which function starts a coroutine?", "launch or async."),
                            ),
                    ),
                    SeedDeck(
                        slug = "android",
                        name = "Android quiz",
                        cards =
                            listOf(
                                SeedCard("What is Room?", "A library for a local SQLite database."),
                                SeedCard("What is an Activity?", "A screen the user can interact with."),
                                SeedCard("What does offline-first mean?", "Usable offline, then syncs later."),
                            ),
                    ),
                    SeedDeck(
                        slug = "capitals",
                        name = "Capitals quiz",
                        cards =
                            listOf(
                                SeedCard("Capital of Australia?", "Canberra."),
                                SeedCard("Capital of Japan?", "Tokyo."),
                                SeedCard("Capital of Canada?", "Ottawa."),
                            ),
                    ),
                )
        }
    }
