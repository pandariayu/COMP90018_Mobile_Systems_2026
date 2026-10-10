package com.comp90018.flashcards.ui.study.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun FlashcardComponent(
    front: String,
    back: String,
    isFlipped: Boolean,
    onFlip: () -> Unit,
    modifier: Modifier = Modifier,
    frontImageUri: String? = null,
    backImageUri: String? = null,
) {
    val rotation by animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        animationSpec = tween(durationMillis = 500),
        label = "cardFlip",
    )

    ElevatedCard(
        onClick = onFlip,
        modifier =
            modifier
                .graphicsLayer {
                    rotationX = rotation
                    cameraDistance = 12f * density
                },
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (rotation <= 90f) {
                // Front content
                CardContent(
                    text = front,
                    imageUri = frontImageUri,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Back content (mirrored to look correct after flip)
                CardContent(
                    text = back,
                    imageUri = backImageUri,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                rotationX = 180f
                            },
                )
            }
        }
    }
}

@Composable
private fun CardContent(
    text: String,
    imageUri: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        // TODO: Implement image support using imageUri if needed
        // For now, just show the text.
        Text(
            text = text,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
    }
}
