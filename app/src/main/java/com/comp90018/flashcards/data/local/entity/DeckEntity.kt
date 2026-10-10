package com.comp90018.flashcards.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "decks")
data class DeckEntity(
    @PrimaryKey val deckId: String,
    val name: String,
    val description: String = "",
    val ownerId: String,
)
