package com.comp90018.flashcards.data.remote

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firestore implementation of [DeckRemoteDataSource].
 *
 * Returns a clear failure when Firebase is not configured (missing `google-services.json`).
 */
@Singleton
class FirestoreDeckRemoteDataSource
    @Inject
    constructor(
        private val firestore: FirebaseFirestore?,
    ) : DeckRemoteDataSource {
        override suspend fun upsertDeck(deck: RemoteDeck): Result<Unit> =
            runRemote {
                decks()
                    .document(deck.deckId)
                    .set(deck.toFirestoreMap(), SetOptions.merge())
                    .await()
            }

        override suspend fun getDeck(
            deckId: String,
            fromServer: Boolean,
        ): Result<RemoteDeck?> =
            runRemote {
                decks()
                    .document(deckId)
                    .get(if (fromServer) Source.SERVER else Source.DEFAULT)
                    .await()
                    .takeIf { it.exists() }
                    ?.let { RemoteDeckMapper.from(it.id, it.data.orEmpty()) }
            }

        override suspend fun listDecksForOwner(ownerId: String): Result<List<RemoteDeck>> =
            runRemote {
                decks()
                    .whereEqualTo(FIELD_OWNER_ID, ownerId)
                    .orderBy(FIELD_UPDATED_AT, Query.Direction.DESCENDING)
                    .get()
                    .await()
                    .documents
                    .map { RemoteDeckMapper.from(it.id, it.data.orEmpty()) }
            }

        override suspend fun listPublicDecks(): Result<List<RemoteDeck>> =
            runRemote {
                decks()
                    .whereEqualTo(FIELD_VISIBILITY, DeckVisibility.PUBLIC.firestoreValue)
                    .orderBy(FIELD_UPDATED_AT, Query.Direction.DESCENDING)
                    .get()
                    .await()
                    .documents
                    .map { RemoteDeckMapper.from(it.id, it.data.orEmpty()) }
            }

        override suspend fun deleteDeck(deckId: String): Result<Unit> =
            runRemote {
                val cardRefs =
                    cards(deckId)
                        .get()
                        .await()
                        .documents
                        .map { it.reference }
                cardRefs.chunked(BATCH_WRITE_LIMIT).forEach { chunk ->
                    val batch = requireFirestore().batch()
                    chunk.forEach { batch.delete(it) }
                    batch.commit().await()
                }
                decks().document(deckId).delete().await()
                awaitServer()
            }

        override suspend fun upsertCard(card: RemoteCard): Result<Unit> =
            runRemote {
                cards(card.deckId)
                    .document(card.cardId)
                    .set(card.toFirestoreMap(), SetOptions.merge())
                    .await()
            }

        override suspend fun listCards(
            deckId: String,
            fromServer: Boolean,
        ): Result<List<RemoteCard>> =
            runRemote {
                cards(deckId)
                    .orderBy(FIELD_POSITION)
                    .get(if (fromServer) Source.SERVER else Source.DEFAULT)
                    .await()
                    .documents
                    .map { RemoteCardMapper.from(deckId, it.id, it.data.orEmpty()) }
            }

        override suspend fun pushDeck(
            deck: RemoteDeck,
            cards: List<RemoteCard>,
            deleteCardIds: List<String>,
            create: Boolean,
        ): Result<Unit> =
            runRemote {
                val deckRef = decks().document(deck.deckId)
                if (create) {
                    deckRef.set(deck.toFirestoreMap()).await()
                    // Card rules read the parent deck, so the deck must be on the server first.
                    awaitServer()
                } else {
                    deckRef.set(deck.toContentUpdateMap(), SetOptions.merge()).await()
                }
                val cardCollection = cards(deck.deckId)
                val writes =
                    ArrayList<Pair<String, Map<String, Any?>?>>(cards.size + deleteCardIds.size)
                cards.forEach { card -> writes.add(card.cardId to card.toFirestoreMap()) }
                deleteCardIds.forEach { cardId -> writes.add(cardId to null) }
                writes.chunked(BATCH_WRITE_LIMIT).forEach { chunk ->
                    val batch = requireFirestore().batch()
                    chunk.forEach { (cardId, payload) ->
                        val ref = cardCollection.document(cardId)
                        if (payload == null) {
                            batch.delete(ref)
                        } else {
                            batch.set(ref, payload)
                        }
                    }
                    batch.commit().await()
                }
                awaitServer()
            }

        override suspend fun deleteCard(
            deckId: String,
            cardId: String,
        ): Result<Unit> =
            runRemote {
                cards(deckId).document(cardId).delete().await()
            }

        private fun decks() = requireFirestore().collection(COLLECTION_DECKS)

        private fun cards(deckId: String) = decks().document(deckId).collection(COLLECTION_CARDS)

        private fun requireFirestore(): FirebaseFirestore = firestore ?: error(NOT_CONFIGURED_MESSAGE)

        private suspend fun awaitServer() {
            try {
                withTimeout(SERVER_ACK_TIMEOUT_MS) {
                    requireFirestore().waitForPendingWrites().await()
                }
            } catch (timeout: TimeoutCancellationException) {
                throw IllegalStateException("Cloud sync was not acknowledged.", timeout)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun <T> runRemote(block: suspend () -> T): Result<T> =
            try {
                Result.success(block())
            } catch (timeout: TimeoutCancellationException) {
                Result.failure(timeout)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Firestore call failed", error)
                Result.failure(error)
            }

        private companion object {
            const val TAG = "DeckSync"
            const val COLLECTION_DECKS = "decks"
            const val COLLECTION_CARDS = "cards"
            const val FIELD_OWNER_ID = "ownerId"
            const val FIELD_VISIBILITY = "visibility"
            const val FIELD_UPDATED_AT = "updatedAt"
            const val FIELD_POSITION = "position"
            const val NOT_CONFIGURED_MESSAGE =
                "Firestore is not configured. Add app/google-services.json and enable Firestore."
            const val BATCH_WRITE_LIMIT = 450
            const val SERVER_ACK_TIMEOUT_MS = 20_000L
        }
    }

internal object RemoteDeckMapper {
    fun from(
        deckId: String,
        data: Map<String, Any?>,
    ): RemoteDeck =
        RemoteDeck(
            deckId = deckId,
            name = data["name"] as? String ?: "",
            ownerId = data["ownerId"] as? String ?: "",
            visibility = DeckVisibility.fromFirestore(data["visibility"] as? String),
            updatedAt = (data["updatedAt"] as? Timestamp)?.toDate()?.toInstant() ?: Instant.EPOCH,
            cardCount = (data["cardCount"] as? Number)?.toInt() ?: 0,
        )
}

internal object RemoteCardMapper {
    fun from(
        deckId: String,
        cardId: String,
        data: Map<String, Any?>,
    ): RemoteCard =
        RemoteCard(
            cardId = cardId,
            deckId = deckId,
            front = data["front"] as? String ?: "",
            back = data["back"] as? String ?: "",
            position = (data["position"] as? Number)?.toInt() ?: 0,
            frontImageUri = data["frontImageUri"] as? String,
            backImageUri = data["backImageUri"] as? String,
        )
}

internal fun RemoteDeck.toFirestoreMap(): Map<String, Any?> =
    mapOf(
        "name" to name,
        "ownerId" to ownerId,
        "visibility" to visibility.firestoreValue,
        "updatedAt" to Timestamp(updatedAt.epochSecond, updatedAt.nano),
        "cardCount" to cardCount,
    )

internal fun RemoteDeck.toContentUpdateMap(): Map<String, Any?> =
    mapOf(
        "name" to name,
        "updatedAt" to Timestamp(updatedAt.epochSecond, updatedAt.nano),
        "cardCount" to cardCount,
    )

internal fun RemoteCard.toFirestoreMap(): Map<String, Any?> =
    buildMap {
        put("front", front)
        put("back", back)
        put("position", position)
        frontImageUri?.let { put("frontImageUri", it) }
        backImageUri?.let { put("backImageUri", it) }
    }
