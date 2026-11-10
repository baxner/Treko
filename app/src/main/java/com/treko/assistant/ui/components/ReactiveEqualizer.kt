package com.treko.assistant.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.treko.assistant.ui.theme.NeonCyan
import com.treko.assistant.ui.theme.NeonPurple
import com.treko.core.common.model.AudioLevel

/**
 * Ecualizador reactivo Canvas de 5 barras que oscilan según la amplitud y frecuencia del audio.
 */
@Composable
fun ReactiveEqualizer(
    audioLevel: AudioLevel,
    modifier: Modifier = Modifier,
    barHeightMax: Dp = 48.dp
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeightMax)
    ) {
        val barCount = 5
        val spacing = size.width / (barCount * 3)
        val barWidth = spacing * 2
        val startX = (size.width - (barCount * barWidth + (barCount - 1) * spacing)) / 2

        val gradient = Brush.verticalGradient(
            colors = listOf(NeonCyan, NeonPurple)
        )

        for (i in 0 until barCount) {
            val rawFactor = if (i < audioLevel.bands.size) audioLevel.bands[i] else 0f
            val baseHeight = 6.dp.toPx()
            val animatedHeight = baseHeight + (size.height - baseHeight) * rawFactor.coerceIn(0f, 1f)
            val x = startX + i * (barWidth + spacing)
            val y = (size.height - animatedHeight) / 2

            drawRoundRect(
                brush = gradient,
                topLeft = Offset(x, y),
                size = Size(barWidth, animatedHeight),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2)
            )
        }
    }
}
