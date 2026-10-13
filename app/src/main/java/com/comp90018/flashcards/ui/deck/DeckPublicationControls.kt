package com.comp90018.flashcards.ui.deck

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.comp90018.flashcards.R
import com.comp90018.flashcards.ui.social.PublishConfirmation
import com.comp90018.flashcards.ui.social.SocialStatus

// Jason Ren's publish/unpublish menu from PR #64, backed by CommunityDecks rather than direct writes.
@Composable
internal fun PublishMenuItem(
    published: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownMenuItem(
        modifier = modifier,
        text = { Text(if (published) "Make Deck Private" else "Make Deck Public") },
        leadingIcon = {
            if (published) {
                Icon(Icons.Default.Lock, contentDescription = null)
            } else {
                Icon(painterResource(R.drawable.ic_globe), contentDescription = null)
            }
        },
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
internal fun DeckPublicationMenu(
    onDismissMenu: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DeckPublicationViewModel = hiltViewModel(),
) {
    val deck by viewModel.deck.collectAsStateWithLifecycle()
    val task by viewModel.taskState.collectAsStateWithLifecycle()
    PublishMenuItem(
        published = deck?.visibility == "public",
        enabled = deck != null && !task.busy,
        onClick = {
            viewModel.requestConfirmation()
            onDismissMenu()
        },
        modifier = modifier,
    )
}

@Composable
internal fun DeckPublicationStatus(
    onOpenSource: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DeckPublicationViewModel = hiltViewModel(),
) {
    val deck by viewModel.deck.collectAsStateWithLifecycle()
    val task by viewModel.taskState.collectAsStateWithLifecycle()
    val confirmation by viewModel.confirmation.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refresh() }
    Column(modifier) {
        deck?.let {
            Text(if (it.visibility == "public") "Public" else "Private", style = MaterialTheme.typography.labelLarge)
            it.copiedFrom?.let { source ->
                TextButton(onClick = { onOpenSource(source.deckId) }) { Text("View source deck") }
            }
        }
        SocialStatus(task)
        if (task.error != null) TextButton(onClick = viewModel::refresh) { Text("Refresh cloud status") }
    }
    confirmation?.let { public ->
        PublishConfirmation(
            public = public,
            onConfirm = {
                viewModel.dismissConfirmation()
                viewModel.publish(public)
            },
            onDismiss = viewModel::dismissConfirmation,
        )
    }
}
