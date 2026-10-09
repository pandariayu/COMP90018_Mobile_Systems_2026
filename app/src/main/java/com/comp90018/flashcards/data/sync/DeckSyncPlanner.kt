package com.comp90018.flashcards.data.sync

import com.comp90018.flashcards.data.local.entity.CardEntity
import com.comp90018.flashcards.data.local.entity.DeckEntity
import com.comp90018.flashcards.data.remote.DeckVisibility
import com.comp90018.flashcards.data.remote.RemoteCard
import com.comp90018.flashcards.data.remote.RemoteDeck
import java.time.Instant

internal sealed interface DeckSyncPlan {
    data object Unchanged : DeckSyncPlan

    data object Foreign : DeckSyncPlan

    data class Push(
        val deck: RemoteDeck,
        val cards: List<RemoteCard>,
        val deleteCardIds: List<String>,
        val create: Boolean,
    ) : DeckSyncPlan
}

/**
 * Local Room wins for the owner's deck content. An existing cloud visibility is kept so a
 * later publish is not reset to private. Study progress is not part of the plan.
 */
internal object DeckSyncPlanner {
    fun plan(
        localDeck: DeckEntity,
        localCards: List<CardEntity>,
        remoteDeck: RemoteDeck?,
        remoteCards: List<RemoteCard>,
        now: Instant,
    ): DeckSyncPlan {
        val cards =
            localCards
                .sortedWith(compareBy(CardEntity::position, CardEntity::cardId))
                .map { it.toRemote() }
        return when {
            remoteDeck != null && remoteDeck.ownerId != localDeck.ownerId -> DeckSyncPlan.Foreign
            remoteDeck != null && sameContent(localDeck, cards, remoteDeck, remoteCards) ->
                DeckSyncPlan.Unchanged
            else ->
                DeckSyncPlan.Push(
                    deck =
                        RemoteDeck(
                            deckId = localDeck.deckId,
                            name = localDeck.name,
                            ownerId = localDeck.ownerId,
                            visibility = remoteDeck?.visibility ?: DeckVisibility.PRIVATE,
                            updatedAt = now,
                            cardCount = cards.size,
                        ),
                    cards = cards,
                    deleteCardIds = staleCardIds(remoteCards, cards),
                    create = remoteDeck == null,
                )
        }
    }

    private fun staleCardIds(
        remoteCards: List<RemoteCard>,
        localCards: List<RemoteCard>,
    ): List<String> {
        val localIds = localCards.map { it.cardId }.toSet()
        return remoteCards.map { it.cardId }.filterNot { it in localIds }.sorted()
    }

    private fun sameContent(
        localDeck: DeckEntity,
        localCards: List<RemoteCard>,
        remoteDeck: RemoteDeck,
        remoteCards: List<RemoteCard>,
    ): Boolean {
        val remoteById = remoteCards.associateBy { it.cardId }
        val namesMatch = localDeck.name == remoteDeck.name
        val countsMatch = localCards.size == remoteDeck.cardCount && remoteById.size == localCards.size
        return namesMatch && countsMatch && localCards.all { card -> remoteById[card.cardId] == card }
    }

    private fun CardEntity.toRemote(): RemoteCard =
        RemoteCard(
            cardId = cardId,
            deckId = deckId,
            front = front,
            back = back,
            position = position,
            frontImageUri = frontImageUri,
            backImageUri = backImageUri,
        )
}
