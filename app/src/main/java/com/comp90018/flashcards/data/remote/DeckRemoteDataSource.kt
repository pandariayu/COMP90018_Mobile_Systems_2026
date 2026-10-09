package com.comp90018.flashcards.data.remote

/**
 * Firestore access for cloud decks. Local Room stays the source of truth; the sync coordinator
 * pushes changes when the device is online. Study progress is not stored here.
 */
interface DeckRemoteDataSource {
    suspend fun upsertDeck(deck: RemoteDeck): Result<Unit>

    suspend fun getDeck(
        deckId: String,
        fromServer: Boolean = false,
    ): Result<RemoteDeck?>

    suspend fun listDecksForOwner(ownerId: String): Result<List<RemoteDeck>>

    suspend fun listPublicDecks(): Result<List<RemoteDeck>>

    suspend fun deleteDeck(deckId: String): Result<Unit>

    suspend fun upsertCard(card: RemoteCard): Result<Unit>

    suspend fun listCards(
        deckId: String,
        fromServer: Boolean = false,
    ): Result<List<RemoteCard>>

    suspend fun deleteCard(
        deckId: String,
        cardId: String,
    ): Result<Unit>

    /**
     * Creates or updates a deck and makes its cards match [cards].
     *
     * [create] writes visibility. An update leaves visibility untouched so a published deck stays
     * public. Cards in [deleteCardIds] are removed.
     */
    suspend fun pushDeck(
        deck: RemoteDeck,
        cards: List<RemoteCard>,
        deleteCardIds: List<String>,
        create: Boolean,
    ): Result<Unit>
}
