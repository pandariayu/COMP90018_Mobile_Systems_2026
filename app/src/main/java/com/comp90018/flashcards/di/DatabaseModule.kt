package com.comp90018.flashcards.di

import android.content.Context
import androidx.room.Room
import com.comp90018.flashcards.data.local.DeckSyncDatabase
import com.comp90018.flashcards.data.local.FlashcardDatabase
import com.comp90018.flashcards.data.local.dao.CardDao
import com.comp90018.flashcards.data.local.dao.DeckDao
import com.comp90018.flashcards.data.local.dao.DeckSyncOutboxDao
import com.comp90018.flashcards.data.local.dao.ReviewDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): FlashcardDatabase =
        Room
            .databaseBuilder(
                context,
                FlashcardDatabase::class.java,
                "flashcard_db",
            ).fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideCardDao(database: FlashcardDatabase): CardDao = database.cardDao()

    @Provides
    fun provideDeckDao(database: FlashcardDatabase): DeckDao = database.deckDao()

    @Provides
    fun provideReviewDao(database: FlashcardDatabase): ReviewDao = database.reviewDao()

    @Provides
    @Singleton
    fun provideSyncDatabase(
        @ApplicationContext context: Context,
    ): DeckSyncDatabase =
        Room
            .databaseBuilder(
                context,
                DeckSyncDatabase::class.java,
                "flashcard_sync_db",
            ).build()

    @Provides
    fun provideDeckSyncOutboxDao(database: DeckSyncDatabase): DeckSyncOutboxDao = database.outboxDao()
}
