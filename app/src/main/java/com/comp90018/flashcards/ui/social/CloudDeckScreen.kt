package com.comp90018.flashcards.ui.social

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.comp90018.flashcards.data.social.SocialCard
import com.comp90018.flashcards.data.social.SocialDeck

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudDeckScreen(
    onBack: () -> Unit,
    onOpenLocal: (String) -> Unit,
    onFriends: () -> Unit,
    onOpenSource: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CloudDeckViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val task by viewModel.taskState.collectAsStateWithLifecycle()
    var sharing by rememberSaveable { mutableStateOf(false) }
    var publishing by remember { mutableStateOf<Boolean?>(null) }
    Scaffold(modifier = modifier, topBar = {
        CloudDeckTopBar(onBack, viewModel::refresh, !task.busy)
    }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SocialStatus(task) }
            if (state.deck == null && (state.pendingCopy || state.savedCopyId != null)) {
                copyAction(state, !task.busy, viewModel::saveCopy, onOpenLocal)
            }
            state.deck?.let { deck ->
                item { CloudDeckHeader(deck, onOpenSource) }
                if (deck.ownerId == viewModel.userId) {
                    item {
                        OwnerDeckActions(
                            deck,
                            !task.busy,
                            onPublish = { publishing = it },
                            onShare = {
                                sharing = true
                                viewModel.loadFriends()
                            },
                        )
                    }
                }
                copyAction(state, !task.busy, viewModel::saveCopy, onOpenLocal)
                deckPreview(state, !task.busy, viewModel::moreCards)
            }
        }
    }
    if (sharing) {
        DeckShareSheet(state, task, onDismiss = { sharing = false }, onFriends = {
            sharing = false
            onFriends()
        }, onShare = viewModel::share, onRefresh = viewModel::loadFriends)
    }
    publishing?.let { public ->
        PublishConfirmation(
            public,
            onConfirm = {
                publishing = null
                viewModel.publish(public)
            },
            onDismiss = { publishing = null },
        )
    }
}

private fun LazyListScope.copyAction(
    state: CloudDeckState,
    enabled: Boolean,
    onSave: () -> Unit,
    onOpen: (String) -> Unit,
) {
    item { CopyDeckAction(state.savedCopyId, enabled, onSave, onOpen, state.pendingCopy) }
}

private fun LazyListScope.deckPreview(
    state: CloudDeckState,
    enabled: Boolean,
    onMore: () -> Unit,
) {
    item { Text("Card preview", style = MaterialTheme.typography.titleLarge) }
    if (state.cards.isEmpty()) item { Text("This deck has no cards yet.") }
    items(state.cards, key = { it.cardId }) { card -> CardPreview(card) }
    if (state.moreCards) item { TextButton(onClick = onMore, enabled = enabled) { Text("Load more cards") } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudDeckTopBar(
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    enabled: Boolean,
) {
    TopAppBar(
        title = { Text("Deck community") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = { TextButton(onClick = onRefresh, enabled = enabled) { Text("Refresh") } },
    )
}

@Composable
internal fun PublishConfirmation(
    public: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    SocialConfirmation(
        if (public) "Publish this deck?" else "Make this deck private?",
        if (public) {
            "Everyone signed in can read this deck and save their own copy."
        } else {
            "It will leave the public catalogue. Existing friend shares and saved copies remain."
        },
        if (public) "Publish" else "Make private",
        onConfirm,
        onDismiss,
    )
}

@Composable
private fun CloudDeckHeader(
    deck: SocialDeck,
    onOpenSource: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(deck.name, style = MaterialTheme.typography.headlineMedium)
        Text("${deck.cardCount} cards · ${if (deck.visibility == "public") "Public" else "Private"}")
        Text(
            if (deck.visibility ==
                "public"
            ) {
                "Visible to everyone signed in."
            } else {
                "Visible to the owner and shared friends."
            },
        )
        deck.copiedFrom?.let { source ->
            TextButton(onClick = { onOpenSource(source.deckId) }) { Text("View source deck") }
        }
    }
}

@Composable
internal fun OwnerDeckActions(
    deck: SocialDeck,
    enabled: Boolean,
    onPublish: (Boolean) -> Unit,
    onShare: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Share your deck", style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = onShare,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Share with a friend") }
            OutlinedButton(
                onClick = { onPublish(deck.visibility != "public") },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (deck.visibility == "public") "Unpublish" else "Publish for everyone")
            }
            DeckCode(deck.deckId)
        }
    }
}

@Composable
internal fun CopyDeckAction(
    copyId: String?,
    enabled: Boolean,
    onSave: () -> Unit,
    onOpen: (String) -> Unit,
    pending: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (copyId != null) {
            Button(onClick = { onOpen(copyId) }, modifier = Modifier.fillMaxWidth()) { Text("Open my saved copy") }
        } else {
            Button(
                onClick = onSave,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (pending) "Retry saving copy" else "Save a copy to My Decks") }
            Text(
                "Your copy is private and editable, with fresh study progress. Device-local images cannot be copied.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CardPreview(card: SocialCard) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(card.front, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            Text(card.back)
            if (card.frontImageUri != null || card.backImageUri != null) {
                Text("Contains an image attachment", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
internal fun DeckCode(deckId: String) {
    val context = LocalContext.current
    var copied by remember(deckId) { mutableStateOf(false) }
    TextButton(onClick = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Deck code", deckId))
        copied = true
    }) {
        Text(
            text = if (copied) "Deck code copied" else "Copy deck code",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
