package com.comp90018.flashcards.data.sync

import com.comp90018.flashcards.data.local.entity.CardEntity
import com.comp90018.flashcards.data.local.entity.DeckEntity
import com.comp90018.flashcards.data.remote.DeckRemoteDataSource
import com.comp90018.flashcards.data.remote.DeckVisibility
import com.comp90018.flashcards.data.remote.RemoteCard
import com.comp90018.flashcards.data.remote.RemoteDeck
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DeckSyncTest {
    private val now = Instant.parse("2026-10-09T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `first push creates a private deck in card order`() {
        val plan =
            DeckSyncPlanner.plan(
                deck(),
                listOf(card(position = 1), card(cardId = "b", position = 0)),
                null,
                emptyList(),
                now,
            )

        val push = plan as DeckSyncPlan.Push
        assertTrue(push.create)
        assertEquals(DeckVisibility.PRIVATE, push.deck.visibility)
        assertEquals("uid-1", push.deck.ownerId)
        assertEquals(2, push.deck.cardCount)
        assertEquals(now, push.deck.updatedAt)
        assertEquals(listOf("b", "card-1"), push.cards.map { it.cardId })
        assertTrue(push.deleteCardIds.isEmpty())
    }

    @Test
    fun `later push keeps public visibility and deletes removed cards`() {
        val remote = remoteDeck(visibility = DeckVisibility.PUBLIC, cardCount = 2)
        val remoteCards = listOf(remoteCard("card-1"), remoteCard("gone"))
        val plan = DeckSyncPlanner.plan(deck(name = "Renamed"), listOf(card()), remote, remoteCards, now)

        val push = plan as DeckSyncPlan.Push
        assertFalse(push.create)
        assertEquals(DeckVisibility.PUBLIC, push.deck.visibility)
        assertEquals("Renamed", push.deck.name)
        assertEquals(listOf("gone"), push.deleteCardIds)
    }

    @Test
    fun `unchanged public deck is not written again`() {
        val localCard = card()
        val plan =
            DeckSyncPlanner.plan(
                deck(),
                listOf(localCard),
                remoteDeck(cardCount = 1),
                listOf(remoteCard("card-1")),
                now,
            )

        assertEquals(DeckSyncPlan.Unchanged, plan)
    }

    @Test
    fun `another account owns the cloud deck`() {
        val plan =
            DeckSyncPlanner.plan(
                deck(),
                listOf(card()),
                remoteDeck(ownerId = "someone-else", cardCount = 1),
                listOf(remoteCard("card-1")),
                now,
            )

        assertEquals(DeckSyncPlan.Foreign, plan)
    }

    @Test
    fun `execute pushes a new deck and skips an identical one`() =
        runBlocking {
            val remote = FakeRemote()
            val sync = DeckCloudSync(remote, clock)
            val local = deck()
            val cards = listOf(card())

            assertEquals(DeckSyncStep.Synced, sync.execute(DeckSyncOperation.UPSERT, local.deckId, local, cards))
            assertEquals(1, remote.pushes.size)
            val created = remote.pushes.single()
            assertTrue(created.create)
            assertEquals(DeckVisibility.PRIVATE, created.deck.visibility)

            remote.deck = created.deck
            remote.cards = created.cards
            remote.pushes.clear()

            assertEquals(DeckSyncStep.Synced, sync.execute(DeckSyncOperation.UPSERT, local.deckId, local, cards))
            assertTrue(remote.pushes.isEmpty())
        }

    @Test
    fun `execute preserves visibility and deletes a removed card`() =
        runBlocking {
            val remote = FakeRemote()
            remote.deck = remoteDeck(visibility = DeckVisibility.PUBLIC, cardCount = 2)
            remote.cards = listOf(remoteCard("card-1"), remoteCard("gone"))
            val sync = DeckCloudSync(remote, clock)

            val step = sync.execute(DeckSyncOperation.UPSERT, "deck-1", deck(name = "Renamed"), listOf(card()))

            assertEquals(DeckSyncStep.Synced, step)
            val push = remote.pushes.single()
            assertFalse(push.create)
            assertEquals(DeckVisibility.PUBLIC, push.deck.visibility)
            assertEquals(listOf("gone"), push.deleteCardIds)
        }

    @Test
    fun `a missing cloud deck is created when the read is denied`() =
        runBlocking {
            val remote = FakeRemote()
            remote.getDeckFailure = CloudDeckHiddenException()
            val sync = DeckCloudSync(remote, clock)

            val step = sync.execute(DeckSyncOperation.UPSERT, "deck-1", deck(), listOf(card()))

            assertEquals(DeckSyncStep.Synced, step)
            val push = remote.pushes.single()
            assertTrue(push.create)
            assertEquals(DeckVisibility.PRIVATE, push.deck.visibility)
            assertTrue(CloudDeckHiddenException().isUnreadableCloudDeck())
        }

    @Test
    fun `execute deletes the cloud deck when the local deck is gone`() =
        runBlocking {
            val remote = FakeRemote()
            val sync = DeckCloudSync(remote, clock)

            assertEquals(
                DeckSyncStep.Synced,
                sync.execute(DeckSyncOperation.DELETE, "deck-1", deck(), listOf(card())),
            )
            assertEquals("deck-1", remote.deletedDeckId)
            assertTrue(remote.pushes.isEmpty())
        }

    @Test
    fun `missing firebase configuration is deferred and other failures retry`() {
        val notConfigured = IllegalStateException("Firestore is not configured.")
        assertEquals(DeckSyncStep.Deferred, syncFailureStep(notConfigured))
        assertEquals(DeckSyncStep.Retry, syncFailureStep(IllegalStateException("offline")))
        assertTrue(isPermanentFirestoreCode("PERMISSION_DENIED"))
        assertTrue(isPermanentFirestoreCode("INVALID_ARGUMENT"))
        assertFalse(isPermanentFirestoreCode("UNAVAILABLE"))
        assertFalse(isPermanentFirestoreCode("UNAUTHENTICATED"))
    }

    private fun deck(
        name: String = "Biology",
        ownerId: String = "uid-1",
    ) = DeckEntity(deckId = "deck-1", name = name, ownerId = ownerId)

    private fun card(
        cardId: String = "card-1",
        position: Int = 0,
    ) = CardEntity(cardId = cardId, deckId = "deck-1", front = "Q", back = "A", position = position)

    private fun remoteDeck(
        ownerId: String = "uid-1",
        visibility: DeckVisibility = DeckVisibility.PUBLIC,
        cardCount: Int = 1,
    ) = RemoteDeck(
        deckId = "deck-1",
        name = "Biology",
        ownerId = ownerId,
        visibility = visibility,
        updatedAt = Instant.parse("2026-10-01T00:00:00Z"),
        cardCount = cardCount,
    )

    private fun remoteCard(cardId: String) =
        RemoteCard(cardId = cardId, deckId = "deck-1", front = "Q", back = "A", position = 0)

    private class FakeRemote : DeckRemoteDataSource {
        var deck: RemoteDeck? = null
        var cards: List<RemoteCard> = emptyList()
        var getDeckFailure: Throwable? = null
        val pushes = mutableListOf<DeckSyncPlan.Push>()
        var deletedDeckId: String? = null

        override suspend fun upsertDeck(deck: RemoteDeck): Result<Unit> = unused()

        override suspend fun getDeck(
            deckId: String,
            fromServer: Boolean,
        ): Result<RemoteDeck?> {
            assertTrue(fromServer)
            getDeckFailure?.let { return Result.failure(it) }
            return Result.success(deck?.takeIf { it.deckId == deckId })
        }

        override suspend fun listDecksForOwner(ownerId: String): Result<List<RemoteDeck>> = unused()

        override suspend fun listPublicDecks(): Result<List<RemoteDeck>> = unused()

        override suspend fun deleteDeck(deckId: String): Result<Unit> {
            deletedDeckId = deckId
            return Result.success(Unit)
        }

        override suspend fun upsertCard(card: RemoteCard): Result<Unit> = unused()

        override suspend fun listCards(
            deckId: String,
            fromServer: Boolean,
        ): Result<List<RemoteCard>> {
            assertTrue(fromServer)
            return Result.success(cards)
        }

        override suspend fun deleteCard(
            deckId: String,
            cardId: String,
        ): Result<Unit> = unused()

        override suspend fun pushDeck(
            deck: RemoteDeck,
            cards: List<RemoteCard>,
            deleteCardIds: List<String>,
            create: Boolean,
        ): Result<Unit> {
            pushes.add(DeckSyncPlan.Push(deck, cards, deleteCardIds, create))
            return Result.success(Unit)
        }

        private fun <T> unused(): Result<T> = Result.failure(IllegalStateException("unused"))
    }
}
