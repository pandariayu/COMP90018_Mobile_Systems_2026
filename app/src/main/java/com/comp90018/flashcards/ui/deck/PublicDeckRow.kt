package com.comp90018.flashcards.ui.deck

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comp90018.flashcards.data.remote.RemoteDeck

// Adapted from Jason Ren's PublicDeckScreen in PR #64.
@Composable
internal fun PublicDeckRow(
    deck: RemoteDeck,
    ownedByMe: Boolean,
    onOpenDeck: (String) -> Unit, // opens the deck page
    modifier: Modifier = Modifier,
) {
    val owner =
        listOfNotNull(
            deck.ownerDisplayName?.takeIf { it.isNotBlank() },
            "Yours".takeIf { ownedByMe },
        ).joinToString(" · ").ifBlank { "Public" }

    Card(
        onClick = { onOpenDeck(deck.deckId) },
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                deck.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${deck.cardCount} cards · $owner",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
