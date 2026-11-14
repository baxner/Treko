package com.treko.assistant.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.treko.assistant.R
import com.treko.assistant.service.TrekoAssistantService
import com.treko.assistant.ui.theme.CardBorder
import com.treko.assistant.ui.theme.DeepBackground
import com.treko.assistant.ui.theme.NeonCyan
import com.treko.assistant.ui.theme.NeonPurple
import com.treko.assistant.ui.theme.SurfaceDark
import com.treko.assistant.ui.theme.TextPrimary
import com.treko.assistant.ui.theme.TextSecondary
import com.treko.core.audio.NativeAudioEngine
import com.treko.core.common.data.TrekoPreferences
import com.treko.core.kws.BiometricVoiceTrainer
import com.treko.core.kws.NeuralSpeechDenoiser
import com.treko.core.kws.SpeakerBiometricEngine
import com.treko.core.kws.SpeakerBiometricProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
fun BiometricVoiceEnrollmentDialog(
    onDismiss: () -> Unit,
    onEnrollmentApplied: (profileJson: String, recommendedThreshold: Float) -> Unit,
    onResetProfile: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasMic = remember { TrekoAssistantService.hasMicPermission(context) }
    val prefs = remember { TrekoPreferences(context) }

    val trainer = remember { BiometricVoiceTrainer(targetTakes = 3) }
    val trainerState by trainer.state.collectAsState()
    val audioLevel by NativeAudioEngine.audioLevel.collectAsState()

    var initialProfile by remember {
        mutableStateOf(SpeakerBiometricProfile.fromJson(prefs.biometricVoicePrintJson))
    }
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Enroll, 1 = Live Test
    var isRecordingNow by remember { mutableStateOf(false) }
    var isProcessingTake by remember { mutableStateOf(false) }
    var recordProgress by remember { mutableFloatStateOf(0f) }
    var feedbackBanner by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var liveResult by remember { mutableStateOf<SpeakerBiometricEngine.VerificationResult?>(null) }

    val fallbackEngine = remember { mutableStateOf<SpeakerBiometricEngine?>(null) }
    val fallbackDenoiser = remember { mutableStateOf<NeuralSpeechDenoiser?>(null) }

    suspend fun getOrInitEngine(): SpeakerBiometricEngine = withContext(Dispatchers.IO) {
        val shared = TrekoAssistantService.getActiveBiometricEngine()
        if (shared != null) {
            if (!shared.isInitialized) shared.initialize()
            return@withContext shared
        }
        val local = fallbackEngine.value ?: SpeakerBiometricEngine(context).also { fallbackEngine.value = it }
        if (!local.isInitialized) local.initialize()
        local
    }

    suspend fun getOrInitDenoiser(): NeuralSpeechDenoiser = withContext(Dispatchers.IO) {
        val shared = TrekoAssistantService.getActiveDenoiser()
        if (shared != null) {
            if (!shared.isInitialized) shared.initialize()
            return@withContext shared
        }
        val local = fallbackDenoiser.value ?: NeuralSpeechDenoiser(context).also { fallbackDenoiser.value = it }
        if (!local.isInitialized) local.initialize()
        local
    }

    DisposableEffect(hasMic) {
        if (hasMic) {
            if (!TrekoAssistantService.isServiceRunning) {
                TrekoAssistantService.startService(context)
            }
            TrekoAssistantService.setTrainingMode(true)
            trainer.startSession()
        }
        onDispose {
            TrekoAssistantService.setTrainingMode(false)
            fallbackEngine.value?.release()
            fallbackEngine.value = null
            fallbackDenoiser.value?.release()
            fallbackDenoiser.value = null
        }
    }

    // Collect continuous live PCM frames whenever the dialog is in training mode
    LaunchedEffect(hasMic) {
        if (!hasMic) return@LaunchedEffect
        TrekoAssistantService.trainingPcmFrames.collect { frame ->
            trainer.onPcmFrame(frame, frame.size)
        }
    }

    // Step 2: periodic non-destructive live verification loop (~1.5s windows)
    LaunchedEffect(selectedTab, hasMic, trainerState.profile, initialProfile) {
        if (!hasMic || selectedTab != 1) return@LaunchedEffect
        val activeProfile = trainerState.profile ?: initialProfile ?: return@LaunchedEffect
        val activeThreshold = trainerState.profile?.recommendedThreshold ?: prefs.biometricSpeakerThreshold

        trainer.enterLiveVerification()
        delay(900L)

        while (selectedTab == 1) {
            delay(1300L)
            val snap = trainer.snapshotRecentPcm(maxSamples = 24000) // 1.5s @ 16kHz
            if (snap.size < 8000) continue

            // Compute simple RMS to skip completely silent room intervals
            var sumSq = 0.0
            for (sample in snap) {
                val f = sample / 32768.0
                sumSq += f * f
            }
            val rms = sqrt(sumSq / snap.size).toFloat()
            val minGate = (NativeAudioEngine.lastFrameMetrics.noiseFloorRms * 1.4f).coerceAtLeast(0.008f)
            if (rms < minGate) continue

            val res = withContext(Dispatchers.Default) {
                val engine = getOrInitEngine()
                val denoiser = getOrInitDenoiser()
                val evalPcm = if (denoiser.isInitialized) {
                    denoiser.reset()
                    val clean = denoiser.processPcm(snap, snap.size)
                    val tail = denoiser.flush()
                    denoiser.reset()
                    val combined = clean + tail
                    if (combined.size >= 4800) combined else snap
                } else {
                    snap
                }
                engine.verifyPcm(
                    samples = evalPcm,
                    count = evalPcm.size,
                    profileOverride = activeProfile,
                    thresholdOverride = activeThreshold,
                    recordTurnStats = false
                )
            }
            if (res.isAvailable) {
                liveResult = res
            }
        }
    }

    val activeProfile = trainerState.profile ?: initialProfile
    val activeThreshold = trainerState.profile?.recommendedThreshold ?: prefs.biometricSpeakerThreshold

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 24.dp),
            shape = RoundedCornerShape(24.dp),
            color = SurfaceDark,
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(28.dp)
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.bio_title),
                                color = TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.bio_subtitle),
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_close),
                            tint = TextSecondary
                        )
                    }
                }

                // Step Tabs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StepChip(
                        label = stringResource(R.string.bio_step_enroll),
                        selected = selectedTab == 0,
                        enabled = true,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedTab = 0 }
                    )
                    StepChip(
                        label = stringResource(R.string.bio_step_verify),
                        selected = selectedTab == 1,
                        enabled = activeProfile != null,
                        modifier = Modifier.weight(1f),
                        onClick = { if (activeProfile != null) selectedTab = 1 }
                    )
                }

                if (!hasMic) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B171D)),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.training_mic_required),
                            color = Color(0xFFFCA5A5),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                } else if (selectedTab == 0) {
                    // STEP 1: 3-Phrase Enrollment
                    Text(
                        text = stringResource(R.string.bio_phrases_desc),
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )

                    val promptStrings = listOf(
                        stringResource(R.string.bio_phrase_1),
                        stringResource(R.string.bio_phrase_2),
                        stringResource(R.string.bio_phrase_3)
                    )
                    val currentTakeIdx = trainerState.recordedTakes.coerceIn(0, 2)

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        promptStrings.forEachIndexed { index, phrase ->
                            val isDone = index < trainerState.recordedTakes
                            val isCurrent = index == currentTakeIdx && trainerState.recordedTakes < 3
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = when {
                                        isDone -> Color(0xFF102A22)
                                        isCurrent -> Color(0xFF132238)
                                        else -> DeepBackground
                                    }
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    when {
                                        isDone -> Color(0xFF10B981).copy(alpha = 0.6f)
                                        isCurrent -> NeonCyan
                                        else -> CardBorder
                                    }
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (isDone) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = Color(0xFF10B981),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Text(
                                        text = phrase,
                                        color = if (isCurrent || isDone) TextPrimary else TextSecondary,
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp
                                    )
                                }
                            }
                        }
                    }

                    // Live Mic VU bar
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(
                            progress = { (audioLevel.rms * 6.5f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = if (audioLevel.isSpeaking) NeonCyan else NeonPurple,
                            trackColor = DeepBackground
                        )
                        if (isRecordingNow) {
                            LinearProgressIndicator(
                                progress = { recordProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp),
                                color = Color(0xFF10B981),
                                trackColor = DeepBackground
                            )
                        }
                    }

                    feedbackBanner?.let { (ok, message) ->
                        Surface(
                            color = if (ok) Color(0xFF0F291E) else Color(0xFF2D161A),
                            border = BorderStroke(
                                1.dp,
                                if (ok) Color(0xFF10B981).copy(alpha = 0.6f) else Color(0xFFF59E0B).copy(alpha = 0.6f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (ok) Color(0xFF10B981) else Color(0xFFF59E0B),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = message,
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    }

                    if (trainerState.recordedTakes < trainerState.targetTakes) {
                        val takeNum = trainerState.recordedTakes + 1
                        Button(
                            onClick = {
                                if (isRecordingNow || isProcessingTake) return@Button
                                scope.launch {
                                    isRecordingNow = true
                                    recordProgress = 0f
                                    feedbackBanner = null

                                    // Give user a short breathing room to settle audio ring before sampling 3.2s window
                                    val steps = 32
                                    val stepMs = BiometricVoiceTrainer.TAKE_DURATION_MS / steps.toLong()
                                    for (s in 1..steps) {
                                        delay(stepMs)
                                        recordProgress = s.toFloat() / steps
                                    }

                                    val snap = trainer.snapshotRecentPcm(BiometricVoiceTrainer.TAKE_WINDOW_SAMPLES)
                                    isRecordingNow = false
                                    isProcessingTake = true

                                    val analysis = withContext(Dispatchers.Default) {
                                        val engine = getOrInitEngine()
                                        val denoiser = getOrInitDenoiser()
                                        val floor = NativeAudioEngine.lastFrameMetrics.noiseFloorRms.coerceAtLeast(0.003f)
                                        trainer.submitTake(
                                            rawSamples = snap,
                                            noiseFloorRms = floor,
                                            denoiser = denoiser,
                                            engine = engine
                                        )
                                    }
                                    isProcessingTake = false

                                    feedbackBanner = when (analysis.quality) {
                                        BiometricVoiceTrainer.TakeQuality.OK -> {
                                            true to context.getString(
                                                R.string.bio_take_ok,
                                                analysis.snrDb,
                                                analysis.activeDurationMs / 1000f
                                            )
                                        }
                                        BiometricVoiceTrainer.TakeQuality.TOO_QUIET -> {
                                            false to context.getString(R.string.bio_quality_too_quiet, analysis.snrDb)
                                        }
                                        BiometricVoiceTrainer.TakeQuality.TOO_NOISY -> {
                                            false to context.getString(R.string.bio_quality_too_noisy)
                                        }
                                        BiometricVoiceTrainer.TakeQuality.CLIPPED -> {
                                            false to context.getString(R.string.bio_quality_clipped)
                                        }
                                        BiometricVoiceTrainer.TakeQuality.TOO_SHORT -> {
                                            false to context.getString(
                                                R.string.bio_quality_too_short,
                                                analysis.activeDurationMs / 1000f
                                            )
                                        }
                                    }

                                    trainer.state.value.profile?.let { builtProfile ->
                                        onEnrollmentApplied(builtProfile.toJson(), builtProfile.recommendedThreshold)
                                    }
                                }
                            },
                            enabled = !isRecordingNow && !isProcessingTake,
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when {
                                    isProcessingTake -> stringResource(R.string.bio_processing)
                                    isRecordingNow -> stringResource(R.string.bio_recording_take)
                                    else -> stringResource(
                                        R.string.bio_record_take,
                                        takeNum,
                                        trainerState.targetTakes
                                    )
                                },
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        val built = trainerState.profile
                        if (built != null) {
                            Surface(
                                color = Color(0xFF0E2B22),
                                border = BorderStroke(1.dp, Color(0xFF10B981)),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.bio_profile_ready,
                                        (built.recommendedThreshold * 100f).roundToInt()
                                    ),
                                    color = Color(0xFF6EE7B7),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { selectedTab = 1 },
                                border = BorderStroke(1.dp, NeonCyan),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.bio_step_verify), color = NeonCyan, fontSize = 12.sp)
                            }
                            Button(
                                onClick = {
                                    built?.let { onEnrollmentApplied(it.toJson(), it.recommendedThreshold) }
                                    onDismiss()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    stringResource(R.string.training_finish_done),
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                } else {
                    // STEP 2: Live Owner vs TV Verification
                    Text(
                        text = stringResource(R.string.bio_verify_desc),
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )

                    LinearProgressIndicator(
                        progress = { (audioLevel.rms * 6.5f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = if (audioLevel.isSpeaking) NeonCyan else NeonPurple,
                        trackColor = DeepBackground
                    )

                    val latest = liveResult
                    val simPct = ((latest?.similarity ?: 0f) * 100f).roundToInt()
                    val thrPct = (activeThreshold * 100f).roundToInt()

                    Surface(
                        color = when {
                            latest == null -> DeepBackground
                            latest.matched -> Color(0xFF0E2B22)
                            else -> Color(0xFF2D161A)
                        },
                        border = BorderStroke(
                            1.dp,
                            when {
                                latest == null -> CardBorder
                                latest.matched -> Color(0xFF10B981)
                                else -> Color(0xFFEF4444)
                            }
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = when {
                                    latest == null -> stringResource(R.string.bio_verify_listening)
                                    latest.matched -> stringResource(
                                        R.string.bio_verify_match_owner,
                                        simPct,
                                        thrPct,
                                        latest.inferenceMs
                                    )
                                    else -> stringResource(
                                        R.string.bio_verify_match_other,
                                        simPct,
                                        thrPct,
                                        latest.inferenceMs
                                    )
                                },
                                color = when {
                                    latest == null -> TextSecondary
                                    latest.matched -> Color(0xFF6EE7B7)
                                    else -> Color(0xFFFCA5A5)
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            if (latest != null) {
                                LinearProgressIndicator(
                                    progress = { latest.similarity.coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp),
                                    color = if (latest.matched) Color(0xFF10B981) else Color(0xFFEF4444),
                                    trackColor = SurfaceDark
                                )
                            }
                        }
                    }

                    Button(
                        onClick = {
                            activeProfile?.let { prof ->
                                onEnrollmentApplied(prof.toJson(), activeThreshold)
                            }
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.training_finish_done),
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Bottom Reset row when a profile exists
                if (activeProfile != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = {
                                initialProfile = null
                                liveResult = null
                                feedbackBanner = null
                                selectedTab = 0
                                trainer.startSession()
                                onResetProfile()
                            },
                            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = Color(0xFFFCA5A5),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.bio_reset),
                                color = Color(0xFFFCA5A5),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(36.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = when {
            selected -> NeonCyan.copy(alpha = 0.2f)
            enabled -> DeepBackground
            else -> DeepBackground.copy(alpha = 0.4f)
        },
        border = BorderStroke(
            1.dp,
            if (selected) NeonCyan else CardBorder.copy(alpha = if (enabled) 1f else 0.4f)
        )
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(
                text = label,
                color = when {
                    selected -> NeonCyan
                    enabled -> TextPrimary
                    else -> TextSecondary.copy(alpha = 0.5f)
                },
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}
