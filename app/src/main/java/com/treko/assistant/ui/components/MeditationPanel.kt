package com.treko.assistant.ui.components

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treko.assistant.R
import com.treko.assistant.meditation.BreathPhase
import com.treko.assistant.meditation.BreathPosition
import com.treko.assistant.meditation.MeditationSnapshot
import com.treko.assistant.meditation.MeditationState
import com.treko.assistant.ui.theme.NeonCyan
import com.treko.assistant.ui.theme.NeonPurple
import com.treko.assistant.ui.theme.TextPrimary
import com.treko.assistant.ui.theme.TextSecondary
import java.util.Locale

private val CalmBlue = Color(0xFF7DD3FC)

/** Clock time (same base as the session's) refreshed every frame while composed. */
@Composable
fun rememberMeditationClock(): State<Long> = produceState(SystemClock.elapsedRealtime()) {
    while (true) {
        withFrameMillis { }
        value = SystemClock.elapsedRealtime()
    }
}

/** Where the breath is now; null when the session is not running (static circle). */
fun breathAt(snapshot: MeditationSnapshot, nowMs: Long): BreathPosition? {
    val pattern = snapshot.type?.pattern ?: return null
    if (snapshot.state != MeditationState.RUNNING) return null
    return pattern.positionAt(snapshot.activeAt(nowMs))
}

/**
 * A soft circle behind the face that grows on the in-breath, holds, and shrinks on the
 * out-breath. Place it first in the Box that holds the face.
 */
@Composable
fun BoxScope.MeditationBreathingHalo(snapshot: MeditationSnapshot, nowMs: Long) {
    val breath = breathAt(snapshot, nowMs)
    val fullness = breath?.fullness ?: 0.45f
    val alpha = if (snapshot.state == MeditationState.RUNNING) 1f else 0.45f
    Canvas(modifier = Modifier.matchParentSize()) {
        val base = size.minDimension / 2f
        val radius = base * (0.78f + 0.42f * fullness)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(CalmBlue.copy(alpha = 0.28f * alpha), NeonPurple.copy(alpha = 0.06f * alpha), Color.Transparent),
                center = Offset(size.width / 2f, size.height / 2f),
                radius = radius * 1.25f
            ),
            radius = radius * 1.25f
        )
        drawCircle(
            color = CalmBlue.copy(alpha = 0.55f * alpha),
            radius = radius,
            style = Stroke(width = 3.dp.toPx())
        )
    }
}

/** Phase, remaining time and the stop / pause buttons of the running session. */
@Composable
fun MeditationPanel(
    snapshot: MeditationSnapshot,
    nowMs: Long,
    onTogglePause: () -> Unit,
    onStop: () -> Unit
) {
    val breath = breathAt(snapshot, nowMs)
    val label = when (snapshot.state) {
        MeditationState.PREPARING, MeditationState.IDLE -> stringResource(R.string.meditation_preparing)
        MeditationState.PAUSED -> stringResource(R.string.meditation_paused)
        MeditationState.ENDING -> stringResource(R.string.meditation_ending)
        MeditationState.RUNNING -> when (breath?.phase) {
            BreathPhase.INHALE -> stringResource(R.string.meditation_phase_inhale)
            BreathPhase.HOLD_IN -> stringResource(R.string.meditation_phase_hold)
            BreathPhase.EXHALE -> stringResource(R.string.meditation_phase_exhale)
            BreathPhase.HOLD_OUT, null -> stringResource(R.string.meditation_phase_rest)
        }
    }
    val remainingS = (snapshot.remainingAt(nowMs) + 999L) / 1000L
    val remaining = String.format(Locale.ROOT, "%d:%02d", remainingS / 60, remainingS % 60)
    Surface(
        color = CalmBlue.copy(alpha = 0.10f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, CalmBlue.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.SelfImprovement, contentDescription = null, tint = CalmBlue)
            Column(modifier = Modifier.weight(1f)) {
                Text(label, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                val details = listOfNotNull(
                    stringResource(R.string.meditation_remaining, remaining),
                    snapshot.placeName,
                    if (snapshot.usingPhoneVoice) stringResource(R.string.meditation_phone_voice_chip) else null
                ).joinToString(" · ")
                Text(details, color = TextSecondary, fontSize = 12.sp, maxLines = 1)
            }
            val paused = snapshot.state == MeditationState.PAUSED
            FilledTonalIconButton(
                onClick = onTogglePause,
                enabled = snapshot.state == MeditationState.RUNNING || paused,
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = NeonCyan.copy(alpha = 0.14f), contentColor = NeonCyan)
            ) {
                Icon(
                    imageVector = if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = stringResource(if (paused) R.string.meditation_resume_cd else R.string.meditation_pause_cd)
                )
            }
            FilledTonalIconButton(
                onClick = onStop,
                enabled = snapshot.state != MeditationState.ENDING,
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0x33F87171), contentColor = Color(0xFFFCA5A5))
            ) {
                Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.meditation_stop_cd))
            }
        }
    }
}
