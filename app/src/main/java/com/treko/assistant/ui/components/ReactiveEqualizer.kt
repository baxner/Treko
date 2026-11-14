package com.treko.assistant.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
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
import com.treko.core.common.model.TrekoState
import kotlin.math.PI
import kotlin.math.sin

/**
 * Zero-recomposition 60/120fps Canvas equalizer driven by asymmetric attack/release
 * smoothing in the animation frame loop and cached Brushes in drawWithCache.
 */
@Composable
fun ReactiveEqualizer(
    audioLevelState: State<AudioLevel>,
    modifier: Modifier = Modifier,
    state: TrekoState = TrekoState.IDLE,
    barHeightMax: Dp = 54.dp,
    barCount: Int = 7
) {
    // Mutable float state read ONLY inside drawBehind so audio frames cause zero recompositions.
    val smoothedBars = remember(barCount) { FloatArray(barCount) { 0.10f } }
    var animClock by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(state, barCount) {
        var lastNanos = 0L
        var elapsedSec = 0f
        while (true) {
            withFrameNanos { frameNanos ->
                val dt = if (lastNanos == 0L) 0.016f else ((frameNanos - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                lastNanos = frameNanos
                elapsedSec += dt

                val level = audioLevelState.value
                val bands = level.bands
                val rmsBoost = (level.rms * 4.5f).coerceIn(0f, 1f)

                for (i in 0 until barCount) {
                    val centerDist = kotlin.math.abs(i - (barCount - 1) / 2f) / ((barCount - 1) / 2f).coerceAtLeast(1f)
                    val envelope = 1f - centerDist * 0.35f

                    val target = when (state) {
                        TrekoState.IDLE -> {
                            val idleWave = 0.09f + 0.05f * (0.5f + 0.5f * sin(elapsedSec * 2.1f + i * 0.7f))
                            maxOf(idleWave, rmsBoost * 0.45f * envelope)
                        }
                        TrekoState.THINKING -> {
                            0.18f + 0.48f * (0.5f + 0.5f * sin(elapsedSec * 6.2f - i * 0.85f))
                        }
                        TrekoState.ERROR -> {
                            0.12f + 0.14f * (0.5f + 0.5f * sin(elapsedSec * 4.0f))
                        }
                        TrekoState.LISTENING, TrekoState.SPEAKING -> {
                            val bandIdx = ((i.toFloat() / (barCount - 1).coerceAtLeast(1)) * (bands.size - 1).coerceAtLeast(0))
                                .toInt().coerceIn(0, (bands.size - 1).coerceAtLeast(0))
                            val rawBand = if (bands.isNotEmpty()) bands[bandIdx] else rmsBoost
                            val harmonic = 0.06f * sin(elapsedSec * 9f + i * 1.1f) * rmsBoost
                            (maxOf(rawBand, rmsBoost * 0.85f) * envelope + harmonic).coerceIn(0.08f, 1f)
                        }
                    }

                    val current = smoothedBars[i]
                    // Fast attack (0.42), smooth organic release (0.14) scaled by frame delta
                    val rate = if (target > current) 22f else 9f
                    val alpha = (rate * dt).coerceIn(0.05f, 0.85f)
                    smoothedBars[i] = current + (target - current) * alpha
                }

                // Trigger Draw-phase invalidation only (no recomposition)
                animClock = elapsedSec
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeightMax)
            .drawWithCache {
                val activeBrush = Brush.verticalGradient(
                    colors = listOf(NeonCyan, NeonPurple)
                )
                val thinkingBrush = Brush.verticalGradient(
                    colors = listOf(NeonPurple, NeonCyan)
                )
                val idleBrush = Brush.verticalGradient(
                    colors = listOf(NeonCyan.copy(alpha = 0.45f), NeonPurple.copy(alpha = 0.35f))
                )
                val errorBrush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFFF5252), Color(0xFFFF8A65))
                )

                onDrawBehind {
                    // Read animClock so Compose tracks Draw invalidation cleanly
                    @Suppress("UNUSED_VARIABLE")
                    val tick = animClock

                    val slotWidth = (size.width * 0.72f) / barCount
                    val barWidth = (slotWidth * 0.56f).coerceIn(4.dp.toPx(), 14.dp.toPx())
                    val spacing = (slotWidth - barWidth).coerceAtLeast(4.dp.toPx())
                    val totalWidth = barCount * barWidth + (barCount - 1) * spacing
                    val startX = (size.width - totalWidth) / 2f
                    val minHeight = 7.dp.toPx()

                    val brush = when (state) {
                        TrekoState.IDLE -> idleBrush
                        TrekoState.THINKING -> thinkingBrush
                        TrekoState.ERROR -> errorBrush
                        TrekoState.LISTENING, TrekoState.SPEAKING -> activeBrush
                    }

                    for (i in 0 until barCount) {
                        val factor = smoothedBars[i].coerceIn(0.08f, 1f)
                        val barH = minHeight + (size.height - minHeight) * factor
                        val x = startX + i * (barWidth + spacing)
                        val y = (size.height - barH) / 2f
                        val radius = barWidth / 2f

                        drawRoundRect(
                            brush = brush,
                            topLeft = Offset(x, y),
                            size = Size(barWidth, barH),
                            cornerRadius = CornerRadius(radius, radius)
                        )
                    }
                }
            }
    )
}
