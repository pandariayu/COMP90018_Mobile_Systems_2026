package com.comp90018.flashcards.data.remote

import com.comp90018.flashcards.data.local.entity.CardEntity
import com.comp90018.flashcards.data.local.entity.DeckEntity

/**
 * A saved copy has new ids, belongs to the person who saved it, and does not include study progress.
 * Later edits to the source deck do not change the copy.
 */
internal fun copiedDeck(
    source: RemoteDeck,
    cards: List<RemoteCard>,
    ownerId: String,
    newDeckId: String,
    newCardIds: List<String>,
): Pair<DeckEntity, List<CardEntity>> {
    val ordered = cards.sortedWith(compareBy(RemoteCard::position, RemoteCard::cardId))
    require(newCardIds.size == ordered.size) { "A saved copy needs one new id per card." }
    val deck =
        DeckEntity(
            deckId = newDeckId,
            name = source.name,
            ownerId = ownerId,
            sourceDeckId = source.deckId,
            copiedFromName = source.name,
        )
    val localCards =
        ordered.zip(newCardIds) { card, cardId ->
            CardEntity(
                cardId = cardId,
                deckId = newDeckId,
                front = card.front,
                back = card.back,
                position = card.position,
                frontImageUri = card.frontImageUri,
                backImageUri = card.backImageUri,
            )
        }
    return deck to localCards
}
