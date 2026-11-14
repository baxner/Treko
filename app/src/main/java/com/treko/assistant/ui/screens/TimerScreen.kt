package com.treko.assistant.ui.screens

import com.treko.assistant.service.TrekoAssistantService
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treko.assistant.R
import com.treko.assistant.ui.theme.CardBorder
import com.treko.assistant.ui.theme.NeonCyan
import com.treko.assistant.ui.theme.NeonPurple
import com.treko.assistant.ui.theme.SurfaceDark
import com.treko.assistant.ui.theme.TextPrimary
import com.treko.assistant.ui.theme.TextSecondary
import com.treko.core.common.data.TrekoTimerManager
import com.treko.core.common.model.TimerMode
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimerScreen() {
    val timerState by TrekoTimerManager.timerState.collectAsState()
    val namedTimers by TrekoTimerManager.namedTimers.collectAsState()
    val rawProgress = if (timerState.totalSeconds > 0) {
        (timerState.remainingSeconds.toFloat() / timerState.totalSeconds.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val animatedProgress by animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "TimerArcProgress"
    )

    val pulseTransition = rememberInfiniteTransition(label = "TimerGlowPulse")
    val glowAlpha by pulseTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "TimerGlowAlpha"
    )

    var showCustomDialog by remember { mutableStateOf(false) }
    var customMinutesInput by remember { mutableStateOf("10") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.timer_title), color = NeonCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.timer_subtitle, TrekoAssistantService.assistantName.collectAsState().value), color = TextSecondary, fontSize = 13.sp)

            Spacer(modifier = Modifier.height(10.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(TimerMode.POMODORO, TimerMode.SHORT_BREAK, TimerMode.LONG_BREAK).forEach { mode ->
                    val label = when (mode) {
                        TimerMode.POMODORO -> stringResource(R.string.timer_mode_pomodoro)
                        TimerMode.SHORT_BREAK -> stringResource(R.string.timer_mode_short_break)
                        TimerMode.LONG_BREAK -> stringResource(R.string.timer_mode_long_break)
                        TimerMode.CUSTOM -> stringResource(R.string.timer_mode_custom)
                    }
                    FilterChip(
                        selected = timerState.selectedMode == mode,
                        onClick = { TrekoTimerManager.setMode(mode) },
                        label = { Text(label, fontSize = 11.sp) }
                    )
                }
                FilterChip(
                    selected = timerState.selectedMode == TimerMode.CUSTOM,
                    onClick = { showCustomDialog = true },
                    leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    label = { Text(stringResource(R.string.timer_custom_button), fontSize = 11.sp) }
                )
            }
        }

        val minutes = timerState.remainingSeconds / 60
        val seconds = timerState.remainingSeconds % 60
        val timeString = String.format(Locale.US, "%02d:%02d", minutes, seconds)

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(236.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokePx = 10.dp.toPx()
                drawArc(
                    color = CardBorder,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
                if (timerState.isRunning) {
                    drawArc(
                        color = NeonCyan.copy(alpha = glowAlpha * 0.35f),
                        startAngle = -90f,
                        sweepAngle = 360f * animatedProgress,
                        useCenter = false,
                        style = Stroke(width = strokePx + 8.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
                drawArc(
                    brush = Brush.sweepGradient(listOf(NeonCyan, NeonPurple, NeonCyan)),
                    startAngle = -90f,
                    sweepAngle = 360f * animatedProgress,
                    useCenter = false,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(204.dp)
                    .clip(CircleShape)
                    .background(SurfaceDark)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = timeString,
                        color = if (timerState.isRunning) NeonCyan else TextPrimary,
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (timerState.isRunning) {
                            stringResource(R.string.timer_running)
                        } else {
                            stringResource(R.string.timer_paused)
                        },
                        color = if (timerState.isRunning) NeonPurple else TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = { TrekoTimerManager.toggle() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (timerState.isRunning) Color(0xFFFF5252) else NeonCyan,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier
                        .height(50.dp)
                        .width(140.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = if (timerState.isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (timerState.isRunning) stringResource(R.string.timer_pause) else stringResource(R.string.timer_start),
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = { TrekoTimerManager.reset() },
                    modifier = Modifier
                        .height(50.dp)
                        .width(126.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = TextPrimary)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.timer_reset), color = TextPrimary)
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.timer_sessions_today), color = TextSecondary, fontSize = 12.sp)
                        Text("${timerState.completedSessions}", color = NeonCyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.timer_focus_total), color = TextSecondary, fontSize = 12.sp)
                        Text(
                            stringResource(R.string.timer_focus_minutes, timerState.completedSessions * 25),
                            color = NeonPurple,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (namedTimers.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            stringResource(R.string.timer_active_named_title, namedTimers.size),
                            color = NeonCyan,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        namedTimers.forEach { nt ->
                            val m = nt.remainingSeconds / 60
                            val s = nt.remainingSeconds % 60
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    nt.label,
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    String.format(Locale.US, "%02d:%02d", m, s),
                                    color = NeonPurple,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(
                                    onClick = { TrekoTimerManager.cancelNamedTimer(nt.id) }
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.action_cancel),
                                        tint = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCustomDialog) {
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text(stringResource(R.string.timer_custom_dialog_title), color = NeonCyan) },
            text = {
                OutlinedTextField(
                    value = customMinutesInput,
                    onValueChange = { input ->
                        customMinutesInput = input.filter { it.isDigit() }.take(3)
                    },
                    label = { Text(stringResource(R.string.timer_custom_minutes_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val mins = (customMinutesInput.toIntOrNull() ?: 10).coerceIn(1, 180)
                        TrekoTimerManager.setCustomMinutes(mins)
                        showCustomDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black)
                ) {
                    Text(stringResource(R.string.timer_start))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDialog = false }) {
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
        )
    }
}
