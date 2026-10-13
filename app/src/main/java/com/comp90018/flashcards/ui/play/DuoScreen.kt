package com.comp90018.flashcards.ui.play

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.comp90018.flashcards.data.local.entity.CardEntity

private val MISSED_LIST_MAX_WIDTH = 560.dp

private fun rules(secondsPerCard: Int): List<String> =
    listOf(
        "One of you is the Describer and the other is the Guesser",
        "The Guesser holds the phone up to their forehead, screen facing out",
        "The Describer sees the card and describes it, without saying any word shown on it",
        "The Guesser has ${describeCountdown(secondsPerCard)} to guess, then the back is shown",
        "Check the guess: tilt down if it was right, or tilt up if it was wrong",
    )

/**
 * The 2-player screen, locked to landscape: rules, then one card at a time, then the results.
 */
@Composable
fun DuoScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DuoViewModel = hiltViewModel(),
) {
    LockLandscape()
    PauseWhenBackgrounded(viewModel::pause)
    val uiState by viewModel.uiState.collectAsState()

    Surface(modifier = modifier.fillMaxSize()) {
        when {
            uiState.isLoading -> LoadingContent()
            uiState.isPaused -> PausedContent(uiState, viewModel::resume)
            uiState.phase == DuoPhase.READY -> ReadyContent(uiState, viewModel::start)
            uiState.phase == DuoPhase.FRONT -> FrontContent(uiState, viewModel::reveal)
            uiState.phase == DuoPhase.BACK -> BackContent(uiState, viewModel::onGesture)
            else -> FinishedContent(uiState, viewModel::playAgain, onNavigateBack)
        }
    }
}

@Composable
internal fun LoadingContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** Shown after the app was in the background, so the Guesser can get ready before the clock runs. */
@Composable
internal fun PausedContent(
    uiState: DuoUiState,
    onResume: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Paused", style = MaterialTheme.typography.headlineMedium)
        Text(text = "${formatCountdown(uiState.secondsLeft)} left", style = MaterialTheme.typography.titleMedium)
        Button(onClick = onResume) { Text("Resume") }
    }
}

@Composable
internal fun ReadyContent(
    uiState: DuoUiState,
    onStart: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = uiState.deckName, style = MaterialTheme.typography.headlineMedium)
        Text(text = "How to play", style = MaterialTheme.typography.titleMedium)
        rules(uiState.secondsPerCard).forEachIndexed { index, rule ->
            Text(text = "${index + 1}. $rule", style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = onStart) { Text("Start") }
    }
}

/** The front of the card. Tapping anywhere reveals the back early. */
@Composable
internal fun FrontContent(
    uiState: DuoUiState,
    onReveal: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clickable(onClick = onReveal)
                .padding(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Correct: ${uiState.correctCount} / ${uiState.totalCount}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(text = formatCountdown(uiState.secondsLeft), style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            text = uiState.current?.front.orEmpty(),
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
        )
        Text(
            text = "Tap to reveal the back",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * The back of the card, for checking the guess. The top of the screen stands in for tilting up
 * (wrong) and the bottom for tilting down (right), until the tilt sensor is connected.
 */
@Composable
internal fun BackContent(
    uiState: DuoUiState,
    onGesture: (TiltGesture) -> Unit,
) {
    val context = LocalContext.current
    val currentOnGesture by rememberUpdatedState(onGesture)

    // listen for the tilt while the back is showing, and stop when it leaves the screen
    DisposableEffect(Unit) {
        val detector = DuoFlipDetector(context) { gesture -> currentOnGesture(gesture) }
        detector.start()
        onDispose {
            detector.stop()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        GestureZone(
            label = "Wrong (tilt up)",
            color = MaterialTheme.colorScheme.errorContainer,
            onClick = { onGesture(TiltGesture.UP) },
            modifier = Modifier.weight(1f),
        )
        CardFaces(card = uiState.current)
        GestureZone(
            label = "Right (tilt down)",
            color = MaterialTheme.colorScheme.primaryContainer,
            onClick = { onGesture(TiltGesture.DOWN) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun GestureZone(
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(color)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun CardFaces(card: CardEntity?) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = card?.front.orEmpty(), style = MaterialTheme.typography.titleMedium)
        Text(
            text = card?.back.orEmpty(),
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FinishedContent(
    uiState: DuoUiState,
    onPlayAgain: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "${uiState.correctCount} of ${uiState.totalCount} correct",
            style = MaterialTheme.typography.headlineMedium,
        )
        if (uiState.missed.isNotEmpty()) {
            MissedCards(uiState.missed, modifier = Modifier.weight(1f))
        } else {
            PerfectRound(hasCards = uiState.totalCount > 0, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onPlayAgain) { Text("Play again") }
            Button(onClick = onFinish) { Text("Done") }
        }
    }
}

@Composable
internal fun MissedCards(
    cards: List<CardEntity>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().widthIn(max = MISSED_LIST_MAX_WIDTH),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Missed cards", style = MaterialTheme.typography.titleLarge)
        // Only this list scrolls, so a long list never pushes the buttons off the screen.
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(cards) { card -> MissedCardRow(card) }
        }
    }
}

@Composable
private fun MissedCardRow(card: CardEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = card.front,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = card.back,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Fills the space where the missed cards would be when there are none. */
@Composable
internal fun PerfectRound(
    hasCards: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (hasCards) {
            Text(
                text = "Congratulations, you got it all correct!",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}
