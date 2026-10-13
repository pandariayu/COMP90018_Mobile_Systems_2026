package com.comp90018.flashcards.ui.deck

import com.comp90018.flashcards.data.remote.RemoteDeck

/**
 * Public catalogue search. A blank query keeps every deck. Otherwise the deck name or creator
 * display name must contain the text, ignoring case.
 */
internal fun List<RemoteDeck>.matchingPublicSearch(query: String): List<RemoteDeck> {
    val needle = query.trim()
    return if (needle.isEmpty()) {
        this
    } else {
        filter { deck ->
            deck.name.contains(needle, ignoreCase = true) ||
                deck.ownerDisplayName.orEmpty().contains(needle, ignoreCase = true)
        }
    }
}
