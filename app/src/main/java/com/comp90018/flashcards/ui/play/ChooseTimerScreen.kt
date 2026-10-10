package com.comp90018.flashcards.ui.play

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.comp90018.flashcards.ui.deck.DeckSummaryViewModel

private const val MAX_DIGITS = 3

/**
 * The countdown choices. [seconds] is null for Custom, where the player types their own.
 */
private enum class TimerOption(
    val title: String,
    val description: String,
    val seconds: Int?,
) {
    QUICK("10 seconds", "A fast pace, best for cards you know well", 10),
    STANDARD("30 seconds", "Enough time to describe and think", 30),
    CUSTOM("Custom", "Set your own countdown, in seconds or minutes", null),
}

/**
 * Choose-timer page: how long the Guesser gets for each card. Laid out like the choose-mode page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChooseTimerScreen(
    onNavigateBack: () -> Unit,
    onStart: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DeckSummaryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCustomDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(uiState.deckName.ifBlank { "Deck" })
                        Text(text = "Choose countdown", style = MaterialTheme.typography.labelMedium)
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
            TimerOption.entries.forEach { option ->
                TimerOptionButton(
                    option = option,
                    // Each option may shrink, so all three stay visible on a short landscape screen.
                    modifier = Modifier.weight(1f, fill = false),
                    onClick = {
                        val seconds = option.seconds
                        if (seconds == null) showCustomDialog = true else onStart(viewModel.deckId, seconds)
                    },
                )
            }
        }
    }

    if (showCustomDialog) {
        CustomCountdownDialog(
            onConfirm = { seconds ->
                showCustomDialog = false
                onStart(viewModel.deckId, seconds)
            },
            onDismiss = { showCustomDialog = false },
        )
    }
}

@Composable
private fun TimerOptionButton(
    option: TimerOption,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(
            onClick = onClick,
            modifier = Modifier.weight(1f, fill = false).fillMaxWidth().height(72.dp),
        ) {
            Text(option.title, style = MaterialTheme.typography.titleLarge)
        }
        Text(
            text = option.description,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Asks for a countdown as a whole number plus a unit. Start stays disabled until the number is
 * usable (see [parseCountdownSeconds]).
 */
@Composable
private fun CustomCountdownDialog(
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf(CountdownUnit.SECONDS) }
    val seconds = parseCountdownSeconds(input, unit)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom countdown") },
        text = {
            // Scrolls, so the field and unit toggle stay reachable in landscape or with the keyboard open.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.filter(Char::isDigit).take(MAX_DIGITS) },
                    label = { Text("Time for each card") },
                    singleLine = true,
                    isError = input.isNotEmpty() && seconds == null,
                    supportingText = { Text("Up to ${describeCountdown(MAX_COUNTDOWN_SECONDS)}") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                UnitSelector(selected = unit, onSelect = { unit = it })
            }
        },
        confirmButton = {
            Button(onClick = { seconds?.let(onConfirm) }, enabled = seconds != null) { Text("Start") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitSelector(
    selected: CountdownUnit,
    onSelect: (CountdownUnit) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        CountdownUnit.entries.forEachIndexed { index, unit ->
            SegmentedButton(
                selected = unit == selected,
                onClick = { onSelect(unit) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = CountdownUnit.entries.size),
            ) {
                Text(unit.label)
            }
        }
    }
}
