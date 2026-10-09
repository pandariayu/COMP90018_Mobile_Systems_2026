package com.comp90018.flashcards.data.sync

import com.comp90018.flashcards.data.local.dao.CardDao
import com.comp90018.flashcards.data.local.dao.DeckDao
import com.comp90018.flashcards.data.local.dao.DeckSyncOutboxDao
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeckSyncStore
    @Inject
    constructor(
        val deckDao: DeckDao,
        val cardDao: CardDao,
        val outboxDao: DeckSyncOutboxDao,
    )
