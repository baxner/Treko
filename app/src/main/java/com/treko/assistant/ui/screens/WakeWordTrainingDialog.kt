package com.treko.assistant.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.treko.assistant.R
import com.treko.assistant.service.TrekoAssistantService
import com.treko.assistant.ui.components.ReactiveEqualizer
import com.treko.assistant.ui.theme.CardBorder
import com.treko.assistant.ui.theme.DeepBackground
import com.treko.assistant.ui.theme.NeonCyan
import com.treko.assistant.ui.theme.NeonPurple
import com.treko.assistant.ui.theme.SurfaceDark
import com.treko.assistant.ui.theme.TextPrimary
import com.treko.assistant.ui.theme.TextSecondary
import com.treko.core.audio.NativeAudioEngine
import com.treko.core.common.data.TrekoPreferences
import com.treko.core.common.model.TrekoState
import com.treko.core.kws.TrekoWakeWordDetector
import com.treko.core.kws.WakeWordVoiceTrainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WakeWordTrainingDialog(
    prefs: TrekoPreferences,
    keyword: String,
    onCalibrationApplied: (boost: Float, threshold: Float) -> Unit,
    onCalibrationReset: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val trainer = remember { WakeWordVoiceTrainer(targetSamples = WakeWordVoiceTrainer.DEFAULT_TARGET_SAMPLES) }
    val uiState by trainer.state.collectAsState()
    val micLevelState = NativeAudioEngine.audioLevel.collectAsState()

    val hasMicPermission = remember { TrekoAssistantService.hasMicPermission(context) }
    var isCapturing by remember { mutableStateOf(false) }
    var liveHits by remember { mutableIntStateOf(0) }
    var lastLiveEvent by remember { mutableStateOf<TrekoWakeWordDetector.WakeWordDetectionEvent?>(null) }
    var startedOwnRecording by remember { mutableStateOf(false) }

    LaunchedEffect(keyword) {
        trainer.startSession(keyword.ifBlank { "Treko" })
        TrekoAssistantService.setTrainingMode(true)
        if (hasMicPermission) {
            if (!TrekoAssistantService.isServiceRunning) {
                TrekoAssistantService.startService(context)
                var waitedMs = 0
                while (waitedMs < 1500 && !NativeAudioEngine.isRecording) {
                    delay(50)
                    waitedMs += 50
                }
            }
            if (!NativeAudioEngine.isRecording) {
                NativeAudioEngine.startRecording(scope, preferVoiceCommunication = prefs.audioEchoModeEnabled)
                startedOwnRecording = true
            }
        }
    }

    DisposableEffect(Unit) {
        val pcmListener: (ShortArray, Int) -> Unit = { samples, read ->
            trainer.onPcmFrame(samples, read)
        }
        NativeAudioEngine.addPcmListener(pcmListener)
        onDispose {
            NativeAudioEngine.removePcmListener(pcmListener)
            TrekoAssistantService.setTrainingMode(false)
            if (startedOwnRecording) {
                NativeAudioEngine.stopRecording()
                startedOwnRecording = false
            }
        }
    }

    // Collect live KWS verification hits during VERIFYING_LIVE
    LaunchedEffect(uiState.stage) {
        if (uiState.stage == WakeWordVoiceTrainer.TrainingStage.VERIFYING_LIVE) {
            TrekoAssistantService.trainingDetections.collect { evt ->
                liveHits++
                lastLiveEvent = evt
            }
        }
    }

    fun startNoiseCapture() {
        if (isCapturing || !hasMicPermission) return
        scope.launch {
            isCapturing = true
            trainer.beginNoiseFloorCapture()
            delay(1600L)
            trainer.finishNoiseFloorMeasurement()
            isCapturing = false
        }
    }

    fun startSampleTake() {
        if (isCapturing || !hasMicPermission) return
        scope.launch {
            isCapturing = true
            trainer.beginSampleCapture()
            delay(2200L)
            val analysis = trainer.finishSampleCapture()
            isCapturing = false

            if (uiState.stage == WakeWordVoiceTrainer.TrainingStage.CALIBRATING ||
                (analysis.quality == WakeWordVoiceTrainer.SampleQuality.OK &&
                    trainer.state.value.stage == WakeWordVoiceTrainer.TrainingStage.CALIBRATING)
            ) {
                val result = withContext(Dispatchers.Default) {
                    trainer.runCalibration(
                        detector = TrekoAssistantService.getActiveDetector(),
                        prefs = prefs
                    )
                }
                onCalibrationApplied(result.recommendedBoost, result.recommendedThreshold)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.7f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = NeonCyan)
                        Column {
                            Text(
                                text = stringResource(R.string.training_title),
                                color = NeonCyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                            Text(
                                text = stringResource(R.string.training_subtitle, uiState.keyword),
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close), tint = TextSecondary)
                    }
                }

                // Stage indicator chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StagePill(
                        label = stringResource(R.string.training_step_noise),
                        active = uiState.stage == WakeWordVoiceTrainer.TrainingStage.MEASURING_NOISE_FLOOR ||
                            uiState.stage == WakeWordVoiceTrainer.TrainingStage.FAILED,
                        completed = uiState.stage.ordinal > WakeWordVoiceTrainer.TrainingStage.MEASURING_NOISE_FLOOR.ordinal &&
                            uiState.stage != WakeWordVoiceTrainer.TrainingStage.FAILED,
                        modifier = Modifier.weight(1f)
                    )
                    StagePill(
                        label = stringResource(R.string.training_step_samples),
                        active = uiState.stage == WakeWordVoiceTrainer.TrainingStage.RECORDING_SAMPLE ||
                            uiState.stage == WakeWordVoiceTrainer.TrainingStage.CALIBRATING,
                        completed = uiState.stage == WakeWordVoiceTrainer.TrainingStage.VERIFYING_LIVE ||
                            uiState.stage == WakeWordVoiceTrainer.TrainingStage.COMPLETED,
                        modifier = Modifier.weight(1f)
                    )
                    StagePill(
                        label = stringResource(R.string.training_step_verify),
                        active = uiState.stage == WakeWordVoiceTrainer.TrainingStage.VERIFYING_LIVE,
                        completed = uiState.stage == WakeWordVoiceTrainer.TrainingStage.COMPLETED,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Live audio visualizer
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DeepBackground)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    ReactiveEqualizer(
                        audioLevelState = micLevelState,
                        state = if (isCapturing || uiState.stage == WakeWordVoiceTrainer.TrainingStage.VERIFYING_LIVE) {
                            TrekoState.LISTENING
                        } else {
                            TrekoState.IDLE
                        },
                        barHeightMax = 44.dp
                    )
                }

                if (!hasMicPermission) {
                    Text(
                        text = stringResource(R.string.training_mic_required),
                        color = Color(0xFFFF5252),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    when (uiState.stage) {
                        WakeWordVoiceTrainer.TrainingStage.IDLE,
                        WakeWordVoiceTrainer.TrainingStage.MEASURING_NOISE_FLOOR,
                        WakeWordVoiceTrainer.TrainingStage.FAILED -> {
                            Text(
                                text = stringResource(R.string.training_noise_desc),
                                color = TextPrimary,
                                fontSize = 13.sp
                            )

                            if (isCapturing) {
                                LinearProgressIndicator(
                                    progress = { uiState.progressFraction },
                                    color = NeonCyan,
                                    trackColor = CardBorder,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                )
                                Text(
                                    text = stringResource(R.string.training_measuring_noise),
                                    color = NeonCyan,
                                    fontSize = 12.sp
                                )
                            }

                            if (uiState.stage == WakeWordVoiceTrainer.TrainingStage.FAILED) {
                                Text(
                                    text = stringResource(R.string.training_env_noisy),
                                    color = Color(0xFFFF5252),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Button(
                                onClick = { startNoiseCapture() },
                                enabled = !isCapturing,
                                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.training_measure_noise), fontWeight = FontWeight.Bold)
                            }
                        }

                        WakeWordVoiceTrainer.TrainingStage.RECORDING_SAMPLE -> {
                            val envLabel = when {
                                uiState.noiseFloorRms < 0.012f -> stringResource(R.string.training_env_quiet)
                                uiState.noiseFloorRms < 0.03f -> stringResource(R.string.training_env_moderate)
                                else -> stringResource(R.string.training_env_noisy)
                            }
                            Text(
                                text = stringResource(R.string.training_noise_result, uiState.noiseFloorRms, envLabel),
                                color = Color(0xFF00E676),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )

                            Text(
                                text = stringResource(R.string.training_samples_desc, uiState.keyword),
                                color = TextPrimary,
                                fontSize = 13.sp
                            )

                            if (isCapturing) {
                                LinearProgressIndicator(
                                    progress = { uiState.progressFraction },
                                    color = NeonPurple,
                                    trackColor = CardBorder,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                )
                                Text(
                                    text = stringResource(R.string.training_recording_take, uiState.keyword),
                                    color = NeonCyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            uiState.lastSampleAnalysis?.let { analysis ->
                                val feedbackColor = if (analysis.quality == WakeWordVoiceTrainer.SampleQuality.OK) {
                                    Color(0xFF00E676)
                                } else {
                                    Color(0xFFFFB74D)
                                }
                                val feedbackText = when (analysis.quality) {
                                    WakeWordVoiceTrainer.SampleQuality.OK ->
                                        stringResource(R.string.training_take_ok, analysis.snrDb, analysis.voicedDurationMs)
                                    WakeWordVoiceTrainer.SampleQuality.TOO_QUIET ->
                                        stringResource(R.string.training_quality_too_quiet, analysis.snrDb)
                                    WakeWordVoiceTrainer.SampleQuality.TOO_NOISY ->
                                        stringResource(R.string.training_quality_too_noisy)
                                    WakeWordVoiceTrainer.SampleQuality.CLIPPED ->
                                        stringResource(R.string.training_quality_clipped)
                                    WakeWordVoiceTrainer.SampleQuality.TOO_SHORT ->
                                        stringResource(R.string.training_quality_too_short, analysis.voicedDurationMs)
                                    WakeWordVoiceTrainer.SampleQuality.TOO_LONG ->
                                        stringResource(R.string.training_quality_too_long, analysis.voicedDurationMs)
                                }
                                Text(text = feedbackText, color = feedbackColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }

                            Button(
                                onClick = { startSampleTake() },
                                enabled = !isCapturing,
                                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(
                                        R.string.training_record_take,
                                        (uiState.currentSampleIndex + 1).coerceAtMost(uiState.targetSampleCount),
                                        uiState.targetSampleCount
                                    ),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        WakeWordVoiceTrainer.TrainingStage.CALIBRATING -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(color = NeonCyan, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(stringResource(R.string.training_calibrating), color = TextPrimary, fontSize = 13.sp)
                            }
                        }

                        WakeWordVoiceTrainer.TrainingStage.VERIFYING_LIVE,
                        WakeWordVoiceTrainer.TrainingStage.COMPLETED -> {
                            uiState.calibrationResult?.let { res ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = DeepBackground),
                                    border = BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.5f)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = stringResource(
                                                R.string.training_recommended_params,
                                                res.recommendedBoost,
                                                res.recommendedThreshold
                                            ),
                                            color = NeonCyan,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = stringResource(
                                                R.string.training_calibration_metrics,
                                                res.meanSnrDb,
                                                (res.augmentedRecall * 100).toInt(),
                                                res.falseTriggerCount
                                            ),
                                            color = TextSecondary,
                                            fontSize = 12.sp
                                        )
                                        res.acousticMatcher?.let { matcher ->
                                            Text(
                                                text = stringResource(
                                                    R.string.training_calibration_dtw,
                                                    matcher.threshold,
                                                    matcher.templates.size
                                                ),
                                                color = Color(0xFF00E676),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }

                            Text(
                                text = stringResource(R.string.training_verify_desc, uiState.keyword),
                                color = TextPrimary,
                                fontSize = 12.sp
                            )

                            Surface(
                                color = DeepBackground,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (liveHits > 0) Color(0xFF00E676) else CardBorder
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = if (liveHits > 0) Icons.Default.CheckCircle else Icons.Default.Mic,
                                        contentDescription = null,
                                        tint = if (liveHits > 0) Color(0xFF00E676) else NeonCyan
                                    )
                                    val evt = lastLiveEvent
                                    Text(
                                        text = if (evt != null && liveHits > 0) {
                                            stringResource(
                                                R.string.training_verify_hit,
                                                evt.keyword,
                                                liveHits,
                                                evt.snrDb,
                                                (evt.voicePrintSimilarity * 100).toInt(),
                                                evt.source
                                            )
                                        } else {
                                            stringResource(R.string.training_verify_waiting, uiState.keyword)
                                        },
                                        color = if (liveHits > 0) Color(0xFF00E676) else TextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        liveHits = 0
                                        lastLiveEvent = null
                                        prefs.resetWakeWordCalibration()
                                        TrekoAssistantService.reloadConfiguration(context)
                                        onCalibrationReset()
                                        trainer.startSession(uiState.keyword)
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.training_reset_calibration), fontSize = 12.sp)
                                }
                                Button(
                                    onClick = {
                                        trainer.markCompleted()
                                        onDismiss()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(stringResource(R.string.training_finish_done), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                if (uiState.stage != WakeWordVoiceTrainer.TrainingStage.VERIFYING_LIVE &&
                    uiState.stage != WakeWordVoiceTrainer.TrainingStage.COMPLETED
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.action_close), color = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StagePill(
    label: String,
    active: Boolean,
    completed: Boolean,
    modifier: Modifier = Modifier
) {
    val bg = when {
        completed -> Color(0xFF00E676).copy(alpha = 0.16f)
        active -> NeonCyan.copy(alpha = 0.2f)
        else -> DeepBackground
    }
    val borderColor = when {
        completed -> Color(0xFF00E676)
        active -> NeonCyan
        else -> CardBorder
    }
    val textColor = when {
        completed -> Color(0xFF00E676)
        active -> NeonCyan
        else -> TextSecondary
    }
    Surface(
        color = bg,
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(999.dp),
        modifier = modifier
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
        )
    }
}
