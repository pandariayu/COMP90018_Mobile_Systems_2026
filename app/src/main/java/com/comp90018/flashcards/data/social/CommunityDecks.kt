package com.comp90018.flashcards.data.social

import com.comp90018.flashcards.data.local.dao.DeckDao
import com.comp90018.flashcards.data.remote.DeckVisibility
import com.comp90018.flashcards.data.remote.RemoteDeck
import com.comp90018.flashcards.data.sync.DeckSyncCoordinator
import com.comp90018.flashcards.data.sync.DeckSyncOutcome
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import javax.inject.Inject

class CommunityDecks
    @Inject
    constructor(
        private val api: SocialApi,
        private val local: DeckDao,
        private val sync: DeckSyncCoordinator,
        private val session: SocialSession,
    ) {
        suspend fun catalogue(uid: String): List<RemoteDeck> {
            session.requireUser(uid)
            val decks: List<SocialDeck> = socialJson.decodeFromString(api.request(uid, "GET", "/v1/decks/public"))
            session.requireUser(uid)
            return decks.map { deck ->
                RemoteDeck(
                    deckId = deck.deckId,
                    name = deck.name,
                    ownerId = deck.ownerId,
                    visibility = DeckVisibility.fromFirestore(deck.visibility),
                    updatedAt = deck.updatedAt?.let(Instant::parse) ?: Instant.EPOCH,
                    cardCount = deck.cardCount,
                    createdAt = deck.createdAt?.let(Instant::parse),
                    ownerDisplayName = deck.ownerDisplayName,
                )
            }
        }

        suspend fun prepare(
            uid: String,
            deckId: String,
        ): SocialDeck {
            session.requireUser(uid)
            val deck = local.getDeckById(deckId)
            if (deck?.ownerId == uid) {
                sync.deckChanged(deckId, uid)
                check(sync.syncNow(uid) == DeckSyncOutcome.UP_TO_DATE) {
                    "Your deck is still syncing. Check your connection and try again."
                }
            }
            return get(uid, deckId)
        }

        suspend fun get(
            uid: String,
            deckId: String,
        ): SocialDeck = socialJson.decodeFromString(api.request(uid, "GET", "/v1/decks/$deckId"))

        suspend fun cards(
            uid: String,
            deckId: String,
            offset: Int,
        ): List<SocialCard> =
            socialJson.decodeFromString(api.request(uid, "GET", "/v1/decks/$deckId/cards?limit=50&offset=$offset"))

        suspend fun publish(
            uid: String,
            deckId: String,
            public: Boolean,
        ): SocialDeck {
            if (public) prepare(uid, deckId)
            return socialJson.decodeFromString(
                api.request(uid, if (public) "POST" else "DELETE", "/v1/decks/$deckId/publish"),
            )
        }

        suspend fun share(
            uid: String,
            deckId: String,
            recipientId: String,
        ) {
            prepare(uid, deckId)
            api.request(uid, "POST", "/v1/decks/$deckId/shares", buildJsonObject { put("recipient_id", recipientId) })
        }

        suspend fun revoke(
            uid: String,
            deckId: String,
            recipientId: String,
        ) {
            api.request(uid, "DELETE", "/v1/decks/$deckId/shares/$recipientId")
        }
    }
