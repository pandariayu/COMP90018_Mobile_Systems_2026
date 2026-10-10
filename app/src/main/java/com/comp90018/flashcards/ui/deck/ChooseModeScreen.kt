package com.comp90018.flashcards.ui.deck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * The ways a deck can be played. [playerCount] is how many person icons the button shows.
 * [isAvailable] is false for modes that are not built yet.
 */
private enum class PlayMode(
    val title: String,
    val description: String,
    val playerCount: Int,
    val isAvailable: Boolean,
) {
    SOLO("Solo", "Study the deck on your own, at your own pace", 1, isAvailable = true),
    DUO("Duo", "One describes, one guesses, and a tilt marks each answer right or wrong", 2, isAvailable = true),
    TWO_V_TWO("2v2", "Two teams of two race through the deck together", 4, isAvailable = false),
}

/**
 * Choose-mode page: pick how to play the deck. Only Solo works for now, and Duo and 2v2 are
 * shown disabled until those modes exist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChooseModeScreen(
    onNavigateBack: () -> Unit,
    onPlaySolo: (String) -> Unit,
    onPlayDuo: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DeckSummaryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(uiState.deckName.ifBlank { "Deck" })
                        Text(text = "Choose mode", style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            PlayMode.entries.forEach { mode ->
                PlayModeButton(
                    mode = mode,
                    // Each option may shrink, so all three stay visible on a short landscape screen.
                    modifier = Modifier.weight(1f, fill = false),
                    onClick = {
                        when (mode) {
                            PlayMode.SOLO -> onPlaySolo(viewModel.deckId)
                            PlayMode.DUO -> onPlayDuo(viewModel.deckId)
                            PlayMode.TWO_V_TWO -> Unit
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PlayModeButton(
    mode: PlayMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(
            onClick = onClick,
            enabled = mode.isAvailable,
            modifier = Modifier.weight(1f, fill = false).fillMaxWidth().height(72.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(mode.playerCount) {
                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(32.dp))
                }
                Text(mode.title, style = MaterialTheme.typography.titleLarge)
            }
        }
        Text(
            text = if (mode.isAvailable) mode.description else "${mode.description} (coming soon)",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
