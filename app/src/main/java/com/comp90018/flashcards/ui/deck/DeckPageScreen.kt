package com.comp90018.flashcards.ui.deck

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.comp90018.flashcards.ui.social.PublishConfirmation
import com.comp90018.flashcards.ui.social.SocialTaskState

// which dialog or sheet is open, held together so the screen can hand it to the pieces that draw them
private class DeckPageDialogState {
    var showEdit by mutableStateOf(false)
    var showDelete by mutableStateOf(false)
    var showPublish by mutableStateOf(false)
    var showShare by mutableStateOf(false)
    var showOptions by mutableStateOf(false)
    var readOnlyCard by mutableStateOf<CardUiModel?>(null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeckPageScreen(
    onNavigateBack: () -> Unit,
    onPlay: (String) -> Unit,
    onAddCard: (String) -> Unit,
    onEditCard: (String, String) -> Unit,
    onOpenSource: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenLocal: (String) -> Unit = {},
    onFriends: () -> Unit = {},
    viewModel: DeckSummaryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val taskState by viewModel.taskState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val dialogs = remember { DeckPageDialogState() }

    LaunchedEffect(taskState.message) {
        taskState.message?.let { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(taskState.error) {
        taskState.error?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        modifier = modifier,
        topBar = { DeckPageTopBar(onNavigateBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        DeckPageContent(
            uiState = uiState,
            taskBusy = taskState.busy,
            deckId = viewModel.deckId,
            dialogs = dialogs,
            onPlay = onPlay,
            onAddCard = onAddCard,
            onEditCard = onEditCard,
            onOpenSource = onOpenSource,
            onOpenLocal = onOpenLocal,
            onSaveCopy = viewModel::saveCopy,
            onMoreCards = viewModel::moreCards,
            onLoadFriends = viewModel::loadFriends,
            modifier = Modifier.padding(padding),
        )
    }

    DeckPageSheets(uiState, taskState, viewModel.deckId, dialogs, onFriends, viewModel::share, viewModel::loadFriends)
    DeckPageDialogs(
        uiState = uiState,
        dialogs = dialogs,
        onUpdate = viewModel::updateDeck,
        onPublish = viewModel::publish,
        onDelete = { viewModel.deleteDeck(onDeleted = onNavigateBack) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeckPageTopBar(onNavigateBack: () -> Unit) {
    TopAppBar(
        title = {},
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
    )
}

// the deck itself, with the play bar over the bottom when the deck is the player's own
@Composable
private fun DeckPageContent(
    uiState: DeckSummaryUiState,
    taskBusy: Boolean,
    deckId: String,
    dialogs: DeckPageDialogState,
    onPlay: (String) -> Unit,
    onAddCard: (String) -> Unit,
    onEditCard: (String, String) -> Unit,
    onOpenSource: (String) -> Unit,
    onOpenLocal: (String) -> Unit,
    onSaveCopy: () -> Unit,
    onMoreCards: () -> Unit,
    onLoadFriends: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (uiState.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            return@Box
        }

        DeckPageBody(
            uiState = uiState,
            taskBusy = taskBusy,
            onOpenSource = onOpenSource,
            onOpenLocal = onOpenLocal,
            onSaveCopy = onSaveCopy,
            onMoreCards = onMoreCards,
            onAddCard = { onAddCard(deckId) },
            onEditCard = { cardId -> onEditCard(deckId, cardId) },
            onSelectReadOnlyCard = { card -> dialogs.readOnlyCard = card },
            onShareClick = {
                dialogs.showShare = true
                onLoadFriends()
            },
            onOptionsClick = { dialogs.showOptions = true },
        )

        if (uiState.isOwned) {
            PlayBottomBar(
                canPlay = uiState.cardCount > 0,
                onPlay = { onPlay(deckId) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

// the two bottom sheets, share and options
@Composable
private fun DeckPageSheets(
    uiState: DeckSummaryUiState,
    taskState: SocialTaskState,
    deckId: String,
    dialogs: DeckPageDialogState,
    onFriends: () -> Unit,
    onShare: (String, Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    if (dialogs.showShare) {
        UnifiedDeckShareSheet(
            deckId = deckId,
            friends = uiState.friends,
            task = taskState,
            onDismiss = { dialogs.showShare = false },
            onFriends = {
                dialogs.showShare = false
                onFriends()
            },
            onShare = onShare,
            onRefresh = onRefresh,
        )
    }

    if (dialogs.showOptions) {
        DeckOptionsBottomSheet(
            isPublic = uiState.visibility == "public",
            onDismiss = { dialogs.showOptions = false },
            onEditDeckInfo = { dialogs.showEdit = true },
            onEditVisibility = { dialogs.showPublish = true },
            onDeleteDeck = { dialogs.showDelete = true },
        )
    }
}

// edit, publish, delete and the read-only card dialogs
@Composable
private fun DeckPageDialogs(
    uiState: DeckSummaryUiState,
    dialogs: DeckPageDialogState,
    onUpdate: (String, String) -> Unit,
    onPublish: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    if (dialogs.showEdit) {
        EditDeckDialog(
            currentName = uiState.deckName,
            currentDescription = uiState.deckDescription,
            onConfirm = { newName, newDescription ->
                onUpdate(newName, newDescription)
                dialogs.showEdit = false
            },
            onDismiss = { dialogs.showEdit = false },
        )
    }

    if (dialogs.showPublish) {
        PublishConfirmation(
            public = uiState.visibility != "public",
            onConfirm = {
                dialogs.showPublish = false
                onPublish(uiState.visibility != "public")
            },
            onDismiss = { dialogs.showPublish = false },
        )
    }

    if (dialogs.showDelete) {
        DeleteDeckDialog(
            deckName = uiState.deckName,
            onConfirm = {
                dialogs.showDelete = false
                onDelete()
            },
            onDismiss = { dialogs.showDelete = false },
        )
    }

    dialogs.readOnlyCard?.let { card ->
        ReadOnlyCardDialog(
            card = card,
            onDismiss = { dialogs.readOnlyCard = null },
        )
    }
}

@Composable
private fun DeckPageBody(
    uiState: DeckSummaryUiState,
    taskBusy: Boolean,
    onOpenSource: (String) -> Unit,
    onOpenLocal: (String) -> Unit,
    onSaveCopy: () -> Unit,
    onMoreCards: () -> Unit,
    onAddCard: () -> Unit,
    onEditCard: (String) -> Unit,
    onSelectReadOnlyCard: (CardUiModel) -> Unit,
    onShareClick: () -> Unit,
    onOptionsClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DeckHeaderSection(
            uiState = uiState,
            onOpenSource = onOpenSource,
            onShareClick = onShareClick,
            onOptionsClick = onOptionsClick,
        )

        if (!uiState.isOwned) {
            CommunityCopyActionSection(
                uiState = uiState,
                taskBusy = taskBusy,
                onSaveCopy = onSaveCopy,
                onOpenLocal = onOpenLocal,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Cards · ${uiState.cardCount}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (uiState.isOwned) {
                FilledTonalButton(
                    onClick = onAddCard,
                    modifier = Modifier.height(48.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 20.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add Card", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }

        CardsListSection(
            cards = uiState.cards,
            isOwned = uiState.isOwned,
            moreCards = uiState.moreCards,
            taskBusy = taskBusy,
            onMoreCards = onMoreCards,
            onEditCard = onEditCard,
            onSelectReadOnlyCard = onSelectReadOnlyCard,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CommunityCopyActionSection(
    uiState: DeckSummaryUiState,
    taskBusy: Boolean,
    onSaveCopy: () -> Unit,
    onOpenLocal: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (uiState.savedCopyId != null) {
            Button(
                onClick = { onOpenLocal(uiState.savedCopyId) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text("View my copy", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            Button(
                onClick = onSaveCopy,
                enabled = !taskBusy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(
                    if (uiState.pendingCopy) "Retry saving copy" else "Save a copy to My Decks",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                text = "Your copy is private and editable, with fresh study progress.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CardsListSection(
    cards: List<CardUiModel>,
    isOwned: Boolean,
    moreCards: Boolean,
    taskBusy: Boolean,
    onMoreCards: () -> Unit,
    onEditCard: (String) -> Unit,
    onSelectReadOnlyCard: (CardUiModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (cards.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text =
                    if (isOwned) {
                        "No cards in this deck yet.\nTap 'Add Card' above to create cards."
                    } else {
                        "This deck has no cards yet."
                    },
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Box(modifier = modifier) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = if (isOwned) 120.dp else 24.dp),
            ) {
                items(cards, key = { it.cardId }) { card ->
                    CardItemView(
                        card = card,
                        onClick = {
                            if (isOwned) onEditCard(card.cardId) else onSelectReadOnlyCard(card)
                        },
                    )
                }
                if (moreCards && !isOwned) {
                    item {
                        TextButton(
                            onClick = onMoreCards,
                            enabled = !taskBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Load more cards")
                        }
                    }
                }
            }

            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(16.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(MaterialTheme.colorScheme.background, Color.Transparent),
                            ),
                        ),
            )
        }
    }
}

@Composable
private fun PlayBottomBar(
    canPlay: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.3f to MaterialTheme.colorScheme.background,
                        1f to MaterialTheme.colorScheme.background,
                    ),
                ).padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 16.dp),
    ) {
        Button(
            onClick = onPlay,
            enabled = canPlay,
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Text("Play", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DeckHeaderSection(
    uiState: DeckSummaryUiState,
    onOpenSource: (String) -> Unit,
    onShareClick: () -> Unit,
    onOptionsClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
    ) {
        DeckTitleRow(uiState, onShareClick, onOptionsClick)

        DeckInfoRow(uiState)

        uiState.copiedFrom?.let { source ->
            TextButton(
                onClick = { onOpenSource(source.deckId) },
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("View source deck")
            }
        }

        if (uiState.deckDescription.isNotBlank()) {
            Text(
                text = uiState.deckDescription,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// deck name on the left, share and options on the right
@Composable
private fun DeckTitleRow(
    uiState: DeckSummaryUiState,
    onShareClick: () -> Unit,
    onOptionsClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = uiState.deckName.ifBlank { "Deck" },
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onShareClick) {
                Icon(Icons.Default.Share, contentDescription = "Share")
            }
            if (uiState.isOwned) {
                IconButton(onClick = onOptionsClick) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Options")
                }
            }
        }
    }
}

// public or private, card count and author on one line
@Composable
private fun DeckInfoRow(uiState: DeckSummaryUiState) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = if (uiState.visibility == "public") "Public" else "Private",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "· ${uiState.cardCount} cards",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        uiState.creatorDisplayName?.takeIf { it.isNotBlank() }?.let { name ->
            Text(
                text = "· Created by $name",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CardItemView(
    card: CardUiModel,
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = card.front,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = card.back,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (card.frontImageUri != null || card.backImageUri != null) {
                Text(
                    text = "Contains image attachment",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun EditDeckDialog(
    currentName: String,
    currentDescription: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    var description by remember { mutableStateOf(currentDescription) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Deck Info") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Deck Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(name, description)
                    }
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun DeleteDeckDialog(
    deckName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Deck") },
        text = { Text("Are you sure you want to delete '$deckName'? This action cannot be reversed.") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) {
                Text("Cancel")
            }
        },
    )
}
