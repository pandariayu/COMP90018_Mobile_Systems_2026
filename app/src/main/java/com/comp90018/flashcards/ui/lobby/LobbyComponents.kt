package com.comp90018.flashcards.ui.lobby

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comp90018.flashcards.R
import com.comp90018.flashcards.ui.connection.DebugConnectionButtons

// every team container is this tall, whatever is inside, so the lobby does not jump around
private val TEAM_SLOT_HEIGHT = 150.dp
private const val TINT_ALPHA = 0.18f

/** Top team blue, bottom team red. A see-through wash over the surface, so it suits light and dark. */
enum class TeamTint(
    private val base: Color?,
) {
    None(null),
    Blue(Color(0xFF2196F3)),
    Red(Color(0xFFF44336)),
    ;

    @Composable
    fun container(): Color {
        val background = MaterialTheme.colorScheme.background
        return base?.copy(TINT_ALPHA)?.compositeOver(background) ?: background
    }
}

@Composable
private fun TeamContainer(
    modifier: Modifier = Modifier,
    tint: TeamTint = TeamTint.None,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().height(TEAM_SLOT_HEIGHT),
        colors = CardDefaults.cardColors(containerColor = tint.container()),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LobbyTopBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Clash Mode",
) {
    TopAppBar(
        modifier = modifier,
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
    )
}

/**
 * Shared container layout for Host and Guest lobby screens.
 */
@Composable
fun LobbyLayout(
    topSlot: @Composable () -> Unit,
    bottomSlot: @Composable () -> Unit,
    bottomActions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    deckName: String? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!deckName.isNullOrBlank()) {
                Text(
                    text = deckName,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            topSlot()

            VersusDivider()

            bottomSlot()

            // DEBUG ONLY: fake the link going wrong, shows in emulator mode
            DebugConnectionButtons()
        }

        bottomActions()
    }
}

/**
 * Reusable card representing a team in the lobby.
 */
@Composable
fun TeamSlotCard(
    label: String,
    teamName: String,
    tint: TeamTint,
    modifier: Modifier = Modifier,
    canReroll: Boolean = false,
    onReroll: (() -> Unit)? = null,
) {
    TeamContainer(tint = tint, modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = teamName,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            if (onReroll != null) {
                IconButton(onClick = onReroll, enabled = canReroll) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Pick another team name",
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun VersusDivider(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text("VS", style = MaterialTheme.typography.titleMedium)
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

@Composable
fun WaitingSlot(modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(TEAM_SLOT_HEIGHT)
                .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text("Waiting for a team to join…", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun PendingSlot(
    teamName: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TeamContainer(tint = TeamTint.None, modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "$teamName wants to join",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDecline, modifier = Modifier.weight(1f)) { Text("Decline") }
                Button(onClick = onAccept, modifier = Modifier.weight(1f)) { Text("Accept") }
            }
        }
    }
}

@Composable
fun JoinedSlot(
    teamName: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TeamContainer(tint = TeamTint.Red, modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = teamName,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove) {
                Icon(
                    painterResource(R.drawable.ic_person_cancel),
                    contentDescription = "Remove team",
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}
