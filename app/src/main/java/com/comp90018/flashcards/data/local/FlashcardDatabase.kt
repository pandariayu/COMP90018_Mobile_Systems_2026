package com.comp90018.flashcards.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.comp90018.flashcards.data.local.dao.CardDao
import com.comp90018.flashcards.data.local.dao.DeckDao
import com.comp90018.flashcards.data.local.dao.ReviewDao
import com.comp90018.flashcards.data.local.entity.CardEntity
import com.comp90018.flashcards.data.local.entity.CardFsrsStateEntity
import com.comp90018.flashcards.data.local.entity.DeckEntity
import com.comp90018.flashcards.data.local.entity.ReviewLogEntity

@Database(
    entities = [CardEntity::class, DeckEntity::class, CardFsrsStateEntity::class, ReviewLogEntity::class],
    version = 5,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class FlashcardDatabase : RoomDatabase() {
    abstract fun cardDao(): CardDao

    abstract fun deckDao(): DeckDao

    abstract fun reviewDao(): ReviewDao
}
