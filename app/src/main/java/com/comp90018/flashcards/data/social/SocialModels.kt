package com.comp90018.flashcards.data.social

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SocialProfile(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
)

@Serializable
data class SocialFriend(
    val user: SocialProfile,
)

@Serializable
data class SocialRequest(
    val id: String,
    val sender: SocialProfile,
    val recipient: SocialProfile,
    val status: String,
)

@Serializable
data class SocialDeck(
    val deckId: String,
    val name: String,
    val ownerId: String,
    val visibility: String,
    val cardCount: Int,
    val copiedFrom: DeckAttribution? = null,
    val ownerDisplayName: String? = null,
    val updatedAt: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class DeckAttribution(
    val deckId: String,
    val ownerId: String,
    val copiedAt: String,
)

@Serializable
data class SocialCard(
    val cardId: String,
    val front: String,
    val back: String,
    val position: Int,
    val frontImageUri: String? = null,
    val backImageUri: String? = null,
)
