package com.treko.assistant.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.AlarmClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.treko.assistant.meditation.MeditationController
import com.treko.assistant.meditation.MeditationSnapshot
import com.treko.assistant.meditation.MeditationTimelineBuilder
import com.treko.assistant.meditation.SpeakResult
import com.treko.assistant.meditation.XiaozhiMeditationVoice
import com.treko.assistant.MainActivity
import com.treko.assistant.R
import com.treko.assistant.data.CameraAnomalyCheckResult
import com.treko.assistant.data.ProactiveCameraCandidate
import com.treko.assistant.data.ProactiveDataSources
import com.treko.assistant.data.PendingInsightQueue
import com.treko.assistant.data.ProactiveCategory
import com.treko.assistant.data.ProactiveInsight
import com.treko.assistant.data.SentinelRunTrace
import com.treko.assistant.data.TrekoProactiveManager
import com.treko.assistant.data.TrekoWorkflowManager
import com.treko.assistant.data.WorkflowActions
import com.treko.assistant.data.SpokenText
import com.treko.assistant.data.parseVisionVerdict
import com.treko.core.audio.FullDuplexComplementaryQueue
import com.treko.core.audio.NativeAudioEngine
import com.treko.core.audio.NearFieldListeningGate
import com.treko.core.audio.OpusAudioCodec
import com.treko.core.common.data.TrekoAlertLog
import com.treko.core.common.data.TrekoHabitManager
import com.treko.core.common.data.TrekoPreferences
import com.treko.core.common.data.TrekoTaskManager
import com.treko.core.common.data.TrekoTimerManager
import com.treko.core.common.model.AudioLevel
import com.treko.core.common.model.CameraFeedState
import com.treko.core.common.model.ChatMessage
import com.treko.core.common.model.FullDuplexIndicator
import com.treko.core.common.model.FullDuplexMode
import com.treko.core.common.model.MultiCameraGridState
import com.treko.core.common.model.TimerMode
import com.treko.core.common.model.TrekoEmotion
import com.treko.core.common.model.TrekoState
import com.treko.core.common.util.LogText
import com.treko.core.common.util.TargetLookup
import com.treko.core.common.util.TrekoLogBuffer
import com.treko.core.common.util.failureMessage
import com.treko.core.kws.BiometricTurnGate
import com.treko.core.kws.NeuralSpeechDenoiser
import com.treko.core.kws.SpeakerBiometricEngine
import com.treko.core.kws.TrekoWakeWordDetector
import com.treko.core.mcp.ActionGate
import com.treko.core.mcp.HomeAssistantClient
import com.treko.core.mcp.SensitiveActionGuard
import com.treko.core.mcp.SensitiveActions
import com.treko.core.mcp.SensitiveKind
import com.treko.core.mcp.watch.CameraWatch
import com.treko.core.mcp.watch.WatchSensor
import com.treko.core.common.model.TrekoSkills
import com.treko.core.mcp.RadioBrowserClient
import com.treko.core.network.XiaozhiWebSocketClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

enum class WsConnectionStatus {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}

/**
 * Foreground voice assistant service orchestrating:
 * 1. Local continuous Wake Word detection with Sherpa-ONNX.
 * 2. Full-duplex 16kHz/48kHz Opus audio streaming with Xiaozhi AI.
 * 3. Audio focus management, hardware AEC session sharing, and barge-in.
 * 4. Real-time state, emotion, subtitle, and MCP tool execution.
 */
class TrekoAssistantService : Service() {

    private val TAG = "TrekoService"
    private val CHANNEL_ID = "treko_assistant_service"
    private val NOTIFICATION_ID = 1001

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Null until the model has loaded in the background (see initWakeWordDetector). */
    @Volatile
    private var wakeWordDetector: TrekoWakeWordDetector? = null
    private val wakeWordLock = Any()
    /** Set in onDestroy; a detector that finishes loading afterwards is released, not kept. */
    private var wakeWordReleased = false
    private var speechDenoiser: NeuralSpeechDenoiser? = null
    private var speakerBiometricEngine: SpeakerBiometricEngine? = null
    private var opusCodec: OpusAudioCodec? = null
    private var playbackCollectorJob: Job? = null
    private var wsClient: XiaozhiWebSocketClient? = null
    private var activeWsUrl: String = ""
    private var activeWsToken: String = ""

    private var ttsDrainingJob: Job? = null
    private var thinkingTimeoutJob: Job? = null
    private val conversationWatchdog = ConversationWatchdog()
    private var biometricCheckJob: Job? = null
    private var complementaryBioJob: Job? = null
    private var foreignVoiceResetJob: Job? = null
    private var proactiveLoopJob: Job? = null

    @Volatile
    private var lastProactiveCheckMs: Long = System.currentTimeMillis()

    private val nearFieldGate = NearFieldListeningGate()
    private val bargeInGate = NearFieldListeningGate(nearFieldEnabled = true, minSnrDb = 8.5f)
    private val complementaryQueue = FullDuplexComplementaryQueue()
    private val biometricTurnGate = BiometricTurnGate()
    private val opusSendOrderLock = Any()

    @Volatile
    private var effectiveFullDuplexMode: FullDuplexMode = FullDuplexMode.COMPLEMENTARY

    @Volatile
    private var isComplementaryBioCheckInFlight: Boolean = false

    @Volatile
    private var bargeInVoicedFrames: Int = 0

    private val pendingOpusLock = Any()
    private val pendingOpusPackets = ArrayDeque<ByteArray>(50)

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus: Boolean = false

    private var lastSoundTimestamp: Long = System.currentTimeMillis()
    private var isTorchOn: Boolean = false

    /** Unlock / disarm / open-garage calls need a second, confirmed call (see SensitiveActionGuard). */
    private val sensitiveActionGuard = SensitiveActionGuard()

    @Volatile
    private var hasTurnEmotion: Boolean = false
    @Volatile
    private var suppressIdleTts: Boolean = false

    @Volatile
    private var speechDetected: Boolean = false

    @Volatile
    private var lastSpeechTimestamp: Long = 0L

    @Volatile
    private var listeningStartTimestamp: Long = 0L

    @Volatile
    private var speakingStartTimestamp: Long = 0L

    @Volatile
    private var activeListeningTimeoutMs: Long = 5000L

    @Volatile
    private var effectiveAutoFollowUpEnabled: Boolean = true

    @Volatile
    private var effectiveVoiceBargeInEnabled: Boolean = true

    @Volatile
    private var effectiveBiometricGateEnabled: Boolean = false

    @Volatile
    private var isBiometricCheckInFlight: Boolean = false

    @Volatile
    private var isClosingTurn: Boolean = false

    @Volatile
    private var listeningOpenedByAutoFollowUp: Boolean = false

    @Volatile
    private var listeningTurnGeneration: Long = 0L

    @Volatile
    private var foreignRejectionsInTurn: Int = 0

    @Volatile
    private var listeningHardDeadlineMs: Long = 0L

    @Volatile
    private var lastListeningTimeoutTimestamp: Long = 0L

    @Volatile
    private var phoneMediaPlayer: MediaPlayer? = null

    private var proactiveTtsFallbackJob: Job? = null

    @Volatile
    private var activeVoiceProactiveInsight: ProactiveInsight? = null

    @Volatile
    private var activeVoiceProactiveFromUserTap: Boolean = false

    @Volatile
    private var localTts: TextToSpeech? = null
    private val isLocalTtsReady = AtomicBoolean(false)
    private val proactiveUtteranceSeq = AtomicInteger(0)

    private val radioBrowserClient by lazy { RadioBrowserClient() }

    /** Guided meditation (the `meditation` tool); null until onCreate. */
    private var meditation: MeditationController? = null

    /** A meditation cue Xiaozhi is reading (see [deliverMeditationCue]). */
    private class MeditationCueRequest(val text: String, val turnGeneration: Long) {
        val done = CompletableDeferred<Boolean>()
        @Volatile var consumed = false
        @Volatile var started = false

        /** Cut on purpose (the wake word, a pause, an alert): not a voice failure. */
        @Volatile var cancelled = false
    }

    /** Ending a cue is check-and-clear: the session's job and the wake word may both end it. */
    private val meditationCueLock = Any()

    @Volatile
    private var activeMeditationCue: MeditationCueRequest? = null

    /** Xiaozhi could not be reached for the last cue: the session switches to the phone's voice. */
    @Volatile
    private var meditationXiaozhiUnreachable = false
    private val workflowManager by lazy { TrekoWorkflowManager(this) }

    companion object {
        const val ACTION_TOGGLE_CONVERSATION = "com.treko.assistant.action.TOGGLE_CONVERSATION"
        const val ACTION_TOGGLE_MUTE = "com.treko.assistant.action.TOGGLE_MUTE"
        const val ACTION_TOGGLE_CINEMA_MODE = "com.treko.assistant.action.TOGGLE_CINEMA_MODE"
        const val ACTION_STOP_WATCHES = "com.treko.assistant.action.STOP_WATCHES"
        const val ACTION_RELOAD_CONFIG = "com.treko.assistant.action.RELOAD_CONFIG"
        const val ACTION_CONSUME_PROACTIVE_INSIGHT = "com.treko.assistant.action.CONSUME_PROACTIVE_INSIGHT"
        const val ACTION_DISMISS_PROACTIVE_INSIGHT = "com.treko.assistant.action.DISMISS_PROACTIVE_INSIGHT"
        const val ACTION_TRIGGER_PROACTIVE_CHECK = "com.treko.assistant.action.TRIGGER_PROACTIVE_CHECK"

        private const val PROACTIVE_CHANNEL_ID = "treko_proactive_channel"
        private const val PROACTIVE_NOTIFICATION_ID = 2200
        /** How much of each MCP tool result goes into the log (one line, secrets masked). */
        private const val TOOL_RESULT_LOG_CHARS = 300
        /** Wait for a Xiaozhi session before falling back to the phone's voice (reconnects take 2–4 s). */
        private const val PROACTIVE_SESSION_WAIT_MS = 4_000L
        private const val MEDITATION_SESSION_WAIT_MS = 8_000L
        private const val MEDITATION_CUE_START_TIMEOUT_MS = 14_000L

        private val _serviceState = MutableStateFlow(TrekoState.IDLE)
        val serviceState: StateFlow<TrekoState> = _serviceState.asStateFlow()

        private val _isMuted = MutableStateFlow(false)
        val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

        private val _isCinemaMode = MutableStateFlow(false)
        val isCinemaMode: StateFlow<Boolean> = _isCinemaMode.asStateFlow()

        private val _lastBiometricResult = MutableStateFlow<SpeakerBiometricEngine.VerificationResult?>(null)
        val lastBiometricResult: StateFlow<SpeakerBiometricEngine.VerificationResult?> = _lastBiometricResult.asStateFlow()

        private val _connectionStatus = MutableStateFlow(WsConnectionStatus.DISCONNECTED)
        val connectionStatus: StateFlow<WsConnectionStatus> = _connectionStatus.asStateFlow()

        private val _activeSessionId = MutableStateFlow<String?>(null)
        val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

        private val _currentEmotion = MutableStateFlow(TrekoEmotion.RELAX)
        val currentEmotion: StateFlow<TrekoEmotion> = _currentEmotion.asStateFlow()

        private val _subtitleText = MutableStateFlow("")
        val subtitleText: StateFlow<String> = _subtitleText.asStateFlow()

        private val _playbackAudioLevel = MutableStateFlow(AudioLevel())
        val playbackAudioLevel: StateFlow<AudioLevel> = _playbackAudioLevel.asStateFlow()

        private val _fullDuplexIndicator = MutableStateFlow(FullDuplexIndicator.INACTIVE)
        val fullDuplexIndicator: StateFlow<FullDuplexIndicator> = _fullDuplexIndicator.asStateFlow()

        private val _activeWatchAreas = MutableStateFlow<List<String>>(emptyList())
        /** Places Treko is watching for the user (camera watches), for the UI. */
        val activeWatchAreas: StateFlow<List<String>> = _activeWatchAreas.asStateFlow()

        /** The main activity is open (set by MainActivity). */
        @Volatile
        var isUiAlive: Boolean = false

        private val _activeWatches = MutableStateFlow<List<CameraWatch>>(emptyList())
        /** The camera watches running right now, for the alerts screen. */
        val activeWatches: StateFlow<List<CameraWatch>> = _activeWatches.asStateFlow()

        /** The running meditation for the face screen (state IDLE when none). */
        val meditationSnapshot: StateFlow<MeditationSnapshot> get() = MeditationController.snapshot

        fun stopMeditation() {
            instance?.meditation?.stopFromUi()
        }

        fun toggleMeditationPause() {
            instance?.meditation?.togglePauseFromUi()
        }

        fun stopAllWatches() {
            instance?.cameraWatch?.stopAll()
        }

        /** Stops one camera watch (alerts screen). */
        fun stopWatch(id: Long) {
            val coordinator = instance?.cameraWatch ?: return
            instance?.serviceScope?.launch(Dispatchers.IO) { coordinator.stopWatch(id) }
        }

        private val _conversationHistory = MutableStateFlow<List<ChatMessage>>(emptyList())
        val conversationHistory: StateFlow<List<ChatMessage>> = _conversationHistory.asStateFlow()

        /**
         * Insights that could not be spoken yet, most important first: a camera alert is never
         * replaced by a later, less important briefing.
         */
        private val pendingQueue = PendingInsightQueue()
        private val _pendingProactiveInsight = MutableStateFlow<ProactiveInsight?>(null)
        /** The most important pending insight (head of the queue), for the UI banner. */
        val pendingProactiveInsight: StateFlow<ProactiveInsight?> = _pendingProactiveInsight.asStateFlow()
        private val _pendingProactiveInsights = MutableStateFlow<List<ProactiveInsight>>(emptyList())
        val pendingProactiveInsights: StateFlow<List<ProactiveInsight>> = _pendingProactiveInsights.asStateFlow()

        /**
         * The insight the proactive notification shows (its Listen/Dismiss actions refer to it),
         * null when it is not showing. Guarded by [pendingQueue].
         */
        private var notifiedInsightId: String? = null

        private fun publishPendingLocked() {
            // Stale insights are never published (nor read back as current).
            pendingQueue.prune(System.currentTimeMillis())
            _pendingProactiveInsights.value = pendingQueue.snapshot()
            _pendingProactiveInsight.value = pendingQueue.peek()
        }

        private fun enqueuePendingInsight(insight: ProactiveInsight) = synchronized(pendingQueue) {
            pendingQueue.offer(insight)
            publishPendingLocked()
        }

        /**
         * Takes insight [id] out of the queue, or the most important one when [id] is null.
         * An [id] that is no longer queued takes nothing (never a different insight).
         */
        private fun takePendingInsight(id: String? = null): ProactiveInsight? = synchronized(pendingQueue) {
            pendingQueue.prune(System.currentTimeMillis())
            val taken = if (id != null) pendingQueue.remove(id) else pendingQueue.poll()
            publishPendingLocked()
            taken
        }

        private fun removePendingInsight(id: String) = synchronized(pendingQueue) {
            if (pendingQueue.remove(id) != null) publishPendingLocked()
        }

        /** Drops pending insights too old to be read as news, and fixes the notification. */
        fun prunePendingInsights(context: Context) {
            synchronized(pendingQueue) { publishPendingLocked() }
            refreshProactiveNotification(context)
        }

        /**
         * The user opened alerts-log entry [alertId] (Alerts screen or its notification): it is
         * no longer pending, so it is not read back later as news.
         */
        fun onAlertSeen(context: Context, alertId: Long) {
            val removed = synchronized(pendingQueue) {
                pendingQueue.removeByAlertId(alertId).also { if (it.isNotEmpty()) publishPendingLocked() }
            }
            if (removed.isNotEmpty()) refreshProactiveNotification(context)
        }

        /**
         * Keeps the proactive notification pointing at a queued insight: when the one it shows
         * left the queue (dismissed, evicted, stale), it is re-posted for the most important
         * remaining briefing, or cancelled when none is left. Camera alerts have their own.
         */
        private fun refreshProactiveNotification(context: Context) {
            val (shown, remaining) = synchronized(pendingQueue) { notifiedInsightId to pendingQueue.snapshot() }
            if (shown == null || remaining.any { it.id == shown }) return
            val next = remaining.firstOrNull { it.category != ProactiveCategory.CAMERA_ANOMALY }
            if (next != null) postProactiveNotification(context, next) else cancelProactiveNotification(context)
        }

        /** "Hace 12 minutos: …" before a camera alert read well after it came up. */
        private fun withHeldAgeNote(context: Context, insight: ProactiveInsight, nowMs: Long = System.currentTimeMillis()): ProactiveInsight {
            val minutes = PendingInsightQueue.heldAgeMinutes(insight, nowMs) ?: return insight
            val note = context.resources.getQuantityString(R.plurals.pending_alert_age_minutes, minutes, minutes)
            val speech = insight.spokenSpeechText.ifBlank { insight.summaryText }.trim()
            return insight.copy(spokenSpeechText = "$note $speech")
        }

        /** String extra: the insight a proactive notification's actions refer to. */
        private const val EXTRA_INSIGHT_ID = "com.treko.assistant.extra.INSIGHT_ID"

        private val _isProactiveCheckRunning = MutableStateFlow(false)
        val isProactiveCheckRunning: StateFlow<Boolean> = _isProactiveCheckRunning.asStateFlow()

        private val _assistantName = MutableStateFlow("Treko")
        val assistantName: StateFlow<String> = _assistantName.asStateFlow()

        /** Normalizes a custom wake word into a capitalized display name for UI labels. */
        fun formatAssistantName(rawWakeWord: String?, fallback: String = "Treko"): String {
            val cleanFallback = fallback.trim().ifBlank { "Treko" }
            val chosen = rawWakeWord?.trim().orEmpty().ifBlank { cleanFallback }
            return chosen.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString()
            }
        }

        /** Resolves the configured assistant display name from preferences or string defaults. */
        fun resolveAssistantName(context: Context): String {
            val appCtx = context.applicationContext
            val raw = TrekoPreferences(appCtx).customWakeWord
            val fallback = appCtx.getString(R.string.wake_word)
            return formatAssistantName(raw, fallback)
        }

        private val _trainingDetections = MutableSharedFlow<TrekoWakeWordDetector.WakeWordDetectionEvent>(extraBufferCapacity = 32)
        val trainingDetections: SharedFlow<TrekoWakeWordDetector.WakeWordDetectionEvent> = _trainingDetections.asSharedFlow()

        private val _trainingPcmFrames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 64)
        val trainingPcmFrames: SharedFlow<ShortArray> = _trainingPcmFrames.asSharedFlow()

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        @Volatile
        var isTrainingMode: Boolean = false
            private set

        @Volatile
        private var instance: TrekoAssistantService? = null

        fun setTrainingMode(active: Boolean) {
            isTrainingMode = active
            val activeSvc = instance ?: return
            if (active) {
                activeSvc.abortConversation()
                if (_isMuted.value && !NativeAudioEngine.isRecording && hasMicPermission(activeSvc)) {
                    val prefs = TrekoPreferences(activeSvc)
                    NativeAudioEngine.startRecording(
                        activeSvc.serviceScope,
                        preferVoiceCommunication = prefs.audioEchoModeEnabled
                    )
                }
            } else {
                if (_isMuted.value && NativeAudioEngine.isRecording) {
                    NativeAudioEngine.stopRecording()
                }
            }
        }

        fun addChatMessage(sender: String, text: String) {
            val clean = text.trim()
            if (clean.isBlank()) return
            val message = ChatMessage(sender = sender, text = clean, timestamp = System.currentTimeMillis())
            // update {} es atómico: dos hilos (STT y TTS) ya no se pisan los mensajes.
            _conversationHistory.update { ChatMessage.append(it, message) }
        }

        fun clearHistory() {
            _conversationHistory.value = emptyList()
        }

        @Volatile
        private var pendingStartConversationKeyword: String? = null

        @Volatile
        private var pendingProactiveDelivery: ProactiveInsight? = null

        private val proactiveFallbackScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val sharedProactiveLock = Any()
        private val proactiveCameraCycle = AtomicInteger(0)

        @Volatile
        private var sharedProactiveManager: TrekoProactiveManager? = null

        private val fallbackHaLock = Any()
        private var fallbackHaClient: HomeAssistantClient? = null
        private var fallbackHaKey: String? = null

        /**
         * The Home Assistant client to use for proactive and sentinel work: the running
         * service's (shared with the live event stream, so caches such as the states and the
         * vision provider are reused), else one kept per configuration. Null when not configured.
         */
        private fun sharedHaClient(context: Context): HomeAssistantClient? {
            instance?.let { return it.currentWatchHaClient() }
            val p = TrekoPreferences(context.applicationContext)
            val haUrl = p.haUrl.trim()
            val haToken = p.haToken.trim()
            return synchronized(fallbackHaLock) {
                if (haUrl.isBlank() || haToken.isBlank()) {
                    fallbackHaClient = null
                    fallbackHaKey = null
                    return@synchronized null
                }
                val key = "$haUrl\n$haToken"
                if (key != fallbackHaKey) {
                    fallbackHaClient = HomeAssistantClient(haUrl, haToken)
                    fallbackHaKey = key
                }
                fallbackHaClient
            }
        }

        private fun getOrCreateProactiveManager(context: Context): TrekoProactiveManager {
            val appCtx = context.applicationContext
            return synchronized(sharedProactiveLock) {
                sharedProactiveManager ?: TrekoProactiveManager(
                    sources = object : ProactiveDataSources {
                        override suspend fun getHomeStatusSummary(): String {
                            val client = sharedHaClient(appCtx) ?: return ""
                            return client.getHomeStatusSummary()
                        }

                        override suspend fun scanPriorityCamerasForAnomalies(
                            homeStatusSummary: String
                        ): List<CameraAnomalyCheckResult> {
                            // Callers decide whether cameras are scanned (includeCameraScan): the
                            // periodic check follows the camera-scan setting, the sentinel its own.
                            val p = TrekoPreferences(appCtx)
                            val trace = coroutineContext[SentinelRunTrace]
                            val client = sharedHaClient(appCtx)
                            if (client == null) {
                                trace?.decide("skipped", "Home Assistant not configured")
                                return emptyList()
                            }
                            // Every scan (periodic, check_now, the no-service fallback and the
                            // sentinel) needs LLM Vision; an empty set means detection has not run yet.
                            val skills = p.detectedSkills
                            if (skills.isNotEmpty() && TrekoSkills.LLM_VISION !in skills) {
                                trace?.decide("skipped", "LLM Vision not detected")
                                return emptyList()
                            }
                            val prompts = VisionPrompts(appCtx)

                            /** One vision look at [imageEntity], recorded in the run's trace. */
                            suspend fun look(
                                cameraName: String,
                                imageEntity: String,
                                cameraEntityId: String?,
                                prompt: String,
                                label: String?,
                                watchRequested: Boolean
                            ): CameraAnomalyCheckResult? {
                                val visionStartMs = System.currentTimeMillis()
                                val report = client.analyzeCameraWithLlmVision(imageEntity, prompt)?.trim().orEmpty()
                                val verdict = parseVisionVerdict(report)
                                val result = if (report.isBlank()) {
                                    null
                                } else {
                                    CameraAnomalyCheckResult(
                                        cameraName = cameraName,
                                        entityId = imageEntity,
                                        visionReport = report,
                                        // The structured level decides; keywords only when the model gave no JSON.
                                        hasAnomaly = verdict?.raisesAlert(watchRequested)
                                            ?: TrekoProactiveManager.detectCameraAnomaly(report),
                                        cameraEntityId = cameraEntityId,
                                        verdict = verdict,
                                        label = label
                                    )
                                }
                                trace?.look(
                                    cameraName = cameraName,
                                    imageEntityId = imageEntity,
                                    durationMs = System.currentTimeMillis() - visionStartMs,
                                    verdict = report,
                                    anomaly = result?.hasAnomaly,
                                    level = verdict?.level?.key,
                                    // A parsed verdict always gets a readable line, never its raw JSON.
                                    description = verdict?.fallbackLine(cameraAlertTexts(appCtx))
                                )
                                return result
                            }

                            // A detector fired: look only at its camera, at the detector's own
                            // picture of the detection first (the person may be gone from the
                            // live view by now), then at the live camera. No other camera.
                            val triggerCamera = trace?.cameraEntityId
                            if (trace != null && triggerCamera != null) {
                                val name = trace.area.ifBlank { triggerCamera.substringAfter('.').replace('_', ' ') }
                                val prompt = prompts.sentinel(
                                    cameraName = name,
                                    label = trace.label,
                                    watchRequested = trace.watchRequested
                                )
                                val images = listOfNotNull(trace.snapshotEntityId, triggerCamera).distinct()
                                for (image in images) {
                                    val result = look(name, image, triggerCamera, prompt, trace.label, trace.watchRequested)
                                    if (result != null) return listOf(result)
                                }
                                return emptyList()
                            }

                            val camerasStartMs = System.currentTimeMillis()
                            val groups = client.getDeduplicatedCameras()
                            trace?.stage("cameras", System.currentTimeMillis() - camerasStartMs)
                            // Frigate cameras are only looked at while Frigate detects something in
                            // them (no blind vision calls on empty scenes). Cameras outside Frigate
                            // keep the regular scan. If Home Assistant can't say, scan as before.
                            var frigateCameras: Set<String> = emptySet()
                            var frigateBusy: Set<String> = emptySet()
                            if (TrekoSkills.FRIGATE in p.detectedSkills) {
                                val catalogStartMs = System.currentTimeMillis()
                                // The watch coordinator's catalog when it has one (no extra download).
                                val catalog = instance?.cameraWatch?.catalogSnapshot?.takeIf { it.isNotEmpty() }
                                    ?: client.getWatchCatalog().orEmpty()
                                val sensors = catalog
                                    .filter { it.source == WatchSensor.Source.FRIGATE_CAMERA && it.cameraEntityId != null }
                                // The states read a moment ago for the camera list are fresh enough.
                                val states = client.getStates(sensors.map { it.entityId }.toSet(), forceRefresh = false)
                                if (sensors.isNotEmpty() && states.isNotEmpty()) {
                                    frigateCameras = sensors.mapNotNull { it.cameraEntityId }.toSet()
                                    frigateBusy = sensors.filter { states[it.entityId] == "on" }.mapNotNull { it.cameraEntityId }.toSet()
                                }
                                trace?.stage("catalog", System.currentTimeMillis() - catalogStartMs)
                                trace?.note("frigate_busy=${frigateBusy.sorted().ifEmpty { listOf("none") }.joinToString(",")}")
                            }
                            fun idsOf(g: HomeAssistantClient.HaCameraGroup) =
                                setOfNotNull(g.primaryEntityId, g.snapshotEntityId, g.liveEntityId)
                            fun candidateOf(g: HomeAssistantClient.HaCameraGroup) = ProactiveCameraCandidate(
                                friendlyName = g.friendlyName,
                                entityId = g.snapshotEntityId.ifBlank { g.primaryEntityId },
                                state = g.state
                            )
                            // Cameras where Frigate sees something now go first, so a detection
                            // is always looked at; the remaining slots rotate as before.
                            val busy = groups.filter { g -> idsOf(g).any { it in frigateBusy } }.map(::candidateOf)
                            val others = groups.filter { g -> idsOf(g).none { it in frigateCameras || it in frigateBusy } }.map(::candidateOf)
                            val mainEntityOf = groups.associate { g -> candidateOf(g).entityId to g.primaryEntityId }
                            val busyPicked = busy.take(3)
                            val selected = busyPicked + TrekoProactiveManager.selectImportantCamerasForScan(
                                candidates = others,
                                homeStatusSummary = homeStatusSummary,
                                maxCameras = 3 - busyPicked.size,
                                rotationOffset = proactiveCameraCycle.getAndIncrement()
                            ).takeIf { busyPicked.size < 3 }.orEmpty()
                            if (selected.isEmpty()) {
                                trace?.decide("skipped", "no camera selected (${groups.size} cameras, none busy or eligible)")
                                return emptyList()
                            }

                            return coroutineScope {
                                selected.map { cam ->
                                    async {
                                        look(
                                            cameraName = cam.friendlyName,
                                            imageEntity = cam.entityId,
                                            cameraEntityId = mainEntityOf[cam.entityId],
                                            prompt = prompts.sentinel(cameraName = cam.friendlyName, label = null),
                                            label = null,
                                            watchRequested = false
                                        )
                                    }
                                }.awaitAll().filterNotNull()
                            }
                        }

                        override suspend fun getHabitsSummary(): String =
                            TrekoHabitManager(appCtx).listHabitsSummary()

                        override suspend fun getTasksSummary(): String =
                            TrekoTaskManager(appCtx).listTasksSummary()

                        override suspend fun getVaultMemories(): List<Pair<String, String>> {
                            val vaultPrefs = appCtx.getSharedPreferences("treko_vault_memory", Context.MODE_PRIVATE)
                            return vaultPrefs.all.entries.mapNotNull { (k, v) ->
                                val str = v?.toString()?.trim()
                                if (!str.isNullOrBlank()) k to str else null
                            }
                        }

                        override fun getActiveTimersCount(): Int =
                            TrekoTimerManager.namedTimers.value.count { it.isRunning } +
                                if (TrekoTimerManager.timerState.value.isRunning) 1 else 0

                        override fun getLastWaterIntakeMs(): Long =
                            TrekoHabitManager(appCtx).getLastWaterIntakeMs()
                    },
                    // Read each time, so a language change applies without a restart.
                    cameraTexts = { cameraAlertTexts(appCtx) }
                ).also { sharedProactiveManager = it }
            }
        }

        private fun ensureProactiveNotificationChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                PROACTIVE_CHANNEL_ID,
                context.getString(R.string.proactive_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.proactive_channel_desc)
            }
            manager.createNotificationChannel(channel)
        }

        private fun postProactiveNotification(context: Context, insight: ProactiveInsight) {
            val appCtx = context.applicationContext
            ensureProactiveNotificationChannel(appCtx)
            val manager = appCtx.getSystemService(NotificationManager::class.java) ?: return

            val contentPendingIntent = AlertNotifications.openAppIntent(
                appCtx,
                PROACTIVE_NOTIFICATION_ID,
                insight.alertId,
                extraFlags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            )

            // The actions act on the insight shown, not on whatever is first in the queue.
            val speakIntent = Intent(appCtx, TrekoAssistantService::class.java).apply {
                action = ACTION_CONSUME_PROACTIVE_INSIGHT
                putExtra(EXTRA_INSIGHT_ID, insight.id)
            }
            val speakPendingIntent = PendingIntent.getService(
                appCtx,
                PROACTIVE_NOTIFICATION_ID + 1,
                speakIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val dismissIntent = Intent(appCtx, TrekoAssistantService::class.java).apply {
                action = ACTION_DISMISS_PROACTIVE_INSIGHT
                putExtra(EXTRA_INSIGHT_ID, insight.id)
            }
            val dismissPendingIntent = PendingIntent.getService(
                appCtx,
                PROACTIVE_NOTIFICATION_ID + 2,
                dismissIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val title = appCtx.getString(R.string.proactive_notif_title, resolveAssistantName(appCtx))
            val notification = NotificationCompat.Builder(appCtx, PROACTIVE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_treko_notif)
                .setContentTitle(title)
                .setContentText(insight.summaryText)
                .setStyle(NotificationCompat.BigTextStyle().bigText("${insight.title}: ${insight.summaryText}"))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                // Re-posted for the next briefing when the shown one leaves: no second sound.
                .setOnlyAlertOnce(true)
                .setContentIntent(contentPendingIntent)
                .addAction(
                    android.R.drawable.ic_btn_speak_now,
                    appCtx.getString(R.string.proactive_action_listen),
                    speakPendingIntent
                )
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    appCtx.getString(R.string.proactive_action_dismiss),
                    dismissPendingIntent
                )
                .build()

            try {
                manager.notify(PROACTIVE_NOTIFICATION_ID, notification)
                synchronized(pendingQueue) { notifiedInsightId = insight.id }
                AlertRecording.markDelivered(insight, notified = manager.areNotificationsEnabled())
            } catch (e: SecurityException) {
                TrekoLogBuffer.w("TrekoService", "Notification permission missing for proactive alert: ${e.message}")
            }
        }

        /**
         * An insight that could not be spoken now: queued for later. A camera alert has its own
         * high-importance notification with the picture; anything else gets the proactive one.
         */
        private fun holdInsightForLater(context: Context, insight: ProactiveInsight) {
            enqueuePendingInsight(insight)
            if (insight.category != ProactiveCategory.CAMERA_ANOMALY) {
                postProactiveNotification(context, insight)
            } else {
                // Camera alerts can push the briefing the notification shows out of the queue.
                refreshProactiveNotification(context)
            }
        }

        private fun cancelProactiveNotification(context: Context) {
            synchronized(pendingQueue) { notifiedInsightId = null }
            val manager = context.applicationContext.getSystemService(NotificationManager::class.java) ?: return
            manager.cancel(PROACTIVE_NOTIFICATION_ID)
        }

        fun triggerProactiveCheckNow(context: Context, forceSpeak: Boolean? = null) {
            val appCtx = context.applicationContext
            val active = instance
            if (active != null) {
                active.serviceScope.launch {
                    active.runProactiveCheck(force = true, overrideSpeak = forceSpeak)
                }
                return
            }
            if (_isProactiveCheckRunning.value) return
            proactiveFallbackScope.launch {
                _isProactiveCheckRunning.value = true
                try {
                    val prefs = TrekoPreferences(appCtx)
                    val manager = getOrCreateProactiveManager(appCtx)
                    val found = manager.evaluateNow(
                        includeCameraScan = prefs.proactiveCameraAnomalyEnabled,
                        force = true,
                        assistantName = resolveAssistantName(appCtx)
                    ) ?: return@launch
                    TrekoAlertLog.init(appCtx)
                    var insight = AlertRecording.recordInsight(found, trace = null)
                    if (insight.category == ProactiveCategory.CAMERA_ANOMALY) {
                        val photo = AlertRecording.announceCameraAlert(
                            appCtx, insight, proactiveFallbackScope, sharedHaClient(appCtx), trace = null
                        )
                        insight = AlertRecording.withPhotoNote(appCtx, insight, photo)
                    }
                    val shouldSpeak = (forceSpeak ?: prefs.proactiveSpeakOutLoud) && hasMicPermission(appCtx)
                    if (shouldSpeak) {
                        pendingProactiveDelivery = insight
                        startService(appCtx)
                    } else {
                        holdInsightForLater(appCtx, insight)
                    }
                } finally {
                    _isProactiveCheckRunning.value = false
                }
            }
        }

        /** Speaks pending insight [insightId], or the most important one. */
        fun consumePendingProactiveInsight(context: Context, insightId: String? = null) {
            val appCtx = context.applicationContext
            cancelProactiveNotification(appCtx)
            val insight = takePendingInsight(insightId) ?: return
            val active = instance
            if (active != null) {
                active.serviceScope.launch {
                    active.deliverProactiveInsightByVoice(insight, fromUserTap = true)
                }
            } else if (hasMicPermission(appCtx)) {
                pendingProactiveDelivery = insight
                TrekoPreferences(appCtx).isMicMuted = false
                _isMuted.value = false
                startService(appCtx)
            } else {
                addChatMessage(resolveAssistantName(appCtx), "💬 ${insight.title}: ${insight.summaryText}")
                _subtitleText.value = insight.summaryText
            }
        }

        /**
         * Drops pending insight [insightId] (or the most important one; a stale id drops
         * nothing); others stay queued and the notification moves to the next briefing.
         */
        fun dismissPendingProactiveInsight(context: Context, insightId: String? = null) {
            takePendingInsight(insightId)
            refreshProactiveNotification(context.applicationContext)
        }

        fun startConversation(context: Context, keyword: String = "Treko") {
            val active = instance
            if (active != null) {
                pendingStartConversationKeyword = null
                if (_isMuted.value) {
                    active.applyMuteState(muted = false)
                }
                active.beginConversation(keyword)
            } else if (hasMicPermission(context)) {
                TrekoPreferences(context).isMicMuted = false
                _isMuted.value = false
                pendingStartConversationKeyword = keyword
                startService(context)
            } else {
                TrekoLogBuffer.w("TrekoService", "Cannot start conversation: RECORD_AUDIO permission missing.")
            }
        }

        fun setMuted(context: Context, muted: Boolean) {
            TrekoPreferences(context).isMicMuted = muted
            _isMuted.value = muted
            instance?.applyMuteState(muted)
        }

        fun toggleMute(context: Context) {
            setMuted(context, !_isMuted.value)
        }

        fun setCinemaMode(context: Context, enabled: Boolean) {
            TrekoPreferences(context).cinemaModeEnabled = enabled
            _isCinemaMode.value = enabled
            instance?.applyLatestPreferences()
        }

        fun toggleCinemaMode(context: Context) {
            setCinemaMode(context, !_isCinemaMode.value)
        }

        fun stopConversation() {
            pendingStartConversationKeyword = null
            val active = instance
            if (active != null) {
                active.abortConversation()
            } else {
                _serviceState.value = TrekoState.IDLE
                _currentEmotion.value = TrekoEmotion.RELAX
            }
        }

        fun updateWakeWordConfig(context: Context, keyword: String, boost: Float, threshold: Float) {
            _assistantName.value = formatAssistantName(keyword, context.applicationContext.getString(R.string.wake_word))
            val current = instance
            if (current != null) {
                current.wakeWordDetector?.updateKeywords(
                    newKeyword = keyword,
                    boost = boost.coerceIn(1.0f, 5.0f),
                    threshold = threshold.coerceIn(0.01f, 0.20f)
                )
                current.updateNotification(_serviceState.value)
            } else {
                TrekoLogBuffer.d("TrekoService", "Service inactive when updating KWS, starting if permitted...")
                startService(context)
            }
        }

        fun reloadConfiguration(context: Context) {
            _assistantName.value = resolveAssistantName(context)
            val current = instance
            if (current != null) {
                current.applyLatestPreferences()
            } else {
                startService(context)
            }
        }

        fun reconnectWebSocket() {
            instance?.let { svc ->
                svc.wsClient?.disconnect()
                _connectionStatus.value = WsConnectionStatus.CONNECTING
                svc.wsClient?.connect()
            }
        }

        fun getKwsDiagnostics(): TrekoWakeWordDetector.KwsDiagnostics? {
            return instance?.wakeWordDetector?.getDiagnostics()
        }

        fun getDenoiserDiagnostics(): NeuralSpeechDenoiser.DenoiserDiagnostics? {
            return instance?.speechDenoiser?.getDiagnostics()
        }

        fun getBiometricDiagnostics(): SpeakerBiometricEngine.BiometricDiagnostics? {
            return instance?.speakerBiometricEngine?.getDiagnostics()
        }

        fun getActiveDetector(): TrekoWakeWordDetector? = instance?.wakeWordDetector

        fun getActiveDenoiser(): NeuralSpeechDenoiser? = instance?.speechDenoiser

        fun getActiveBiometricEngine(): SpeakerBiometricEngine? = instance?.speakerBiometricEngine

        private val _activeCameraFeed = MutableStateFlow<CameraFeedState?>(null)
        val activeCameraFeed: StateFlow<CameraFeedState?> = _activeCameraFeed.asStateFlow()

        private val _activeMultiCameraGrid = MutableStateFlow<MultiCameraGridState?>(null)
        val activeMultiCameraGrid: StateFlow<MultiCameraGridState?> = _activeMultiCameraGrid.asStateFlow()

        fun showCameraFeed(state: CameraFeedState) {
            _activeCameraFeed.value = state
        }

        /**
         * Opens the live view of the camera an alert is about (alerts screen). Uses the camera
         * group of that entity when Home Assistant knows one, so its live stream is used.
         * False when Home Assistant is not configured or there is no camera.
         */
        fun showAlertCamera(context: Context, cameraEntityId: String, fallbackName: String): Boolean {
            val prefs = TrekoPreferences(context.applicationContext)
            val haUrl = prefs.haUrl.trim()
            val haToken = prefs.haToken.trim()
            if (haUrl.isBlank() || haToken.isBlank() || cameraEntityId.isBlank()) return false
            val scope = instance?.serviceScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch(Dispatchers.IO) {
                val group = try {
                    HomeAssistantClient(haUrl, haToken).getDeduplicatedCameras().find {
                        it.primaryEntityId == cameraEntityId ||
                            it.snapshotEntityId == cameraEntityId ||
                            it.liveEntityId == cameraEntityId
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    TrekoLogBuffer.w("TrekoService", "Alert camera lookup failed: ${e.message}")
                    null
                }
                _activeMultiCameraGrid.value = null
                _activeCameraFeed.value = CameraFeedState(
                    entityId = group?.snapshotEntityId?.ifBlank { null } ?: cameraEntityId,
                    friendlyName = group?.friendlyName?.ifBlank { null } ?: fallbackName,
                    // Blank: the viewer builds the camera proxy URL from haUrl.
                    snapshotUrl = group?.snapshotUrl.orEmpty(),
                    haUrl = haUrl,
                    haToken = haToken,
                    isLive = true,
                    liveEntityId = group?.liveEntityId
                )
            }
            return true
        }

        fun dismissCameraFeed(closeGrid: Boolean = false) {
            _activeCameraFeed.value = null
            if (closeGrid) {
                _activeMultiCameraGrid.value = null
            }
        }

        fun dismissMultiCameraGrid() {
            _activeMultiCameraGrid.value = null
        }

        fun setMultiCameraLiveAll(enabled: Boolean) {
            val current = _activeMultiCameraGrid.value ?: return
            _activeMultiCameraGrid.value = current.copy(
                isLiveAll = enabled,
                cameras = current.cameras.map { it.copy(isLive = enabled) },
                lastUpdated = System.currentTimeMillis()
            )
        }

        fun openMultiCameraPanel(context: Context, liveAll: Boolean = false) {
            val prefs = TrekoPreferences(context.applicationContext)
            val haUrl = prefs.haUrl.trim()
            val haToken = prefs.haToken.trim()
            if (haUrl.isBlank() || haToken.isBlank()) {
                _activeCameraFeed.value = null
                _activeMultiCameraGrid.value = MultiCameraGridState(
                    cameras = emptyList(),
                    haUrl = "",
                    haToken = "",
                    isLiveAll = liveAll
                )
                return
            }
            val loadingText = "Cargando cámaras..."
            _subtitleText.value = loadingText
            val scope = instance?.serviceScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch(Dispatchers.IO) {
                try {
                    val groups = HomeAssistantClient(haUrl, haToken).getDeduplicatedCameras()
                    val feeds = groups.map { group ->
                        CameraFeedState(
                            entityId = group.snapshotEntityId.ifBlank { group.primaryEntityId },
                            friendlyName = group.friendlyName,
                            snapshotUrl = group.snapshotUrl,
                            haUrl = haUrl,
                            haToken = haToken,
                            isLive = liveAll,
                            liveEntityId = group.liveEntityId
                        )
                    }
                    _activeCameraFeed.value = null
                    _activeMultiCameraGrid.value = MultiCameraGridState(
                        cameras = feeds,
                        haUrl = haUrl,
                        haToken = haToken,
                        isLiveAll = liveAll
                    )
                    if (_subtitleText.value == loadingText) {
                        _subtitleText.value = ""
                    }
                } catch (e: Exception) {
                    TrekoLogBuffer.e("TrekoService", "Failed to open multi-camera panel: ${e.message}")
                    _activeCameraFeed.value = null
                    _activeMultiCameraGrid.value = MultiCameraGridState(
                        cameras = emptyList(),
                        haUrl = haUrl,
                        haToken = haToken,
                        isLiveAll = liveAll
                    )
                    if (_subtitleText.value == loadingText) {
                        _subtitleText.value = ""
                    }
                }
            }
        }

        fun analyzeCameraFromUi(feed: CameraFeedState) {
            if (feed.haUrl.isBlank() || feed.haToken.isBlank()) {
                _subtitleText.value = "Configura Home Assistant para analizar cámaras."
                return
            }
            _activeMultiCameraGrid.value = null
            _activeCameraFeed.value = null
            _currentEmotion.value = TrekoEmotion.PIENSA
            _subtitleText.value = "Analizando cámara '${feed.friendlyName}'..."
            val scope = instance?.serviceScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch(Dispatchers.IO) {
                try {
                    val client = HomeAssistantClient(feed.haUrl, feed.haToken)
                    val targetEntity = feed.entityId.ifBlank { feed.liveEntityId ?: "" }
                    val analysis = client.analyzeCameraWithLlmVision(
                        targetEntity,
                        "Describe brevemente en español qué se observa en esta cámara."
                    )
                    val summary = if (!analysis.isNullOrBlank()) {
                        analysis.trim()
                    } else {
                        "No se obtuvo análisis visual para '${feed.friendlyName}'."
                    }
                    _currentEmotion.value = if (_serviceState.value == TrekoState.IDLE) {
                        if (_isMuted.value) TrekoEmotion.SLEEPY else TrekoEmotion.RELAX
                    } else {
                        TrekoEmotion.FELIZ_ABIERTO
                    }
                    _subtitleText.value = "${feed.friendlyName}: $summary"
                    addChatMessage(_assistantName.value, "Análisis [${feed.friendlyName}]: $summary")
                } catch (e: Exception) {
                    TrekoLogBuffer.e("TrekoService", "UI camera vision error: ${e.message}")
                    _currentEmotion.value = if (_isMuted.value) TrekoEmotion.SLEEPY else TrekoEmotion.RELAX
                    _subtitleText.value = "Error analizando '${feed.friendlyName}': ${e.message}"
                }
            }
        }

        fun hasMicPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun startService(context: Context) {
            if (!hasMicPermission(context)) {
                TrekoLogBuffer.w("TrekoService", "Skipping startService: RECORD_AUDIO permission not granted.")
                return
            }
            val intent = Intent(context, TrekoAssistantService::class.java)
            context.startForegroundService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, TrekoAssistantService::class.java)
            context.stopService(intent)
        }
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (_serviceState.value != TrekoState.IDLE) {
                    TrekoLogBuffer.i(TAG, "Audio focus lost ($focusChange); aborting active turn.")
                    abortConversation()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        NativeAudioEngine.headsetDeviceFilter = BluetoothHeadsetFilter(this)::allows
        NativeAudioEngine.attachAudioManager(audioManager)
        createNotificationChannel()

        val initialNotification = buildNotification(TrekoState.IDLE)
        if (!hasMicPermission(this)) {
            TrekoLogBuffer.w(TAG, "RECORD_AUDIO missing on service start; stopping cleanly.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Must call startForeground before stopSelf if started via startForegroundService
                try {
                    startForeground(NOTIFICATION_ID, initialNotification)
                } catch (_: Exception) {
                }
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
            stopSelf()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        isServiceRunning = true
        TrekoLogBuffer.i(TAG, "TrekoAssistantService started in foreground.")
        startCameraWatches()
        meditation = MeditationController(this, serviceScope, meditationHost).also {
            // A session the app could not finish (killed mid-way) left lights or volume changed.
            it.restorePendingOnStart()
        }

        val prefs = TrekoPreferences(this)
        _isMuted.value = prefs.isMicMuted

        TimerAlarms.install(this)
        TrekoAlertLog.init(this)
        _isCinemaMode.value = prefs.cinemaModeEnabled
        syncPolicyPreferences(prefs)

        // 1. Start audio capture first unless mic is muted so captureAudioSessionId is populated
        if (!prefs.isMicMuted) {
            NativeAudioEngine.startRecording(serviceScope, preferVoiceCommunication = prefs.audioEchoModeEnabled)
        } else {
            _currentEmotion.value = TrekoEmotion.SLEEPY
        }

        // 2. Initialize Opus codec sharing captureAudioSessionId when available
        val codec = OpusAudioCodec(
            inputSampleRate = 16000,
            outputSampleRate = 48000,
            audioSessionId = NativeAudioEngine.captureAudioSessionId
        ).apply {
            onRenderPcmWritten = { pcm48k, count, playoutDelayMs ->
                NativeAudioEngine.feedSoftwareAecRender(pcm48k, count, playoutDelayMs)
            }
        }
        opusCodec = codec
        codec.setPreferredOutputDevice(NativeAudioEngine.preferredOutputDevice)
        NativeAudioEngine.onPreferredOutputDeviceChanged = { dev ->
            opusCodec?.setPreferredOutputDevice(dev)
        }
        playbackCollectorJob = serviceScope.launch {
            codec.playbackLevel.collect { level ->
                _playbackAudioLevel.value = level
            }
        }

        // 3. Initialize Xiaozhi WebSocket client
        initWebSocketClient(prefs)

        // 4. Initialize Sherpa-ONNX wake word detector
        initWakeWordDetector(prefs)

        // 5. Initialize neural speech denoiser (GTCRN) & biometric speaker extractor (CAM++) off main thread
        serviceScope.launch(Dispatchers.IO) {
            initSpeechEnhancementAndBiometrics()
        }

        // 6. Wire PCM pipeline
        _assistantName.value = resolveAssistantName(this)
        setupAudioPipeline()

        // Refresh notification if started muted
        if (prefs.isMicMuted) {
            updateNotification(TrekoState.IDLE)
        }

        // 7. Execute any conversation start requested while the service was starting
        pendingStartConversationKeyword?.let { kw ->
            pendingStartConversationKeyword = null
            if (_isMuted.value) {
                applyMuteState(muted = false)
            }
            beginConversation(kw)
        }

        // 8. Start proactive companion & sentinel periodic loop, and flush any pending proactive tap
        startProactiveLoop()
        pendingProactiveDelivery?.let { insight ->
            pendingProactiveDelivery = null
            serviceScope.launch {
                deliverProactiveInsightByVoice(insight, fromUserTap = true)
            }
        }
    }

    private fun initSpeechEnhancementAndBiometrics() {
        val latestPrefs = TrekoPreferences(this)
        val denoiser = NeuralSpeechDenoiser(this).apply {
            initialize()
            isEnabled = latestPrefs.speechDenoiserEnabled
        }
        speechDenoiser = denoiser

        val bioEngine = SpeakerBiometricEngine(this).apply {
            initialize()
            loadFromPreferences(latestPrefs)
        }
        speakerBiometricEngine = bioEngine
        effectiveBiometricGateEnabled = bioEngine.isVerificationActive()
        TrekoLogBuffer.i(
            TAG,
            "Audio protection ready: denoiser=${denoiser.isInitialized}, biometric=${bioEngine.isInitialized}, enrolled=${bioEngine.enrolledProfile != null}."
        )
    }

    private fun syncPolicyPreferences(prefs: TrekoPreferences) {
        _isCinemaMode.value = prefs.cinemaModeEnabled
        effectiveAutoFollowUpEnabled = prefs.effectiveAutoFollowUp()
        effectiveVoiceBargeInEnabled = prefs.effectiveVoiceBargeIn()
        effectiveFullDuplexMode = prefs.effectiveFullDuplexMode()
        NativeAudioEngine.softwareAecEnabled = prefs.softwareAec3Enabled
        wsClient?.advertiseAecFeature = prefs.softwareAec3Enabled
        nearFieldGate.nearFieldEnabled = prefs.nearFieldGateEnabled
        nearFieldGate.minSnrDb = prefs.effectiveNearFieldMinSnrDb()
        nearFieldGate.endOfSpeechSilenceMs = if (prefs.cinemaModeEnabled) 950L else 1150L
        bargeInGate.nearFieldEnabled = true
        bargeInGate.minSnrDb = (prefs.effectiveNearFieldMinSnrDb() + 1.5f).coerceIn(5.0f, 18.0f)
        complementaryQueue.configurePolicy(
            minSpeechMs = prefs.complementaryMinSpeechMs,
            nearFieldEnabled = prefs.nearFieldGateEnabled,
            minSnrDb = prefs.effectiveNearFieldMinSnrDb(),
            biometricEnabled = effectiveBiometricGateEnabled
        )
    }

    private fun applyMuteState(muted: Boolean) {
        _isMuted.value = muted
        if (muted) {
            TrekoLogBuffer.i(TAG, "Microphone muted by user.")
            if (_serviceState.value == TrekoState.LISTENING) {
                listeningTurnGeneration++
                biometricCheckJob?.cancel()
                val shouldNotifyWs = biometricTurnGate.state == BiometricTurnGate.TurnAuthState.DISABLED ||
                    biometricTurnGate.state == BiometricTurnGate.TurnAuthState.VERIFIED
                biometricTurnGate.reset()
                nearFieldGate.reset()
                speechDenoiser?.reset()
                clearPendingOpusPackets()
                opusCodec?.resetEncoder()
                if (shouldNotifyWs) {
                    wsClient?.abortSpeaking(reason = "user_muted")
                }
                transitionState(TrekoState.IDLE, TrekoEmotion.SLEEPY)
                _subtitleText.value = ""
            } else if (_serviceState.value == TrekoState.IDLE) {
                _currentEmotion.value = TrekoEmotion.SLEEPY
            }
            if (!isTrainingMode) {
                NativeAudioEngine.stopRecording()
            }
            updateNotification(_serviceState.value)
        } else {
            TrekoLogBuffer.i(TAG, "Microphone unmuted by user.")
            val prefs = TrekoPreferences(this)
            speechDenoiser?.reset()
            nearFieldGate.reset()
            biometricTurnGate.reset()
            if (!NativeAudioEngine.isRecording && hasMicPermission(this)) {
                NativeAudioEngine.startRecording(serviceScope, preferVoiceCommunication = prefs.audioEchoModeEnabled)
                val newSessionId = NativeAudioEngine.captureAudioSessionId
                if (newSessionId > 0) {
                    opusCodec?.updateAudioSessionId(newSessionId)
                }
            }
            if (_serviceState.value == TrekoState.IDLE) {
                _currentEmotion.value = TrekoEmotion.RELAX
                _subtitleText.value = ""
            }
            updateNotification(_serviceState.value)
        }
    }

    private fun transitionState(newState: TrekoState, emotion: TrekoEmotion? = null) {
        val prev = _serviceState.value
        _serviceState.value = newState
        if (emotion != null) {
            _currentEmotion.value = emotion
        }

        NativeAudioEngine.setPlaybackActive(newState == TrekoState.SPEAKING)

        when (newState) {
            TrekoState.LISTENING, TrekoState.SPEAKING -> requestTransientAudioFocus()
            TrekoState.IDLE, TrekoState.ERROR -> {
                hasTurnEmotion = false
                bargeInVoicedFrames = 0
                _fullDuplexIndicator.value = FullDuplexIndicator.INACTIVE
                abandonAudioFocus()
            }
            TrekoState.THINKING -> Unit
        }

        if (prev != newState) {
            updateNotification(newState)
        }
    }

    private fun requestTransientAudioFocus() {
        if (hasAudioFocus) return
        val am = audioManager ?: return
        val req = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(audioFocusListener)
            .build()
            .also { audioFocusRequest = it }

        val granted = am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        hasAudioFocus = granted
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        val am = audioManager ?: return
        audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        hasAudioFocus = false
    }

    private fun initWebSocketClient(prefs: TrekoPreferences) {
        activeWsUrl = prefs.xiaozhiWsUrl
        activeWsToken = prefs.xiaozhiToken
        _connectionStatus.value = WsConnectionStatus.CONNECTING

        val client = XiaozhiWebSocketClient(
            serverUrl = activeWsUrl,
            macAddress = prefs.macAddress,
            clientId = prefs.clientId,
            token = activeWsToken
        ).apply {
            advertiseAecFeature = prefs.softwareAec3Enabled
            availableSkills = prefs.detectedSkills
        }

        client.onSessionStarted = { sid ->
            _activeSessionId.value = sid
            _connectionStatus.value = WsConnectionStatus.CONNECTED
            refreshHomeAssistantSkills()
            flushPendingOpusPackets(client)
        }

        client.onConnectionStateChanged = { connState ->
            when (connState) {
                TrekoState.ERROR -> {
                    _connectionStatus.value = WsConnectionStatus.ERROR
                    _activeSessionId.value = null
                }
                TrekoState.IDLE -> {
                    _connectionStatus.value = if (client.isConnected) {
                        WsConnectionStatus.CONNECTED
                    } else {
                        WsConnectionStatus.DISCONNECTED
                    }
                    if (!client.isConnected) {
                        _activeSessionId.value = null
                    }
                }
                else -> Unit
            }
        }

        client.onError = { errMsg ->
            TrekoLogBuffer.w(TAG, "WebSocket error: $errMsg")
            _connectionStatus.value = WsConnectionStatus.ERROR
            if (_serviceState.value != TrekoState.IDLE) {
                _subtitleText.value = errMsg
            }
        }

        client.onServerAlert = { status, alertMsg ->
            TrekoLogBuffer.w(TAG, "Xiaozhi server alert ($status): $alertMsg")
            activeMeditationCue?.let { endMeditationCue(it, abort = false) }
            val pendingVoice = activeVoiceProactiveInsight
            if (pendingVoice != null && _serviceState.value == TrekoState.THINKING) {
                activeVoiceProactiveInsight = null
                cancelThinkingWatchdog()
                proactiveTtsFallbackJob?.cancel()
                val reopenMic = activeVoiceProactiveFromUserTap && !_isMuted.value
                serviceScope.launch {
                    speakLocalProactiveFallback(pendingVoice, openMicAfter = reopenMic)
                }
            }
        }

        client.onSttReceived = { transcript ->
            val clean = transcript.trim()
            if (clean.equals("谢谢观看", ignoreCase = true) || clean.contains("观看") || clean.isBlank()) {
                Log.d(TAG, "Ignoring silent STT hallucination: $clean")
            } else {
                val youPrefix = getString(R.string.speaker_you)
                _subtitleText.value = "$youPrefix: $clean"
                addChatMessage("user", clean)

                val isSleepIntent = SleepIntentDetector(
                    assistantNames = listOf("Treko", _assistantName.value)
                ).isSleepIntent(clean)

                if (isSleepIntent) {
                    TrekoLogBuffer.i(TAG, "Sleep intent detected ('$clean').")
                    // "Silencio" during a meditation: it must not resume by itself 12 s later.
                    meditation?.onSleepIntent()
                    cancelThinkingWatchdog()
                    serviceScope.launch {
                        client.stopListening()
                        client.abortSpeaking(reason = "sleep_intent")
                        val goodbye = getString(R.string.status_goodbye)
                        val name = _assistantName.value
                        transitionState(TrekoState.THINKING, TrekoEmotion.SLEEPY)
                        _subtitleText.value = "$name: $goodbye"
                        addChatMessage(name, goodbye)
                        delay(1200)
                        transitionState(TrekoState.IDLE, TrekoEmotion.RELAX)
                        _subtitleText.value = ""
                    }
                } else {
                    transitionState(TrekoState.THINKING, TrekoEmotion.PIENSA)
                    startThinkingTimeout()
                }
            }
        }

        client.onLlmReceived = { text, emotionStr ->
            if (_serviceState.value == TrekoState.THINKING) {
                conversationWatchdog.onLlmReceived(System.currentTimeMillis())
                scheduleThinkingWatchdog()
            }
            if (text.isNotBlank()) {
                _subtitleText.value = "${_assistantName.value}: $text"
            }
            if (!emotionStr.isNullOrBlank()) {
                hasTurnEmotion = true
                _currentEmotion.value = TrekoEmotion.fromString(emotionStr)
            }
        }

        client.onTtsStateChanged = { state, text ->
            when (state) {
                "start" -> {
                    val cur = _serviceState.value
                    val isLateReply = cur == TrekoState.IDLE &&
                        conversationWatchdog.acceptsLateTts(System.currentTimeMillis(), wsClient?.sessionId)
                    if (isLateReply) {
                        conversationWatchdog.consumeLateTtsGrace()
                        TrekoLogBuffer.i(TAG, "Playing late server TTS that arrived after the thinking watchdog.")
                    }
                    if ((cur == TrekoState.IDLE || cur == TrekoState.ERROR) && !isLateReply) {
                        suppressIdleTts = true
                        TrekoLogBuffer.i(TAG, "Ignoring unsolicited server TTS in state=$cur.")
                        wsClient?.abortSpeaking(reason = "unsolicited_tts_while_idle")
                    } else {
                        suppressIdleTts = false
                        activeMeditationCue?.started = true
                        proactiveTtsFallbackJob?.cancel()
                        cancelThinkingWatchdog()
                        ttsDrainingJob?.cancel()
                        complementaryBioJob?.cancel()
                        isComplementaryBioCheckInFlight = false
                        bargeInVoicedFrames = 0
                        val nowMs = System.currentTimeMillis()
                        speakingStartTimestamp = nowMs
                        bargeInGate.reset(nowMs)
                        complementaryQueue.reset(
                            biometricEnabled = effectiveBiometricGateEnabled,
                            nowMs = nowMs
                        )
                        if (effectiveFullDuplexMode == FullDuplexMode.COMPLEMENTARY &&
                            !complementaryCaptureAllowed()
                        ) {
                            TrekoLogBuffer.d(
                                TAG,
                                "Remarks during speech off: loudspeaker without voice verification would capture Treko's own echo."
                            )
                        }
                        _fullDuplexIndicator.value = FullDuplexIndicator.INACTIVE
                        val emotionToUse = if (hasTurnEmotion) null else TrekoEmotion.FELIZ_ABIERTO
                        transitionState(TrekoState.SPEAKING, emotionToUse)
                    }
                }
                "sentence_start" -> {
                    if (!suppressIdleTts && _serviceState.value != TrekoState.IDLE && _serviceState.value != TrekoState.ERROR) {
                        proactiveTtsFallbackJob?.cancel()
                        if (_serviceState.value == TrekoState.THINKING) {
                            activeMeditationCue?.started = true
                            cancelThinkingWatchdog()
                            speakingStartTimestamp = System.currentTimeMillis()
                            val emotionToUse = if (hasTurnEmotion) null else TrekoEmotion.FELIZ_ABIERTO
                            transitionState(TrekoState.SPEAKING, emotionToUse)
                        }
                        if (!text.isNullOrBlank() && !text.trimStart().startsWith("% ")) {
                            activeVoiceProactiveInsight = null
                            val name = _assistantName.value
                            _subtitleText.value = "$name: $text"
                            addChatMessage(name, text)
                        }
                    }
                }
                "stop" -> {
                    if (suppressIdleTts) {
                        suppressIdleTts = false
                    } else {
                        ttsDrainingJob?.cancel()
                        ttsDrainingJob = serviceScope.launch {
                            opusCodec?.waitUntilPlaybackDrained(1400L) ?: delay(650)
                            if (isComplementaryBioCheckInFlight) {
                                // Let the voice check on a queued remark finish before deciding.
                                withTimeoutOrNull(900L) { complementaryBioJob?.join() }
                            }
                            val cue = activeMeditationCue
                            if (cue != null && _serviceState.value == TrekoState.SPEAKING) {
                                // A meditation cue ends in silence: no follow-up listening, wake word armed.
                                activeMeditationCue = null
                                complementaryQueue.onPlaybackFinished()
                                _fullDuplexIndicator.value = FullDuplexIndicator.INACTIVE
                                transitionState(TrekoState.IDLE, TrekoEmotion.RELAX)
                                _subtitleText.value = ""
                                cue.done.complete(cue.consumed)
                            } else if (_serviceState.value == TrekoState.SPEAKING) {
                                val flushPlan = complementaryQueue.onPlaybackFinished()
                                val client = wsClient
                                when {
                                    flushPlan.decision == FullDuplexComplementaryQueue.FlushDecision.SEND_COMPLETE_UTTERANCE &&
                                        flushPlan.pcmFrames.isNotEmpty() &&
                                        !_isMuted.value &&
                                        client?.isConnected == true -> {
                                        dispatchQueuedComplementaryTurn(flushPlan.pcmFrames, client)
                                    }
                                    flushPlan.decision == FullDuplexComplementaryQueue.FlushDecision.HANDOFF_LIVE_LISTENING &&
                                        flushPlan.pcmFrames.isNotEmpty() &&
                                        !_isMuted.value &&
                                        client?.isConnected == true -> {
                                        startLiveHandoffListening(flushPlan.pcmFrames)
                                    }
                                    !_isMuted.value && effectiveAutoFollowUpEnabled && client?.isConnected == true &&
                                        meditation?.holdsMicClosed() != true -> {
                                        _fullDuplexIndicator.value = FullDuplexIndicator.INACTIVE
                                        startActiveListening(prompt = getString(R.string.status_follow_up))
                                    }
                                    else -> {
                                        _fullDuplexIndicator.value = FullDuplexIndicator.INACTIVE
                                        transitionState(
                                            TrekoState.IDLE,
                                            if (_isMuted.value) TrekoEmotion.SLEEPY else TrekoEmotion.RELAX
                                        )
                                        _subtitleText.value = ""
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        client.onAudioPacketReceived = { opusBytes ->
            if (!suppressIdleTts && _serviceState.value != TrekoState.IDLE && _serviceState.value != TrekoState.ERROR) {
                opusCodec?.decodeAndPlay(opusBytes)
            }
        }

        client.onMcpToolCall = { toolName, args ->
            handleMcpToolCall(toolName, args, prefs)
        }

        client.onToolCallStarted = { toolName ->
            val token = conversationWatchdog.onToolStarted(toolName, System.currentTimeMillis())
            if (_serviceState.value == TrekoState.THINKING) scheduleThinkingWatchdog()
            token
        }

        client.onToolCallFinished = { toolName, token, durationMs, result ->
            TrekoLogBuffer.d(
                TAG,
                "MCP tool '$toolName' finished in ${durationMs}ms: ${LogText.toolResult(toolName, result, TOOL_RESULT_LOG_CHARS)}"
            )
            (token as? Long)?.let { conversationWatchdog.onToolFinished(it, System.currentTimeMillis()) }
            if (_serviceState.value == TrekoState.THINKING) scheduleThinkingWatchdog()
        }

        wsClient = client
        client.connect()
    }

    private fun applyLatestPreferences() {
        val prefs = TrekoPreferences(this)
        _assistantName.value = resolveAssistantName(this)
        syncPolicyPreferences(prefs)
        speechDenoiser?.isEnabled = prefs.speechDenoiserEnabled
        speakerBiometricEngine?.let { bio ->
            bio.loadFromPreferences(prefs)
            effectiveBiometricGateEnabled = bio.isVerificationActive()
        }
        wakeWordDetector?.loadFromPreferences(prefs)
        wakeWordDetector?.updateKeywords(
            newKeyword = prefs.customWakeWord,
            boost = prefs.wakeWordBoost.coerceIn(1.0f, 5.0f),
            threshold = prefs.wakeWordSensitivity.coerceIn(0.01f, 0.20f)
        )
        if (prefs.xiaozhiWsUrl != activeWsUrl || prefs.xiaozhiToken != activeWsToken) {
            TrekoLogBuffer.i(TAG, "Xiaozhi settings changed; reconnecting WebSocket client.")
            wsClient?.release()
            _activeSessionId.value = null
            initWebSocketClient(prefs)
        }
        if (proactiveLoopJob?.isActive != true) {
            startProactiveLoop()
        }
        refreshHomeAssistantSkills(force = true)
        cameraWatch?.start()
        updateNotification(_serviceState.value)
    }

    var cameraWatch: CameraWatchCoordinator? = null
        private set
    private var watchHaClient: HomeAssistantClient? = null
    private var watchHaKey: String? = null

    /** One HA client per configuration, so the live event stream is not rebuilt needlessly. */
    @Synchronized
    private fun currentWatchHaClient(): HomeAssistantClient? {
        val prefs = TrekoPreferences(this)
        val haUrl = prefs.haUrl.trim()
        val haToken = prefs.haToken.trim()
        if (haUrl.isBlank() || haToken.isBlank()) {
            watchHaClient = null
            watchHaKey = null
            return null
        }
        val key = "$haUrl\n$haToken"
        if (key != watchHaKey) {
            watchHaClient = HomeAssistantClient(haUrl, haToken)
            watchHaKey = key
        }
        return watchHaClient
    }

    private fun startCameraWatches() {
        val coordinator = CameraWatchCoordinator(
            context = this,
            scope = serviceScope,
            haClientProvider = { currentWatchHaClient() },
            speak = { text -> speakWatchAlert(text) },
            addChat = { text -> addChatMessage(_assistantName.value, text) },
            sentinelEnabled = {
                // Its own setting: turning off the periodic briefings keeps camera security on.
                val p = TrekoPreferences(this)
                p.cameraSentinelEnabled && TrekoSkills.FRIGATE in p.detectedSkills &&
                    TrekoSkills.LLM_VISION in p.detectedSkills
            },
            onSentinelDetection = { sensor, trace ->
                TrekoLogBuffer.i(
                    TAG,
                    "Frigate saw ${sensor.objectType} at ${sensor.areaName}; running the camera sentinel now."
                )
                // Have a Xiaozhi session ready by the time a verdict may need to be spoken: the
                // handshake overlaps the vision call instead of delaying the alert.
                wsClient?.let { client ->
                    if (!client.isConnected) {
                        _connectionStatus.value = WsConnectionStatus.CONNECTING
                        client.connect()
                    }
                }
                // Another check in progress: wait for it rather than drop this detection.
                var waitedMs = 0L
                while (_isProactiveCheckRunning.value && waitedMs < 90_000L) {
                    delay(1_000L)
                    waitedMs += 1_000L
                }
                if (waitedMs > 0L) trace.stage("wait", waitedMs)
                if (_isProactiveCheckRunning.value) {
                    trace.decide("skipped", "another proactive check was still running after ${waitedMs / 1000}s")
                    false
                } else {
                    runProactiveCheck(force = false, camerasOnly = true)
                }
            }
        )
        cameraWatch = coordinator
        serviceScope.launch { coordinator.activeWatches.collect { _activeWatches.value = it } }
        serviceScope.launch {
            var hadWatches = false
            coordinator.activeAreas.collect { areas ->
                _activeWatchAreas.value = areas
                updateNotification(_serviceState.value)
                // Kept alive only for the watches: once they end, leave as the user set it.
                if (hadWatches && areas.isEmpty() && !isUiAlive &&
                    !TrekoPreferences(this@TrekoAssistantService).isAlwaysListeningInBackground
                ) {
                    TrekoLogBuffer.i(TAG, "Last camera watch ended with the app closed; stopping service.")
                    stopSelf()
                }
                hadWatches = areas.isNotEmpty()
            }
        }
        coordinator.start()
    }

    /** Spanish (es-MX, es) by default; the phone's language first for localized texts. */
    private fun setLocalTtsLanguage(tts: TextToSpeech, preferPhoneLanguage: Boolean) {
        val spanish = listOf(Locale("es", "MX"), Locale("es"))
        val order = if (preferPhoneLanguage) listOf(Locale.getDefault()) + spanish else spanish
        val chosen = order.firstOrNull { tts.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE } ?: Locale("es")
        if (tts.language != chosen) tts.language = chosen
    }

    /**
     * Short spoken alert with the phone's own voice, only when Treko is not mid-conversation.
     * Returns true only when the text was handed to the phone's voice.
     */
    private suspend fun speakWatchAlert(text: String): Boolean {
        // An urgent watch alert gets through a meditation: pause it, speak, it resumes by itself.
        if (meditation?.pauseForAlert() == true) {
            cancelMeditationCue()
            withTimeoutOrNull(3_000L) { while (_serviceState.value != TrekoState.IDLE) delay(100L) }
        }
        if (_serviceState.value != TrekoState.IDLE || isTrainingMode) return false
        val tts = ensureLocalTtsReady() ?: return false
        // Watch alerts come from localized resources: speak them in the phone's language.
        setLocalTtsLanguage(tts, preferPhoneLanguage = true)
        return tts.speak(text, TextToSpeech.QUEUE_ADD, null, "watch_${System.currentTimeMillis()}") == TextToSpeech.SUCCESS
    }

    private val skillRefreshIntervalMs = 15 * 60_000L
    private var lastSkillRefreshMs = 0L
    private var skillRefreshJob: Job? = null

    /**
     * Detects which optional skills (OctoPrint, …) this user's Home Assistant supports and
     * offers only those tools to the model. Throttled to every 15 min unless [force].
     */
    private fun refreshHomeAssistantSkills(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSkillRefreshMs < skillRefreshIntervalMs) return
        if (skillRefreshJob?.isActive == true) {
            // Settings changed mid-detection: run again with the new ones afterwards.
            if (force) skillRefreshPending = true
            return
        }
        skillRefreshJob = serviceScope.launch(Dispatchers.IO) {
            do {
                skillRefreshPending = false
                val prefs = TrekoPreferences(this@TrekoAssistantService)
                val haUrl = prefs.haUrl.trim()
                val haToken = prefs.haToken.trim()
                val found = if (haUrl.isBlank() || haToken.isBlank()) {
                    emptySet()
                } else {
                    // Unreachable Home Assistant: null keeps the last known skills.
                    HomeAssistantClient(haUrl, haToken).detectSkills()
                }
                if (found != null) {
                    lastSkillRefreshMs = System.currentTimeMillis()
                    val previous = prefs.detectedSkills
                    wsClient?.availableSkills = found
                    if (found != previous) {
                        prefs.detectedSkills = found
                        TrekoLogBuffer.i(TAG, "Home Assistant skills changed: $previous -> $found")
                        withContext(Dispatchers.Main) { announceToolsChanged() }
                    }
                }
                // Settings changed while detecting: detect again with the new ones.
            } while (skillRefreshPending)
        }
    }

    @Volatile private var skillRefreshPending = false

    /**
     * The server asks for tools/list when the session starts, often before skills are
     * detected. Tell it the list changed (MCP), and reconnect while idle so a server that
     * ignores the notification still gets the new list.
     */
    private fun announceToolsChanged() {
        val client = wsClient ?: return
        client.notifyToolsChanged()
        if (_serviceState.value == TrekoState.IDLE && client.isConnected) {
            TrekoLogBuffer.i(TAG, "Reconnecting Xiaozhi so the server sees the new tool list.")
            client.disconnect()
            _connectionStatus.value = WsConnectionStatus.CONNECTING
            client.connect()
        }
    }

    private fun flushPendingOpusPackets(client: XiaozhiWebSocketClient) {
        synchronized(opusSendOrderLock) {
            val authState = biometricTurnGate.state
            if (authState == BiometricTurnGate.TurnAuthState.COLLECTING ||
                authState == BiometricTurnGate.TurnAuthState.CHECKING ||
                authState == BiometricTurnGate.TurnAuthState.REJECTED
            ) {
                return
            }
            val buffered: List<ByteArray>
            synchronized(pendingOpusLock) {
                if (pendingOpusPackets.isEmpty()) return
                buffered = pendingOpusPackets.toList()
                pendingOpusPackets.clear()
            }
            if (_serviceState.value == TrekoState.LISTENING) {
                for (pkt in buffered) {
                    client.sendAudio(pkt)
                }
            }
        }
    }

    private fun clearPendingOpusPackets() {
        synchronized(pendingOpusLock) {
            pendingOpusPackets.clear()
        }
    }

    /**
     * Loads the wake-word model off the main thread (it takes about a second). Until it is
     * ready [wakeWordDetector] stays null, which every user already handles (no detection yet).
     * If the service is destroyed meanwhile, the half-built detector is released, not published.
     */
    private fun initWakeWordDetector(prefs: TrekoPreferences) {
        serviceScope.launch(Dispatchers.IO) {
            val startedMs = System.currentTimeMillis()
            val detector = TrekoWakeWordDetector(this@TrekoAssistantService)
            var published = false
            try {
                detector.initialize(
                    initialKeyword = prefs.customWakeWord,
                    boost = prefs.wakeWordBoost.coerceIn(1.0f, 5.0f),
                    threshold = prefs.wakeWordSensitivity.coerceIn(0.01f, 0.20f)
                )
                // Settings saved while the model was loading.
                val latest = TrekoPreferences(this@TrekoAssistantService)
                detector.loadFromPreferences(latest)
                if (latest.customWakeWord != prefs.customWakeWord ||
                    latest.wakeWordBoost != prefs.wakeWordBoost ||
                    latest.wakeWordSensitivity != prefs.wakeWordSensitivity
                ) {
                    detector.updateKeywords(
                        newKeyword = latest.customWakeWord,
                        boost = latest.wakeWordBoost.coerceIn(1.0f, 5.0f),
                        threshold = latest.wakeWordSensitivity.coerceIn(0.01f, 0.20f)
                    )
                }
                synchronized(wakeWordLock) {
                    if (!wakeWordReleased) {
                        wakeWordDetector = detector
                        published = true
                    }
                }
            } finally {
                if (!published) {
                    try {
                        detector.release()
                    } catch (_: Exception) {
                    }
                }
            }
            if (published) {
                TrekoLogBuffer.i(TAG, "Wake word model ready in ${System.currentTimeMillis() - startedMs}ms.")
                collectWakeWordDetections(detector)
            }
        }
    }

    private fun collectWakeWordDetections(detector: TrekoWakeWordDetector) {
        serviceScope.launch {
            detector.detectionDetails.collect { event ->
                val snrLabel = String.format(Locale.US, "%.1f", event.snrDb)
                if (isTrainingMode) {
                    TrekoLogBuffer.d(TAG, "Training detection '${event.keyword}' [${event.source}, snr=${snrLabel}dB]")
                    _trainingDetections.tryEmit(event)
                } else {
                    val sinceTimeoutMs = lastListeningTimeoutTimestamp
                        .takeIf { it > 0L }
                        ?.let { System.currentTimeMillis() - it }
                    val rejection = WakeDetectionPolicy.rejectionReason(
                        state = _serviceState.value,
                        source = event.source,
                        snrDb = event.snrDb,
                        sinceTimeoutMs = sinceTimeoutMs
                    )
                    if (rejection != null) {
                        TrekoLogBuffer.d(
                            TAG,
                            "Ignoring ${event.source} detection '${event.keyword}' ($rejection) [snr=${snrLabel}dB, state=${_serviceState.value}]"
                        )
                        return@collect
                    }
                    TrekoLogBuffer.i(
                        TAG,
                        "Wake word detected '${event.keyword}' [${event.source}, snr=${snrLabel}dB] in state ${_serviceState.value}"
                    )
                    beginConversation(event.keyword)
                }
            }
        }
    }

    private fun setupAudioPipeline() {
        NativeAudioEngine.onPcmData = pcm@{ buffer, read ->
            if (isTrainingMode && read > 0) {
                _trainingPcmFrames.tryEmit(buffer.copyOf(read))
            }
            if (_isMuted.value && !isTrainingMode) {
                return@pcm
            }
            val currentState = _serviceState.value

            when (currentState) {
                TrekoState.IDLE, TrekoState.ERROR -> {
                    wakeWordDetector?.acceptWaveform(buffer, read)

                    if (!isTrainingMode && currentState == TrekoState.IDLE && !_isMuted.value) {
                        val rms = NativeAudioEngine.audioLevel.value.rms
                        val now = System.currentTimeMillis()
                        if (rms > 0.015f) {
                            lastSoundTimestamp = now
                            if (_currentEmotion.value == TrekoEmotion.SLEEPY) {
                                _currentEmotion.value = TrekoEmotion.RELAX
                            }
                        } else if (now - lastSoundTimestamp > 5000L) {
                            if (_currentEmotion.value != TrekoEmotion.SLEEPY) {
                                _currentEmotion.value = TrekoEmotion.SLEEPY
                            }
                        }
                    }
                }
                TrekoState.SPEAKING -> {
                    val now = System.currentTimeMillis()
                    val elapsed = now - speakingStartTimestamp
                    if (!isTrainingMode && !_isMuted.value && elapsed > 420L) {
                        val activeDenoiser = speechDenoiser
                        val cleanForSpeaking = if (activeDenoiser != null && activeDenoiser.isInitialized && activeDenoiser.isEnabled) {
                            val out = activeDenoiser.processPcm(buffer, read)
                            if (out.isNotEmpty()) out else buffer
                        } else {
                            buffer
                        }

                        // Wake word runs on post-AEC3 + GTCRN clean PCM when vocal barge-in is enabled
                        if (effectiveVoiceBargeInEnabled && elapsed > 650L) {
                            wakeWordDetector?.acceptWaveform(cleanForSpeaking, cleanForSpeaking.size)
                        }

                        when (effectiveFullDuplexMode) {
                            FullDuplexMode.HALF_DUPLEX -> Unit
                            FullDuplexMode.COMPLEMENTARY -> if (complementaryCaptureAllowed()) {
                                val metrics = NativeAudioEngine.lastFrameMetrics
                                val qSnap = complementaryQueue.onSpeakingFrame(
                                    cleanPcm16k = cleanForSpeaking,
                                    count = cleanForSpeaking.size,
                                    metrics = metrics,
                                    playbackRms = _playbackAudioLevel.value.rms,
                                    nowMs = now
                                )
                                _fullDuplexIndicator.value = qSnap.indicator

                                if (qSnap.needsBiometricCheck && !isComplementaryBioCheckInFlight) {
                                    val gen = complementaryQueue.currentGeneration()
                                    val bioSnap = complementaryQueue.snapshotBiometricPcm(gen)
                                    if (bioSnap != null) {
                                        launchComplementaryBiometricCheck(bioSnap, gen)
                                    }
                                }
                            }
                            FullDuplexMode.BARGE_IN_INTERRUPT -> {
                                val metrics = NativeAudioEngine.lastFrameMetrics
                                val decision = bargeInGate.evaluateFrame(cleanForSpeaking, cleanForSpeaking.size, metrics, now)
                                if (decision.isNearFieldSpeech) {
                                    bargeInVoicedFrames++
                                    if (bargeInVoicedFrames >= 4) {
                                        bargeInVoicedFrames = 0
                                        TrekoLogBuffer.i(TAG, "Full-Duplex BARGE_IN_INTERRUPT triggered on sustained speech.")
                                        beginRealtimeBargeInTurn(decision.framesToEmit)
                                    }
                                } else {
                                    bargeInVoicedFrames = 0
                                }
                            }
                        }
                    }
                }
                TrekoState.LISTENING -> {
                    val now = System.currentTimeMillis()
                    lastSoundTimestamp = now
                    val turnGen = listeningTurnGeneration
                    val metrics = NativeAudioEngine.lastFrameMetrics
                    val gateDecision = nearFieldGate.evaluateFrame(buffer, read, metrics, now)

                    if (gateDecision.speechStartedJustNow || gateDecision.isNearFieldSpeech) {
                        speechDetected = true
                        lastSpeechTimestamp = now
                    }

                    var snapshotToVerify: ShortArray? = null
                    if (gateDecision.shouldEmitAudio && gateDecision.framesToEmit.isNotEmpty() && !isClosingTurn) {
                        val denoisedFrames = ArrayList<ShortArray>(gateDecision.framesToEmit.size)
                        val activeDenoiser = speechDenoiser
                        for (rawFrame in gateDecision.framesToEmit) {
                            val clean = if (activeDenoiser != null && activeDenoiser.isInitialized && activeDenoiser.isEnabled) {
                                activeDenoiser.processPcm(rawFrame, rawFrame.size)
                            } else {
                                rawFrame
                            }
                            if (clean.isNotEmpty()) {
                                denoisedFrames.add(clean)
                            }
                        }

                        if (denoisedFrames.isNotEmpty()) {
                            synchronized(opusSendOrderLock) {
                                val currentAuth = biometricTurnGate.state
                                if (currentAuth != BiometricTurnGate.TurnAuthState.REJECTED) {
                                    biometricTurnGate.onSpeechPcm(denoisedFrames)
                                    val encodedPackets = ArrayList<ByteArray>(denoisedFrames.size * 2)
                                    val codec = opusCodec
                                    if (codec != null) {
                                        for (cleanFrame in denoisedFrames) {
                                            encodedPackets.addAll(codec.encodePcm(cleanFrame, cleanFrame.size))
                                        }
                                    }
                                    val immediatePackets = biometricTurnGate.bufferOrPassOpus(encodedPackets)
                                    if (immediatePackets.isNotEmpty()) {
                                        sendOrQueueOpusLocked(immediatePackets)
                                    }
                                    if (biometricTurnGate.state == BiometricTurnGate.TurnAuthState.COLLECTING) {
                                        snapshotToVerify = biometricTurnGate.pollSnapshotForCheck(
                                            silenceDurationMs = gateDecision.silenceDurationMs,
                                            isTurnEnding = false
                                        )
                                    }
                                }
                            }
                        }
                    }

                    snapshotToVerify?.let { pcmSnap ->
                        launchBiometricVerification(pcmSnap, isTurnEnding = false, turnGen = turnGen)
                    }

                    if (!isClosingTurn) {
                        if (nearFieldGate.speechStarted && gateDecision.shouldCloseTurnOnSilence) {
                            isClosingTurn = true
                            handleEndOfSpeechSilence(turnGen)
                        } else if (!nearFieldGate.speechStarted &&
                            (now - listeningStartTimestamp > activeListeningTimeoutMs ||
                                (listeningHardDeadlineMs > 0L && now > listeningHardDeadlineMs))
                        ) {
                            isClosingTurn = true
                            handleNoSpeechTimeout(turnGen)
                        }
                    }
                }
                TrekoState.THINKING -> {
                    // Decir la palabra de activación mientras piensa abre un turno nuevo.
                    if (!isTrainingMode) {
                        wakeWordDetector?.acceptWaveform(buffer, read)
                    }
                }
            }
        }
    }

    private fun sendOrQueueOpusLocked(packets: List<ByteArray>) {
        val client = wsClient
        if (client != null && client.isConnected && client.sessionId != null) {
            for (packet in packets) {
                client.sendAudio(packet)
            }
        } else {
            synchronized(pendingOpusLock) {
                for (packet in packets) {
                    if (pendingOpusPackets.size >= 50) {
                        pendingOpusPackets.removeFirst()
                    }
                    pendingOpusPackets.addLast(packet)
                }
            }
        }
    }

    private fun openAndFlushVerifiedStreamLocked(sendStartListening: Boolean) {
        val client = wsClient ?: return
        if (sendStartListening) {
            if (!client.isConnected) {
                _connectionStatus.value = WsConnectionStatus.CONNECTING
                client.connect()
            }
            client.startListening()
        }
        val queued = biometricTurnGate.drainPendingOpus()
        if (queued.isNotEmpty()) {
            sendOrQueueOpusLocked(queued)
        }
    }

    private fun complementaryCaptureAllowed(): Boolean =
        FullDuplexComplementaryQueue.captureAllowed(
            voiceVerificationActive = effectiveBiometricGateEnabled,
            headsetRouted = NativeAudioEngine.isHeadsetRouted
        )

    private fun launchComplementaryBiometricCheck(snapshot: ShortArray, expectedGen: Long) {
        complementaryBioJob?.cancel()
        isComplementaryBioCheckInFlight = true
        complementaryBioJob = serviceScope.launch(Dispatchers.Default) {
            try {
                val bioEngine = speakerBiometricEngine
                val result = bioEngine?.verifyPcm(
                    samples = snapshot,
                    count = snapshot.size,
                    recordTurnStats = false
                )
                val verified = if (bioEngine == null || result == null || !result.isAvailable) {
                    true
                } else {
                    _lastBiometricResult.value = result
                    val ok = result.similarity >= bioEngine.threshold
                    if (!ok) {
                        val simFmt = String.format(Locale.US, "%.2f", result.similarity)
                        val thrFmt = String.format(Locale.US, "%.2f", bioEngine.threshold)
                        TrekoLogBuffer.i(
                            TAG,
                            "Complementary speaker rejected during SPEAKING (score=$simFmt < $thrFmt)."
                        )
                    }
                    ok
                }
                complementaryQueue.setBiometricVerdict(expectedGen, verified)
                if (!verified) {
                    _fullDuplexIndicator.value = FullDuplexIndicator.INACTIVE
                }
            } finally {
                isComplementaryBioCheckInFlight = false
            }
        }
    }

    private suspend fun dispatchQueuedComplementaryTurn(
        pcmFrames: List<ShortArray>,
        client: XiaozhiWebSocketClient
    ) {
        _fullDuplexIndicator.value = FullDuplexIndicator.FLUSHING_COMPLEMENTARY
        try {
            val encoded = synchronized(opusSendOrderLock) {
                val codec = opusCodec
                val packets = ArrayList<ByteArray>(pcmFrames.size * 2 + 8)
                if (codec != null) {
                    codec.resetEncoder()
                    for (pcm in pcmFrames) {
                        packets.addAll(codec.encodePcm(pcm, pcm.size))
                    }
                    val silenceFrame = ShortArray(960)
                    repeat(3) {
                        packets.addAll(codec.encodePcm(silenceFrame, silenceFrame.size))
                    }
                    packets.addAll(codec.flushEncoder())
                }
                packets
            }

            val sentOk = if (encoded.isNotEmpty() && client.isConnected && client.sessionId != null) {
                client.startListening("auto")
                delay(40L)
                for ((index, pkt) in encoded.withIndex()) {
                    if (!client.isConnected || _serviceState.value == TrekoState.IDLE) break
                    client.sendAudio(pkt)
                    if ((index + 1) % 2 == 0) {
                        delay(10L)
                    }
                }
                delay(30L)
                client.stopListening()
                true
            } else {
                false
            }

            if (sentOk) {
                TrekoLogBuffer.i(
                    TAG,
                    "Flushed complementary addendum (${pcmFrames.size} frames, ${encoded.size} opus pkts) after TTS finished."
                )
                transitionState(TrekoState.THINKING, TrekoEmotion.PIENSA)
                _subtitleText.value = getString(R.string.status_thinking)
                startThinkingTimeout()
            } else {
                transitionState(
                    TrekoState.IDLE,
                    if (_isMuted.value) TrekoEmotion.SLEEPY else TrekoEmotion.RELAX
                )
                _subtitleText.value = ""
            }
        } finally {
            _fullDuplexIndicator.value = FullDuplexIndicator.INACTIVE
        }
    }

    private fun startLiveHandoffListening(pcmFrames: List<ShortArray>) {
        _fullDuplexIndicator.value = FullDuplexIndicator.FLUSHING_COMPLEMENTARY
        startActiveListening(prompt = getString(R.string.status_listening))
        var snapshotToVerify: ShortArray? = null
        val turnGen = listeningTurnGeneration
        synchronized(opusSendOrderLock) {
            if (_serviceState.value == TrekoState.LISTENING) {
                speechDetected = true
                lastSpeechTimestamp = System.currentTimeMillis()
                val auth = biometricTurnGate.state
                if (auth != BiometricTurnGate.TurnAuthState.REJECTED) {
                    biometricTurnGate.onSpeechPcm(pcmFrames)
                    val encoded = ArrayList<ByteArray>(pcmFrames.size * 2)
                    val codec = opusCodec
                    if (codec != null) {
                        for (pcm in pcmFrames) {
                            encoded.addAll(codec.encodePcm(pcm, pcm.size))
                        }
                    }
                    val immediate = biometricTurnGate.bufferOrPassOpus(encoded)
                    if (immediate.isNotEmpty()) {
                        sendOrQueueOpusLocked(immediate)
                    }
                    if (biometricTurnGate.state == BiometricTurnGate.TurnAuthState.COLLECTING) {
                        snapshotToVerify = biometricTurnGate.pollSnapshotForCheck(
                            silenceDurationMs = 0L,
                            isTurnEnding = false
                        )
                    }
                }
            }
        }
        snapshotToVerify?.let { pcmSnap ->
            launchBiometricVerification(pcmSnap, isTurnEnding = false, turnGen = turnGen)
        }
        _fullDuplexIndicator.value = FullDuplexIndicator.INACTIVE
        TrekoLogBuffer.i(TAG, "Handed off ongoing complementary utterance (${pcmFrames.size} frames) into live LISTENING.")
    }

    private fun beginRealtimeBargeInTurn(leadingFrames: List<ShortArray>) {
        beginConversation(TrekoPreferences(this).customWakeWord)
        if (leadingFrames.isEmpty()) return
        val turnGen = listeningTurnGeneration
        var snapshotToVerify: ShortArray? = null
        synchronized(opusSendOrderLock) {
            if (_serviceState.value == TrekoState.LISTENING) {
                speechDetected = true
                lastSpeechTimestamp = System.currentTimeMillis()
                val auth = biometricTurnGate.state
                if (auth != BiometricTurnGate.TurnAuthState.REJECTED) {
                    biometricTurnGate.onSpeechPcm(leadingFrames)
                    val encoded = ArrayList<ByteArray>(leadingFrames.size * 2)
                    val codec = opusCodec
                    if (codec != null) {
                        for (pcm in leadingFrames) {
                            encoded.addAll(codec.encodePcm(pcm, pcm.size))
                        }
                    }
                    val immediate = biometricTurnGate.bufferOrPassOpus(encoded)
                    if (immediate.isNotEmpty()) {
                        sendOrQueueOpusLocked(immediate)
                    }
                    if (biometricTurnGate.state == BiometricTurnGate.TurnAuthState.COLLECTING) {
                        snapshotToVerify = biometricTurnGate.pollSnapshotForCheck(
                            silenceDurationMs = 0L,
                            isTurnEnding = false
                        )
                    }
                }
            }
        }
        snapshotToVerify?.let { pcmSnap ->
            launchBiometricVerification(pcmSnap, isTurnEnding = false, turnGen = turnGen)
        }
    }

    private fun launchBiometricVerification(
        snapshot: ShortArray,
        isTurnEnding: Boolean,
        turnGen: Long
    ) {
        biometricCheckJob?.cancel()
        isBiometricCheckInFlight = true
        biometricCheckJob = serviceScope.launch(Dispatchers.Default) {
            try {
                if (turnGen != listeningTurnGeneration || _serviceState.value != TrekoState.LISTENING) {
                    return@launch
                }
                val bioEngine = speakerBiometricEngine
                val result = bioEngine?.verifyPcm(
                    samples = snapshot,
                    count = snapshot.size,
                    recordTurnStats = true
                )

                var finishThinkingTurn = false
                var rejectedForeign = false

                synchronized(opusSendOrderLock) {
                    if (turnGen != listeningTurnGeneration || _serviceState.value != TrekoState.LISTENING) {
                        return@launch
                    }

                    if (bioEngine == null || result == null || !result.isAvailable) {
                        val outcome = biometricTurnGate.applyCheckResult(
                            similarity = 1.0f,
                            threshold = 0.0f,
                            isTurnEnding = isTurnEnding
                        )
                        if (outcome == BiometricTurnGate.CheckOutcome.VERIFIED) {
                            openAndFlushVerifiedStreamLocked(sendStartListening = true)
                            if (isTurnEnding) finishThinkingTurn = true
                        }
                    } else {
                        _lastBiometricResult.value = result
                        val outcome = biometricTurnGate.applyCheckResult(
                            similarity = result.similarity,
                            threshold = bioEngine.threshold,
                            isTurnEnding = isTurnEnding
                        )
                        when (outcome) {
                            BiometricTurnGate.CheckOutcome.VERIFIED -> {
                                val simFmt = String.format(Locale.US, "%.2f", result.similarity)
                                val thrFmt = String.format(Locale.US, "%.2f", bioEngine.threshold)
                                TrekoLogBuffer.i(
                                    TAG,
                                    "Biometric voiceprint verified (score=$simFmt >= $thrFmt, ${result.inferenceMs}ms)."
                                )
                                openAndFlushVerifiedStreamLocked(sendStartListening = true)
                                if (isTurnEnding) {
                                    finishThinkingTurn = true
                                }
                            }
                            BiometricTurnGate.CheckOutcome.NEED_MORE_SPEECH -> {
                                val simFmt = String.format(Locale.US, "%.2f", result.similarity)
                                TrekoLogBuffer.d(
                                    TAG,
                                    "Biometric score borderline ($simFmt); waiting up to 1.4s for stronger speech sample."
                                )
                            }
                            BiometricTurnGate.CheckOutcome.REJECTED -> {
                                val simFmt = String.format(Locale.US, "%.2f", result.similarity)
                                val thrFmt = String.format(Locale.US, "%.2f", bioEngine.threshold)
                                TrekoLogBuffer.i(
                                    TAG,
                                    "Foreign/TV voice rejected (score=$simFmt < $thrFmt, ${result.inferenceMs}ms); discarding turn locally."
                                )
                                rejectedForeign = true
                            }
                        }
                    }
                }

                if (rejectedForeign) {
                    abortTurnAsForeignSpeaker(turnGen)
                } else if (finishThinkingTurn) {
                    synchronized(opusSendOrderLock) {
                        if (turnGen == listeningTurnGeneration && _serviceState.value == TrekoState.LISTENING) {
                            closeTurnAndEnterThinkingLocked()
                        }
                    }
                }
            } finally {
                isBiometricCheckInFlight = false
            }
        }
    }

    private fun handleEndOfSpeechSilence(turnGen: Long) {
        serviceScope.launch(Dispatchers.Default) {
            // Wait if an async biometric verification is already running on current turn
            val activeJob = biometricCheckJob
            if (activeJob != null && activeJob.isActive) {
                try {
                    activeJob.join()
                } catch (_: Exception) {
                }
            }

            if (turnGen != listeningTurnGeneration || _serviceState.value != TrekoState.LISTENING) {
                return@launch
            }

            var finalSnapToVerify: ShortArray? = null
            var rejectedUltraShort = false
            var finishThinkingNow = false

            synchronized(opusSendOrderLock) {
                if (turnGen != listeningTurnGeneration || _serviceState.value != TrekoState.LISTENING) {
                    return@launch
                }

                // Flush neural denoiser lookahead tail while turn is still alive & not rejected
                val activeDenoiser = speechDenoiser
                if (biometricTurnGate.state != BiometricTurnGate.TurnAuthState.REJECTED &&
                    activeDenoiser != null && activeDenoiser.isInitialized && activeDenoiser.isEnabled
                ) {
                    val tailPcm = activeDenoiser.flush()
                    if (tailPcm.isNotEmpty()) {
                        biometricTurnGate.onSpeechPcm(listOf(tailPcm))
                        val tailPackets = opusCodec?.encodePcm(tailPcm, tailPcm.size) ?: emptyList()
                        val immediate = biometricTurnGate.bufferOrPassOpus(tailPackets)
                        if (immediate.isNotEmpty()) {
                            sendOrQueueOpusLocked(immediate)
                        }
                    }
                }

                when (biometricTurnGate.state) {
                    BiometricTurnGate.TurnAuthState.DISABLED,
                    BiometricTurnGate.TurnAuthState.VERIFIED -> {
                        finishThinkingNow = true
                    }
                    BiometricTurnGate.TurnAuthState.REJECTED -> {
                        rejectedUltraShort = true
                    }
                    BiometricTurnGate.TurnAuthState.COLLECTING,
                    BiometricTurnGate.TurnAuthState.CHECKING -> {
                        val snap = biometricTurnGate.pollSnapshotForCheck(
                            silenceDurationMs = 9999L,
                            isTurnEnding = true
                        )
                        if (snap != null) {
                            finalSnapToVerify = snap
                        } else {
                            val shortOutcome = biometricTurnGate.finalizeUltraShortTurn(
                                strictBlockShortAmbient = listeningOpenedByAutoFollowUp
                            )
                            if (shortOutcome == BiometricTurnGate.CheckOutcome.VERIFIED) {
                                openAndFlushVerifiedStreamLocked(sendStartListening = true)
                                finishThinkingNow = true
                            } else {
                                rejectedUltraShort = true
                            }
                        }
                    }
                }

                if (finishThinkingNow) {
                    closeTurnAndEnterThinkingLocked()
                }
            }

            finalSnapToVerify?.let { snap ->
                val bioEngine = speakerBiometricEngine
                val result = bioEngine?.verifyPcm(
                    samples = snap,
                    count = snap.size,
                    recordTurnStats = true
                )
                var abortForeign = false
                synchronized(opusSendOrderLock) {
                    if (turnGen != listeningTurnGeneration || _serviceState.value != TrekoState.LISTENING) {
                        return@launch
                    }
                    if (bioEngine == null || result == null || !result.isAvailable) {
                        val outcome = biometricTurnGate.applyCheckResult(1.0f, 0.0f, isTurnEnding = true)
                        if (outcome == BiometricTurnGate.CheckOutcome.VERIFIED) {
                            openAndFlushVerifiedStreamLocked(sendStartListening = true)
                            closeTurnAndEnterThinkingLocked()
                        }
                    } else {
                        _lastBiometricResult.value = result
                        val outcome = biometricTurnGate.applyCheckResult(
                            similarity = result.similarity,
                            threshold = bioEngine.threshold,
                            isTurnEnding = true
                        )
                        if (outcome == BiometricTurnGate.CheckOutcome.VERIFIED) {
                            openAndFlushVerifiedStreamLocked(sendStartListening = true)
                            closeTurnAndEnterThinkingLocked()
                        } else {
                            val simFmt = String.format(Locale.US, "%.2f", result.similarity)
                            val thrFmt = String.format(Locale.US, "%.2f", bioEngine.threshold)
                            TrekoLogBuffer.i(
                                TAG,
                                "Final short-phrase biometric rejected (score=$simFmt < $thrFmt); ignoring turn."
                            )
                            abortForeign = true
                        }
                    }
                }
                if (abortForeign) {
                    abortTurnAsForeignSpeaker(turnGen)
                }
            } ?: run {
                if (rejectedUltraShort) {
                    TrekoLogBuffer.i(TAG, "Ultra-short unverified follow-up snippet rejected locally.")
                    abortTurnAsForeignSpeaker(turnGen)
                }
            }
        }
    }

    private fun closeTurnAndEnterThinkingLocked() {
        TrekoLogBuffer.i(TAG, "End-of-speech VAD triggered; flushing Opus encoder.")
        speechDetected = false
        val client = wsClient
        val finalPackets = opusCodec?.flushEncoder() ?: emptyList()
        if (client != null && finalPackets.isNotEmpty() && client.isConnected && client.sessionId != null) {
            for (packet in finalPackets) {
                client.sendAudio(packet)
            }
        }
        clearPendingOpusPackets()
        biometricTurnGate.reset()
        nearFieldGate.reset()
        speechDenoiser?.reset()
        client?.stopListening()
        transitionState(TrekoState.THINKING, TrekoEmotion.PIENSA)
        _subtitleText.value = getString(R.string.status_thinking)
        startThinkingTimeout()
    }

    private fun handleNoSpeechTimeout(turnGen: Long) {
        if (turnGen != listeningTurnGeneration || _serviceState.value != TrekoState.LISTENING) return
        lastListeningTimeoutTimestamp = System.currentTimeMillis()
        TrekoLogBuffer.i(TAG, "Listening timed out (${activeListeningTimeoutMs}ms) without near-field speech.")
        val openedWsListening: Boolean
        val hadSpeech: Boolean
        synchronized(opusSendOrderLock) {
            openedWsListening = biometricTurnGate.state == BiometricTurnGate.TurnAuthState.DISABLED ||
                biometricTurnGate.state == BiometricTurnGate.TurnAuthState.VERIFIED
            hadSpeech = speechDetected
            speechDetected = false
            biometricTurnGate.reset()
            nearFieldGate.reset()
            speechDenoiser?.reset()
            clearPendingOpusPackets()
            opusCodec?.resetEncoder()
        }
        if (openedWsListening && hadSpeech) {
            wsClient?.stopListening()
        } else if (openedWsListening) {
            suppressIdleTts = true
        }
        transitionState(
            TrekoState.IDLE,
            if (_isMuted.value) TrekoEmotion.SLEEPY else TrekoEmotion.RELAX
        )
        _subtitleText.value = ""
        if (openedWsListening && !hadSpeech) {
            wsClient?.abortSpeaking(reason = "no_speech_timeout")
        }
    }

    private fun abortTurnAsForeignSpeaker(turnGen: Long) {
        if (turnGen != listeningTurnGeneration || _serviceState.value != TrekoState.LISTENING) return
        val now = System.currentTimeMillis()
        foreignRejectionsInTurn++
        val exceededBudget = (listeningHardDeadlineMs > 0L && now >= listeningHardDeadlineMs) ||
            foreignRejectionsInTurn >= 4

        listeningTurnGeneration++
        isClosingTurn = false
        speechDetected = false

        if (exceededBudget) {
            TrekoLogBuffer.i(
                TAG,
                "Foreign voice rejection budget reached ($foreignRejectionsInTurn rejections); returning to IDLE."
            )
            synchronized(opusSendOrderLock) {
                biometricTurnGate.reset()
                nearFieldGate.reset(now)
                speechDenoiser?.reset()
                clearPendingOpusPackets()
                opusCodec?.resetEncoder()
            }
            transitionState(
                TrekoState.IDLE,
                if (_isMuted.value) TrekoEmotion.SLEEPY else TrekoEmotion.RELAX
            )
            _subtitleText.value = getString(R.string.status_foreign_voice_ignored)
            foreignVoiceResetJob?.cancel()
            foreignVoiceResetJob = serviceScope.launch {
                delay(2200L)
                if (_serviceState.value == TrekoState.IDLE &&
                    _subtitleText.value == getString(R.string.status_foreign_voice_ignored)
                ) {
                    _subtitleText.value = ""
                }
            }
            return
        }

        // Discard ONLY the foreign/TV burst and remain in LISTENING for the user's voice
        listeningStartTimestamp = now
        lastSpeechTimestamp = now
        synchronized(opusSendOrderLock) {
            clearPendingOpusPackets()
            opusCodec?.resetEncoder()
            speechDenoiser?.reset()
            nearFieldGate.reset(now)
            biometricTurnGate.startTurn(enabled = effectiveBiometricGateEnabled)
        }
        _currentEmotion.value = TrekoEmotion.ESCUCHA
        _subtitleText.value = getString(R.string.status_foreign_voice_ignored)
        foreignVoiceResetJob?.cancel()
        foreignVoiceResetJob = serviceScope.launch {
            delay(1800L)
            if (_serviceState.value == TrekoState.LISTENING &&
                _subtitleText.value == getString(R.string.status_foreign_voice_ignored)
            ) {
                _subtitleText.value = getString(R.string.status_listening)
            }
        }
    }

    private fun startThinkingTimeout() {
        conversationWatchdog.onTurnSent(System.currentTimeMillis())
        scheduleThinkingWatchdog()
    }

    private fun cancelThinkingWatchdog() {
        conversationWatchdog.disarm()
        thinkingTimeoutJob?.cancel()
    }

    /** (Re)programa el temporizador según el plazo vigente de [conversationWatchdog]. */
    private fun scheduleThinkingWatchdog() {
        thinkingTimeoutJob?.cancel()
        thinkingTimeoutJob = serviceScope.launch {
            while (true) {
                val remaining = conversationWatchdog.remainingMs(System.currentTimeMillis()) ?: return@launch
                delay(remaining)
                if (conversationWatchdog.checkExpired(System.currentTimeMillis(), wsClient?.sessionId)) break
            }
            if (_serviceState.value == TrekoState.THINKING) {
                TrekoLogBuffer.w(TAG, "Thinking watchdog timeout; recovering to IDLE.")
                _subtitleText.value = getString(R.string.status_thinking_timeout)
                _currentEmotion.value = TrekoEmotion.CONFUSED
                delay(2500L)
                if (_serviceState.value == TrekoState.THINKING) {
                    transitionState(
                        TrekoState.IDLE,
                        if (_isMuted.value) TrekoEmotion.SLEEPY else TrekoEmotion.RELAX
                    )
                    _subtitleText.value = ""
                }
            }
        }
    }

    fun beginConversation(keyword: String = "Treko") {
        // The wake word (or a tap) during a meditation pauses it; "sigue" resumes it.
        meditation?.onConversationStarted()
        cancelMeditationCue()
        if (_isMuted.value) {
            TrekoPreferences(this).isMicMuted = false
            applyMuteState(muted = false)
        }
        val prefs = TrekoPreferences(this)
        syncPolicyPreferences(prefs)
        speakerBiometricEngine?.let { bio ->
            bio.loadFromPreferences(prefs)
            effectiveBiometricGateEnabled = bio.isVerificationActive()
        } ?: run {
            effectiveBiometricGateEnabled = false
        }

        listeningTurnGeneration++
        biometricCheckJob?.cancel()
        complementaryBioJob?.cancel()
        isComplementaryBioCheckInFlight = false
        bargeInVoicedFrames = 0
        foreignVoiceResetJob?.cancel()
        cancelThinkingWatchdog()
        conversationWatchdog.consumeLateTtsGrace()
        proactiveTtsFallbackJob?.cancel()
        activeVoiceProactiveInsight = null
        ttsDrainingJob?.cancel()
        try {
            localTts?.stop()
        } catch (_: Exception) {
        }
        isClosingTurn = false
        suppressIdleTts = false
        listeningOpenedByAutoFollowUp = false
        _fullDuplexIndicator.value = FullDuplexIndicator.INACTIVE

        synchronized(opusSendOrderLock) {
            clearPendingOpusPackets()
            opusCodec?.stopPlayback()
            opusCodec?.resetEncoder()
            NativeAudioEngine.resetSoftwareAec()
            complementaryQueue.reset(biometricEnabled = effectiveBiometricGateEnabled, nowMs = System.currentTimeMillis())
            bargeInGate.reset(System.currentTimeMillis())
            speechDenoiser?.reset()
            nearFieldGate.reset()
            biometricTurnGate.startTurn(enabled = effectiveBiometricGateEnabled)
        }
        // A new turn (wake word or a tap) interrupts any reply still playing: the same
        // abort the Xiaozhi firmware sends when its wake word fires.
        wsClient?.abortSpeaking(reason = "wake_word_detected")

        hasTurnEmotion = false
        speechDetected = false
        foreignRejectionsInTurn = 0
        val now = System.currentTimeMillis()
        listeningStartTimestamp = now
        lastSpeechTimestamp = now
        activeListeningTimeoutMs = 5000L
        listeningHardDeadlineMs = now + 20_000L

        _subtitleText.value = getString(R.string.status_listening)

        val prefsWord = prefs.customWakeWord
        if (keyword.equals(prefsWord, ignoreCase = true) || keyword.equals("Treko", ignoreCase = true)) {
            transitionState(TrekoState.LISTENING, TrekoEmotion.FELIZ_ABIERTO)
            serviceScope.launch {
                delay(350)
                if (_serviceState.value == TrekoState.LISTENING) {
                    _currentEmotion.value = TrekoEmotion.ESCUCHA
                }
            }
        } else {
            transitionState(TrekoState.LISTENING, TrekoEmotion.ESCUCHA)
        }

        val client = wsClient
        if (client != null) {
            if (!client.isConnected) {
                _connectionStatus.value = WsConnectionStatus.CONNECTING
                client.connect()
            }
            if (!effectiveBiometricGateEnabled) {
                val listenMode = if (effectiveFullDuplexMode == FullDuplexMode.BARGE_IN_INTERRUPT) "realtime" else "auto"
                client.startListening(listenMode)
            }
        }
    }

    fun startActiveListening(prompt: String = getString(R.string.status_follow_up)) {
        if (_isMuted.value) {
            TrekoLogBuffer.i(TAG, "Skipping follow-up listening while microphone is muted.")
            transitionState(TrekoState.IDLE, TrekoEmotion.SLEEPY)
            _subtitleText.value = ""
            return
        }
        val prefs = TrekoPreferences(this)
        syncPolicyPreferences(prefs)
        speakerBiometricEngine?.let { bio ->
            bio.loadFromPreferences(prefs)
            effectiveBiometricGateEnabled = bio.isVerificationActive()
        } ?: run {
            effectiveBiometricGateEnabled = false
        }

        listeningTurnGeneration++
        biometricCheckJob?.cancel()
        foreignVoiceResetJob?.cancel()
        cancelThinkingWatchdog()
        proactiveTtsFallbackJob?.cancel()
        activeVoiceProactiveInsight = null
        ttsDrainingJob?.cancel()
        try {
            localTts?.stop()
        } catch (_: Exception) {
        }
        isClosingTurn = false
        suppressIdleTts = false
        listeningOpenedByAutoFollowUp = true

        synchronized(opusSendOrderLock) {
            clearPendingOpusPackets()
            opusCodec?.resetEncoder()
            speechDenoiser?.reset()
            nearFieldGate.reset()
            biometricTurnGate.startTurn(enabled = effectiveBiometricGateEnabled)
        }

        hasTurnEmotion = false
        speechDetected = false
        foreignRejectionsInTurn = 0
        val now = System.currentTimeMillis()
        listeningStartTimestamp = now
        lastSpeechTimestamp = now
        activeListeningTimeoutMs = 7000L
        listeningHardDeadlineMs = now + 20_000L

        transitionState(TrekoState.LISTENING, TrekoEmotion.ESCUCHA)
        _subtitleText.value = prompt

        val client = wsClient
        if (client != null) {
            if (!client.isConnected) {
                _connectionStatus.value = WsConnectionStatus.CONNECTING
                client.connect()
            }
            if (!effectiveBiometricGateEnabled) {
                val listenMode = if (effectiveFullDuplexMode == FullDuplexMode.BARGE_IN_INTERRUPT) "realtime" else "auto"
                client.startListening(listenMode)
            }
        }
    }

    private suspend fun handleMcpToolCall(
        name: String,
        args: JSONObject,
        prefs: TrekoPreferences
    ): String = withContext(Dispatchers.IO) {
        try {
            when (name) {
                "get_device_status" -> {
                    val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    val batteryLevel = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                    val isCharging = bm?.isCharging == true
                    val sdf = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.getDefault())
                    val currentTime = sdf.format(Date())
                    getString(
                        R.string.tool_device_status,
                        batteryLevel,
                        getString(if (isCharging) R.string.tool_battery_charging else R.string.tool_battery_not_charging),
                        currentTime
                    )
                }
                "manage_vault_memory" -> {
                    val action = args.optString("action", "list").lowercase(Locale.ROOT)
                    val key = args.optString("key", "").trim()
                    val value = args.optString("value", "").trim()
                    val vaultPrefs = getSharedPreferences("treko_vault_memory", Context.MODE_PRIVATE)

                    when (action) {
                        "set", "save", "guardar" -> {
                            if (key.isBlank()) return@withContext getString(R.string.vault_key_required)
                            vaultPrefs.edit().putString(key, value).apply()
                            getString(R.string.vault_saved, key, value)
                        }
                        "get", "read", "consultar" -> {
                            if (key.isBlank()) return@withContext getString(R.string.vault_key_required)
                            val stored = vaultPrefs.getString(key, null)
                            if (stored != null) getString(R.string.vault_value, key, stored) else getString(R.string.vault_not_found, key)
                        }
                        "list" -> {
                            val all = vaultPrefs.all
                            if (all.isEmpty()) {
                                getString(R.string.vault_empty)
                            } else {
                                getString(R.string.vault_list, all.entries.joinToString("; ") { "${it.key}: ${it.value}" })
                            }
                        }
                        "delete", "remove", "forget", "olvidar", "borrar" -> {
                            if (key.isBlank()) return@withContext getString(R.string.vault_key_required)
                            if (!vaultPrefs.contains(key)) return@withContext getString(R.string.vault_not_found, key)
                            vaultPrefs.edit().remove(key).apply()
                            getString(R.string.vault_deleted, key)
                        }
                        else -> getString(R.string.vault_bad_action)
                    }
                }
                "home_assistant_call_service" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext getString(R.string.tool_ha_not_configured)
                    }
                    val domain = args.optString("domain", "").trim()
                    val service = args.optString("service", "").trim()
                    val confirmed = args.optBoolean("confirm", false)

                    val serviceData = when {
                        args.has("service_data") && args.optJSONObject("service_data") != null -> {
                            args.getJSONObject("service_data")
                        }
                        args.has("service_data") && args.optString("service_data").startsWith("{") -> {
                            try {
                                JSONObject(args.getString("service_data"))
                            } catch (_: Exception) {
                                JSONObject()
                            }
                        }
                        else -> {
                            val payload = JSONObject()
                            val it = args.keys()
                            while (it.hasNext()) {
                                val key = it.next()
                                if (key != "domain" && key != "service" && key != "confirm") {
                                    payload.put(key, args.get(key))
                                }
                            }
                            payload
                        }
                    }

                    // A model may put the flag inside the data too; it is not a Home Assistant field.
                    val confirmedInData = serviceData.remove("confirm") == true
                    HomeAssistantClient(haUrl, haToken).callService(
                        domain,
                        service,
                        serviceData,
                        gate = sensitiveGate(confirmed || confirmedInData)
                    )
                }
                "home_assistant_broadcast" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext getString(R.string.tool_ha_not_configured)
                    }
                    val message = args.optString("message", "").trim()
                    val target = args.optString("target", "").trim()
                    if (message.isBlank()) {
                        return@withContext getString(R.string.broadcast_empty)
                    }
                    HomeAssistantClient(haUrl, haToken).broadcastMessage(message, target)
                }
                "home_assistant_list_entities" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext getString(R.string.tool_ha_not_configured)
                    }
                    HomeAssistantClient(haUrl, haToken).listEntities(
                        args.optString("domain", ""),
                        args.optString("search", "")
                    )
                }
                "home_assistant_get_entity_state" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext getString(R.string.tool_ha_not_configured)
                    }
                    val entityId = args.optString("entity_id", "").trim()
                    if (entityId.isBlank()) {
                        return@withContext getString(R.string.entity_required)
                    }
                    HomeAssistantClient(haUrl, haToken).getEntityState(entityId)
                }
                "home_assistant_list_services" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext getString(R.string.tool_ha_not_configured)
                    }
                    HomeAssistantClient(haUrl, haToken).listServices(args.optString("domain", ""))
                }
                "home_assistant_control" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext getString(R.string.tool_ha_not_configured)
                    }

                    val entityId = args.optString("entity_id", "")
                        .ifBlank { args.optString("device", "") }
                        .ifBlank { args.optString("name", "") }
                        .ifBlank { args.optString("target", "") }
                        .ifBlank { args.optString("query", "") }
                    val action = args.optString("action", "turn_on")
                        .ifBlank { args.optString("service", "turn_on") }
                    val source = args.optString("source", "")
                        .ifBlank { args.optString("input", "") }
                    val brightness = args.optInt("brightness", 0)
                    val kelvin = args.optInt("kelvin", 0)
                    val color = args.optString("color", "").trim()
                    val colorOrTemp = if (color.isNotBlank()) color else if (kelvin > 0) "${kelvin}k" else ""

                    HomeAssistantClient(haUrl, haToken).controlDevice(
                        deviceQuery = entityId,
                        accion = action,
                        brillo = brightness,
                        colorOrTemp = colorOrTemp,
                        source = source,
                        gate = sensitiveGate(args.optBoolean("confirm", false))
                    )
                }
                "manage_habits" -> {
                    val habitManager = TrekoHabitManager(this@TrekoAssistantService)
                    val action = args.optString("action", "list").lowercase(Locale.ROOT)
                    val title = args.optString("title", "").trim()
                    val id = args.optInt("id", -1)
                    val waterCount = args.optInt("water_count", -1)

                    when (action) {
                        "add", "create" -> {
                            if (title.isBlank()) return@withContext getString(R.string.habit_title_required)
                            val item = habitManager.addHabit(title)
                            getString(R.string.habit_added, item.title)
                        }
                        "toggle", "check", "complete" -> {
                            val lookup = TrekoHabitManager.findTarget(id.takeIf { it != -1 }, title)
                            lookup.failureMessage("hábito", "hábitos", TrekoHabitManager.habitsFlow.value) { "#${it.id} ${it.title}" }
                                ?.let { return@withContext it }
                            val targetId = (lookup as TargetLookup.Found).item.id
                            habitManager.toggleHabit(targetId)
                            val item = TrekoHabitManager.habitsFlow.value.find { it.id == targetId }
                            getString(
                                if (item?.isDone == true) R.string.habit_done else R.string.habit_pending,
                                item?.title ?: (lookup as TargetLookup.Found).item.title
                            )
                        }
                        "delete", "remove" -> {
                            val lookup = TrekoHabitManager.findTarget(id.takeIf { it != -1 }, title)
                            lookup.failureMessage("hábito", "hábitos", TrekoHabitManager.habitsFlow.value) { "#${it.id} ${it.title}" }
                                ?.let { return@withContext it }
                            val targetId = (lookup as TargetLookup.Found).item.id
                            val deletedTitle = (lookup as TargetLookup.Found).item.title
                            habitManager.deleteHabit(targetId)
                            getString(R.string.habit_deleted, deletedTitle)
                        }
                        "add_water", "drink_water", "tomar_agua", "increment_water" -> {
                            val nextCount = TrekoHabitManager.waterGlassesFlow.value + 1
                            habitManager.setWaterGlasses(nextCount)
                            getString(R.string.water_added, nextCount, 8)
                        }
                        "set_water" -> {
                            if (waterCount < 0) return@withContext getString(R.string.water_count_required)
                            habitManager.setWaterGlasses(waterCount)
                            getString(R.string.water_set, waterCount)
                        }
                        "list", "get" -> habitManager.listHabitsSummary()
                        else -> getString(R.string.habit_bad_action)
                    }
                }
                "manage_tasks" -> {
                    val taskManager = TrekoTaskManager(this@TrekoAssistantService)
                    val action = args.optString("action", "list").lowercase(Locale.ROOT)
                    val title = args.optString("title", "").trim()
                    val id = args.optInt("id", -1)

                    when (action) {
                        "add", "create" -> {
                            if (title.isBlank()) return@withContext getString(R.string.task_title_required)
                            val item = taskManager.addTask(title)
                            getString(R.string.task_added, item.title)
                        }
                        "toggle", "check", "complete" -> {
                            val lookup = TrekoTaskManager.findTarget(id.takeIf { it != -1 }, title)
                            lookup.failureMessage("tarea", "tareas", TrekoTaskManager.tasksFlow.value) { "#${it.id} ${it.title}" }
                                ?.let { return@withContext it }
                            val targetId = (lookup as TargetLookup.Found).item.id
                            taskManager.toggleTask(targetId)
                            val item = TrekoTaskManager.tasksFlow.value.find { it.id == targetId }
                            getString(
                                if (item?.isDone == true) R.string.task_done else R.string.task_pending,
                                item?.title ?: (lookup as TargetLookup.Found).item.title
                            )
                        }
                        "delete", "remove" -> {
                            val lookup = TrekoTaskManager.findTarget(id.takeIf { it != -1 }, title)
                            lookup.failureMessage("tarea", "tareas", TrekoTaskManager.tasksFlow.value) { "#${it.id} ${it.title}" }
                                ?.let { return@withContext it }
                            val targetId = (lookup as TargetLookup.Found).item.id
                            val deletedTitle = (lookup as TargetLookup.Found).item.title
                            taskManager.deleteTask(targetId)
                            getString(R.string.task_deleted, deletedTitle)
                        }
                        "list", "get" -> taskManager.listTasksSummary()
                        else -> getString(R.string.task_bad_action)
                    }
                }
                "manage_timer" -> {
                    val action = args.optString("action", "status").lowercase(Locale.ROOT)
                    val minutes = args.optInt("minutes", 0)
                    val seconds = args.optInt("seconds", 0)
                    val label = args.optString("label", "").trim()
                    val timerId = args.optInt("timer_id", 0)
                    val modeStr = args.optString("mode", "").lowercase(Locale.ROOT)

                    when (action) {
                        "start", "iniciar", "add", "agregar", "crear", "set", "fijar", "poner" -> {
                            when {
                                minutes > 0 || seconds > 0 -> {
                                    val created = TrekoTimerManager.addNamedTimer(
                                        minutes = minutes,
                                        seconds = seconds,
                                        label = label
                                    )
                                    val totalDesc = formatTimerDuration(created.totalSeconds)
                                    if (label.isBlank()) {
                                        getString(R.string.timer_started, totalDesc)
                                    } else {
                                        getString(R.string.timer_started_label, created.label, totalDesc)
                                    }
                                }
                                modeStr.isNotBlank() -> {
                                    when {
                                        modeStr.contains("pomo") -> TrekoTimerManager.setMode(TimerMode.POMODORO)
                                        modeStr.contains("corto") || modeStr.contains("short") -> TrekoTimerManager.setMode(TimerMode.SHORT_BREAK)
                                        modeStr.contains("largo") || modeStr.contains("long") -> TrekoTimerManager.setMode(TimerMode.LONG_BREAK)
                                    }
                                    TrekoTimerManager.start()
                                    TrekoTimerManager.getStatusDescription()
                                }
                                // Resuming a Pomodoro paused halfway needs no duration.
                                label.isBlank() && TrekoTimerManager.timerState.value.let {
                                    !it.isRunning && it.remainingSeconds in 1 until it.totalSeconds
                                } -> {
                                    TrekoTimerManager.start()
                                    TrekoTimerManager.getStatusDescription()
                                }
                                // No duration: ask instead of guessing one.
                                else -> getString(R.string.timer_ask_duration)
                            }
                        }
                        "cancel", "cancelar", "delete", "eliminar", "remove", "borrar" -> {
                            when {
                                label.equals("all", ignoreCase = true) || label.equals("todos", ignoreCase = true) -> {
                                    val n = TrekoTimerManager.cancelAllNamedTimers()
                                    getString(R.string.timer_cancelled_all, n)
                                }
                                timerId > 0 -> {
                                    if (TrekoTimerManager.cancelNamedTimer(timerId)) {
                                        getString(R.string.timer_cancelled_id, timerId)
                                    } else {
                                        getString(R.string.timer_not_found_id, timerId)
                                    }
                                }
                                label.isNotBlank() -> {
                                    val removed = TrekoTimerManager.cancelNamedTimerByLabel(label)
                                    if (removed != null) {
                                        getString(R.string.timer_cancelled_label, removed.label)
                                    } else {
                                        getString(R.string.timer_no_match, label)
                                    }
                                }
                                else -> {
                                    val active = TrekoTimerManager.namedTimers.value
                                    if (active.size == 1) {
                                        TrekoTimerManager.cancelNamedTimer(active.first().id)
                                        getString(R.string.timer_cancelled_label, active.first().label)
                                    } else {
                                        TrekoTimerManager.pause()
                                        TrekoTimerManager.getStatusDescription()
                                    }
                                }
                            }
                        }
                        "pause", "pausar", "detener" -> {
                            TrekoTimerManager.pause()
                            TrekoTimerManager.getStatusDescription()
                        }
                        "toggle", "alternar" -> {
                            TrekoTimerManager.toggle()
                            TrekoTimerManager.getStatusDescription()
                        }
                        "reset", "reiniciar" -> {
                            TrekoTimerManager.reset()
                            TrekoTimerManager.getStatusDescription()
                        }
                        else -> TrekoTimerManager.getStatusDescription()
                    }
                }
                "control_flashlight" -> {
                    val cm = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                    if (cm == null) {
                        getString(R.string.flashlight_unavailable)
                    } else {
                        val cameraId = cm.cameraIdList.firstOrNull() ?: return@withContext getString(R.string.flashlight_unavailable)
                        val action = args.optString("action", "toggle").lowercase(Locale.ROOT)
                        val newState = when (action) {
                            "turn_on", "on", "encender", "prender" -> true
                            "turn_off", "off", "apagar" -> false
                            else -> !isTorchOn
                        }
                        try {
                            cm.setTorchMode(cameraId, newState)
                            isTorchOn = newState
                            getString(if (newState) R.string.flashlight_on else R.string.flashlight_off)
                        } catch (e: Exception) {
                            TrekoLogBuffer.w(TAG, "Flashlight failed: ${e.message}")
                            getString(R.string.flashlight_failed)
                        }
                    }
                }
                "open_app" -> {
                    val appName = args.optString("app_name", "").trim().lowercase(Locale.ROOT)
                    if (appName.isBlank()) return@withContext getString(R.string.app_name_required)
                    val pm = packageManager
                    val knownPackages = mapOf(
                        "spotify" to "com.spotify.music",
                        "youtube" to "com.google.android.youtube",
                        "whatsapp" to "com.whatsapp",
                        "netflix" to "com.netflix.mediaclient",
                        "home assistant" to "io.homeassistant.companion.android",
                        "chrome" to "com.android.chrome",
                        "maps" to "com.google.android.apps.maps",
                        "google maps" to "com.google.android.apps.maps",
                        "telegram" to "org.telegram.messenger",
                        "instagram" to "com.instagram.android",
                        "tiktok" to "com.zhiliaoapp.musically",
                        "camera" to "com.android.camera",
                        "camara" to "com.sec.android.app.camera",
                        "cámara" to "com.sec.android.app.camera",
                        "settings" to "com.android.settings",
                        "ajustes" to "com.android.settings",
                        "configuracion" to "com.android.settings",
                        "configuración" to "com.android.settings"
                    )

                    var targetPkg = knownPackages[appName]
                    if (targetPkg == null) {
                        for ((k, v) in knownPackages) {
                            if (appName.contains(k) || k.contains(appName)) {
                                targetPkg = v
                                break
                            }
                        }
                    }

                    if (targetPkg == null) {
                        val launcherQuery = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        val launchable = pm.queryIntentActivities(launcherQuery, 0)
                        val match = launchable.find { resolveInfo ->
                            val label = resolveInfo.loadLabel(pm).toString().lowercase(Locale.ROOT)
                            label.contains(appName) || appName.contains(label)
                        }
                        targetPkg = match?.activityInfo?.packageName
                    }

                    if (targetPkg == null) {
                        return@withContext getString(R.string.app_not_found, appName)
                    }

                    val launchIntent = pm.getLaunchIntentForPackage(targetPkg)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                        getString(R.string.app_opening, appName)
                    } else {
                        getString(R.string.app_failed, appName)
                    }
                }
                "set_device_volume" -> {
                    val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    if (am == null) {
                        getString(R.string.volume_unavailable)
                    } else {
                        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val action = args.optString("action", "set").lowercase(Locale.ROOT)
                        val level = args.optInt("level", -1)

                        when (action) {
                            "up", "subir" -> {
                                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                                val newVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                                getString(R.string.volume_raised, (newVol * 100) / maxVol)
                            }
                            "down", "bajar" -> {
                                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                                val newVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                                getString(R.string.volume_lowered, (newVol * 100) / maxVol)
                            }
                            "mute", "silenciar" -> {
                                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                                getString(R.string.volume_muted)
                            }
                            "set", "fijar" -> {
                                if (level in 0..100) {
                                    val target = ((level / 100.0) * maxVol).toInt()
                                    am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
                                    getString(R.string.volume_set, level)
                                } else {
                                    getString(R.string.volume_invalid)
                                }
                            }
                            else -> getString(R.string.volume_current, (currentVol * 100) / maxVol)
                        }
                    }
                }
                "set_alarm" -> {
                    val hour = args.optInt("hour", -1)
                    val minute = args.optInt("minute", 0)
                    val message = args.optString("message", "").trim().ifBlank { getString(R.string.alarm_default_label) }
                    if (hour !in 0..23 || minute !in 0..59) {
                        return@withContext getString(R.string.alarm_invalid)
                    }
                    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        putExtra(AlarmClock.EXTRA_MESSAGE, message)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        startActivity(intent)
                        val timeStr = String.format(Locale.US, "%02d:%02d", hour, minute)
                        getString(R.string.alarm_set, timeStr)
                    } catch (e: Exception) {
                        TrekoLogBuffer.w(TAG, "Alarm failed: ${e.message}")
                        getString(R.string.alarm_failed)
                    }
                }
                // get_weather se renombró a get_home_weather: chocaba con la herramienta
                // del servidor Xiaozhi ("Duplicate tool names").
                "get_home_weather", "get_weather" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext getString(R.string.tool_ha_not_configured)
                    }
                    HomeAssistantClient(haUrl, haToken).getWeatherSummary()
                }
                "media_player_playback" -> {
                    val action = args.optString("action", "play_pause").trim()
                    val device = args.optString("device", "").trim()
                    val normDevice = normalizeAsciiLower(device)
                    val targetsPhone = isPhoneDeviceTarget(normDevice)
                    val normAction = normalizeAsciiLower(action)
                    if (targetsPhone || (device.isBlank() && phoneMediaPlayer != null)) {
                        when (normAction) {
                            "stop", "detener" -> {
                                stopPhoneRadio()
                                return@withContext "Radio/música detenida en el teléfono."
                            }
                            "pause", "pausar" -> {
                                val mp = phoneMediaPlayer
                                if (mp != null && mp.isPlaying) {
                                    mp.pause()
                                    return@withContext "Audio pausado en el teléfono."
                                }
                            }
                            "play", "resume", "reanudar", "play_pause" -> {
                                val mp = phoneMediaPlayer
                                if (mp != null) {
                                    if (mp.isPlaying) {
                                        mp.pause()
                                        return@withContext "Audio pausado en el teléfono."
                                    } else {
                                        mp.start()
                                        return@withContext "Audio reanudado en el teléfono."
                                    }
                                }
                            }
                        }
                        if (targetsPhone) {
                            return@withContext "No hay radio activa en el teléfono."
                        }
                    }
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext getString(R.string.tool_ha_not_configured)
                    }
                    val volume = if (args.has("volume_level")) args.optDouble("volume_level", 0.5).toFloat() else null
                    HomeAssistantClient(haUrl, haToken).mediaPlayerPlayback(device, action, volume)
                }
                "trigger_scene" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext getString(R.string.tool_ha_not_configured)
                    }
                    val scene = args.optString("scene", "").trim()
                    if (scene.isBlank()) return@withContext getString(R.string.scene_required)
                    HomeAssistantClient(haUrl, haToken).triggerScene(scene)
                }
                "view_camera" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext getString(R.string.tool_ha_not_configured)
                    }
                    val cameraQuery = args.optString("camera", "").trim()
                    val action = args.optString("action", "view").trim().lowercase(Locale.ROOT)
                    val normQuery = normalizeAsciiLower(cameraQuery)
                    val haClient = HomeAssistantClient(haUrl, haToken)

                    if (action == "dismiss" || action == "close" || action == "cerrar") {
                        dismissCameraFeed(closeGrid = true)
                        return@withContext getString(R.string.camera_dismissed)
                    }

                    val gridQueries = setOf(
                        "todas", "todas las camaras", "all", "all cameras",
                        "panel", "muro", "multicamara", "grid"
                    )
                    if (action == "list" || action == "grid" || action == "panel" || normQuery in gridQueries) {
                        val liveAll = action == "live" || action == "stream" ||
                            normQuery.contains("vivo") || normQuery.contains("live")
                        return@withContext openMultiCameraPanelInternal(prefs, liveAll = liveAll)
                    }

                    val snapshotPair = haClient.getCameraSnapshot(cameraQuery)
                    if (snapshotPair == null) {
                        TrekoLogBuffer.w(TAG, "view_camera failed for query='$cameraQuery'")
                        return@withContext haClient.describeCameraLookupFailure(cameraQuery)
                    }

                    val (camInfo, _) = snapshotPair
                    TrekoLogBuffer.i(TAG, "view_camera resolved '${camInfo.entityId}' (state=${camInfo.state})")
                    val isLive = action == "live" || action == "stream" ||
                        normQuery.contains("vivo") || normQuery.contains("live")
                    val matchedGroup = haClient.getDeduplicatedCameras().find {
                        it.primaryEntityId == camInfo.entityId ||
                            it.snapshotEntityId == camInfo.entityId ||
                            it.liveEntityId == camInfo.entityId
                    }
                    val resolvedName = matchedGroup?.friendlyName ?: camInfo.friendlyName
                    _activeCameraFeed.value = CameraFeedState(
                        entityId = matchedGroup?.snapshotEntityId?.ifBlank { camInfo.entityId } ?: camInfo.entityId,
                        friendlyName = resolvedName,
                        snapshotUrl = matchedGroup?.snapshotUrl?.ifBlank { camInfo.snapshotUrl } ?: camInfo.snapshotUrl,
                        haUrl = haUrl,
                        haToken = haToken,
                        isLive = isLive,
                        liveEntityId = matchedGroup?.liveEntityId
                    )
                    getString(R.string.camera_showing, resolvedName)
                }
                "analyze_camera" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext getString(R.string.tool_ha_not_configured)
                    }
                    val cameraQuery = args.optString("camera", "").trim()
                    val question = args.optString("question", "").trim()
                        .ifBlank { getString(R.string.analyze_default_question) }
                        .let { "$it ${getString(R.string.analyze_answer_rules)}" }
                    val haClient = HomeAssistantClient(haUrl, haToken)

                    val snapshotPair = haClient.getCameraSnapshot(cameraQuery)
                        ?: return@withContext haClient.describeCameraLookupFailure(cameraQuery).also {
                            TrekoLogBuffer.w(TAG, "analyze_camera failed for query='$cameraQuery'")
                        }

                    val (camInfo, bytes) = snapshotPair
                    _activeCameraFeed.value = CameraFeedState(
                        entityId = camInfo.entityId,
                        friendlyName = camInfo.friendlyName,
                        snapshotUrl = camInfo.snapshotUrl,
                        haUrl = haUrl,
                        haToken = haToken,
                        isLive = false
                    )

                    // Without LLM Vision there is nothing to call: show the picture and say why.
                    val skills = prefs.detectedSkills
                    if (skills.isNotEmpty() && TrekoSkills.LLM_VISION !in skills) {
                        return@withContext getString(R.string.analyze_no_vision, camInfo.friendlyName)
                    }
                    val visionAnalysis = haClient.analyzeCameraWithLlmVision(camInfo.entityId, question)
                    if (!visionAnalysis.isNullOrBlank()) {
                        getString(R.string.analyze_result, camInfo.friendlyName, visionAnalysis.trim())
                    } else {
                        TrekoLogBuffer.w(TAG, "analyze_camera: no vision answer for ${camInfo.entityId} (${bytes.size / 1024} KB snapshot)")
                        getString(R.string.analyze_failed, camInfo.friendlyName)
                    }
                }
                "view_all_cameras" -> {
                    val liveAll = args.optBoolean("live", false)
                    openMultiCameraPanelInternal(prefs, liveAll = liveAll)
                }
                "watch_cameras" -> {
                    cameraWatch?.handleTool(args) ?: "La vigilancia de cámaras no está lista todavía."
                }
                "get_3d_printer_status" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext getString(R.string.tool_ha_not_configured)
                    }
                    HomeAssistantClient(haUrl, haToken).get3dPrinterStatus()
                }
                "home_status_summary" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext getString(R.string.tool_ha_not_configured)
                    }
                    HomeAssistantClient(haUrl, haToken).getHomeStatusSummary()
                }
                "play_music_or_radio" -> {
                    val query = args.optString("query", "").trim()
                    if (query.isBlank()) {
                        return@withContext "Por favor indica qué canción, artista, género o estación de radio quieres escuchar."
                    }
                    val device = args.optString("device", "").trim()
                    val mediaType = args.optString("media_type", "auto").trim().ifBlank { "auto" }
                    val provider = args.optString("provider", "auto").trim().ifBlank { "auto" }
                    executePlayMusicOrRadio(prefs, query, device, mediaType, provider)
                }
                "manage_workflows" -> {
                    val action = normalizeAsciiLower(args.optString("action", "list"))
                    val name = args.optString("name", "")
                        .ifBlank { args.optString("workflow", "") }
                        .trim()
                    val description = args.optString("description", "").trim()
                    val stepsJson = args.optJSONArray("steps")?.toString()
                        ?: args.optString("steps", "").trim()

                    when (action) {
                        "list", "listar", "ver", "mostrar" -> workflowManager.listWorkflowsSummary()
                        "run", "ejecutar", "iniciar", "activar", "start" -> {
                            if (name.isBlank()) {
                                "Indica el nombre de la rutina a ejecutar. Disponibles:\n${workflowManager.listWorkflowsSummary()}"
                            } else {
                                workflowManager.runWorkflow(name, buildWorkflowActions(prefs))
                            }
                        }
                        "create", "crear", "add", "agregar", "guardar" -> {
                            workflowManager.createCustomWorkflow(
                                name = name,
                                description = description,
                                stepsJson = stepsJson
                            )
                        }
                        "delete", "eliminar", "borrar", "remove" -> {
                            if (name.isBlank()) {
                                "Error: indica el nombre o ID de la rutina a eliminar."
                            } else {
                                workflowManager.deleteWorkflow(name)
                            }
                        }
                        else -> workflowManager.listWorkflowsSummary()
                    }
                }
                "meditation" -> meditation?.handleTool(args) ?: getString(R.string.meditation_tool_none)
                "manage_proactive_assistant" -> {
                    val action = normalizeAsciiLower(args.optString("action", "status"))
                    val proactiveManager = getOrCreateProactiveManager(this@TrekoAssistantService)
                    when (action) {
                        "deliver_pending", "read_pending_alert", "get_pending", "pending", "leer", "read", "briefing" -> {
                            val activeVoice = activeVoiceProactiveInsight
                            // A meditation cue rides the same trigger ("lee tu aviso pendiente"),
                            // but a real alert being delivered always comes first.
                            if (activeVoice == null) {
                                activeMeditationCue?.let { cue ->
                                    cue.consumed = true
                                    return@withContext getString(R.string.meditation_tool_cue, cue.text)
                                }
                            }
                            // Stale ones (an alert held for hours) are not read as news.
                            prunePendingInsights(this@TrekoAssistantService)
                            // Most important first (a camera alert before any briefing).
                            val pending = _pendingProactiveInsight.value
                            val recent = proactiveManager.lastDeliveredInsight?.takeIf {
                                (System.currentTimeMillis() - it.createdAtMs) < 180_000L
                            }
                            val chosen = activeVoice ?: pending ?: recent
                            if (chosen != null) {
                                activeVoiceProactiveInsight = null
                                removePendingInsight(chosen.id)
                                if (chosen === pending) AlertRecording.markDelivered(chosen, spoken = true)
                                cancelProactiveNotification(this@TrekoAssistantService)
                                readExactlyToolResult(
                                    if (chosen === pending) withHeldAgeNote(this@TrekoAssistantService, chosen) else chosen
                                )
                            } else {
                                // "What were you going to tell me?": a quick look at the other
                                // topics, never a slow camera scan.
                                val fresh = proactiveManager.evaluateNow(
                                    includeCameraScan = false,
                                    force = true,
                                    assistantName = resolveAssistantName(this@TrekoAssistantService)
                                )?.let { AlertRecording.recordInsight(it, trace = null) }
                                if (fresh != null) {
                                    AlertRecording.markDelivered(fresh, spoken = true)
                                    readExactlyToolResult(fresh)
                                } else {
                                    "No hay avisos pendientes ni anomalías en este momento."
                                }
                            }
                        }
                        "check_now", "check", "revisar", "checar" -> {
                            val fresh = proactiveManager.evaluateNow(
                                includeCameraScan = prefs.proactiveCameraAnomalyEnabled,
                                force = true,
                                assistantName = resolveAssistantName(this@TrekoAssistantService)
                            )?.let { AlertRecording.recordInsight(it, trace = null) }
                            if (fresh != null) {
                                // The model reads it to the user as the answer.
                                AlertRecording.markDelivered(fresh, spoken = true)
                                "Resultado del chequeo proactivo (${fresh.title}): ${fresh.spokenSpeechText}"
                            } else {
                                "Chequeo completado: todo en calma."
                            }
                        }
                        "snooze", "posponer", "mas_tarde", "luego", "delay" -> {
                            val topic = args.optString("topic", "water").ifBlank { "water" }
                            val durationMin = args.optInt("duration_minutes", 120).coerceIn(15, 720)
                            proactiveManager.snoozeTopic(topic = topic, durationMinutes = durationMin)
                        }
                        "set_speak_out_loud", "configure", "config" -> {
                            val newVal = if (args.has("speak_out_loud")) {
                                args.optBoolean("speak_out_loud")
                            } else {
                                !prefs.proactiveSpeakOutLoud
                            }
                            prefs.proactiveSpeakOutLoud = newVal
                            val assistantName = resolveAssistantName(this@TrekoAssistantService)
                            if (newVal) {
                                "Voz espontánea proactiva ACTIVADA. Ahora $assistantName podrá iniciar conversaciones hablando cuando detecte algo."
                            } else {
                                "Voz espontánea proactiva DESACTIVADA. $assistantName enviará primero una notificación '$assistantName tiene algo que decirte' sin reproducir audio automático."
                            }
                        }
                        else -> {
                            val assistantName = resolveAssistantName(this@TrekoAssistantService)
                            val mode = if (prefs.proactiveSpeakOutLoud) "hablar automáticamente" else "solo notificación silenciosa ('$assistantName tiene algo que decirte')"
                            val pendingText = _pendingProactiveInsight.value?.let { " Hay un aviso pendiente: ${it.summaryText}." } ?: " No hay avisos pendientes."
                            "Asistente proactivo=${if (prefs.proactiveEnabled) "activado" else "desactivado"}, modo=$mode, intervalo=${prefs.proactiveIntervalMinutes} min, revisión de cámaras=${prefs.proactiveCameraAnomalyEnabled}, horario silencioso=${prefs.proactiveQuietHoursEnabled}.$pendingText"
                        }
                    }
                }
                else -> getString(R.string.tool_unknown, name)
            }
        } catch (e: Exception) {
            TrekoLogBuffer.w(TAG, "Tool $name failed: ${e.message}")
            getString(R.string.tool_error, name, e.message.orEmpty().take(160))
        }
    }

    /** "10 min", "45 s", "1 min 30 s". */
    private fun formatTimerDuration(totalSeconds: Int): String {
        val min = totalSeconds / 60
        val sec = totalSeconds % 60
        return when {
            min == 0 -> getString(R.string.timer_seconds, sec)
            sec == 0 -> getString(R.string.timer_minutes, min)
            else -> getString(R.string.timer_minutes_seconds, min, sec)
        }
    }

    /**
     * Gate for Home Assistant calls from the model: unlocking, disarming or opening a garage or
     * gate only runs on a second call with confirm=true for the same action within a minute.
     */
    private fun sensitiveGate(confirmed: Boolean) = ActionGate { plan ->
        val kind = SensitiveActions.classify(plan) ?: return@ActionGate null
        when (val decision = sensitiveActionGuard.check(plan.key, confirmed)) {
            SensitiveActionGuard.Decision.Proceed -> {
                TrekoLogBuffer.i(TAG, "Sensitive action confirmed: ${plan.key}")
                null
            }
            is SensitiveActionGuard.Decision.Confirm -> {
                TrekoLogBuffer.i(TAG, "Sensitive action held for confirmation (${decision.token}): ${plan.key}")
                val names = plan.targets.joinToString(", ") { it.friendlyName.ifBlank { it.entityId } }
                    .ifBlank { getString(R.string.sensitive_all_targets) }
                val what = when (kind) {
                    SensitiveKind.UNLOCK -> getString(R.string.sensitive_unlock, names)
                    SensitiveKind.DISARM -> getString(R.string.sensitive_disarm, names)
                    SensitiveKind.OPEN_ACCESS -> getString(R.string.sensitive_open, names)
                }
                getString(R.string.sensitive_confirm, what)
            }
        }
    }

    private suspend fun openMultiCameraPanelInternal(
        prefs: TrekoPreferences,
        liveAll: Boolean = false
    ): String {
        val haUrl = prefs.haUrl.trim()
        val haToken = prefs.haToken.trim()
        if (haUrl.isBlank() || haToken.isBlank()) {
            return getString(R.string.tool_ha_not_configured)
        }
        val groups = HomeAssistantClient(haUrl, haToken).getDeduplicatedCameras()
        if (groups.isEmpty()) {
            return getString(R.string.camera_none)
        }
        val feeds = groups.map { group ->
            CameraFeedState(
                entityId = group.snapshotEntityId.ifBlank { group.primaryEntityId },
                friendlyName = group.friendlyName,
                snapshotUrl = group.snapshotUrl,
                haUrl = haUrl,
                haToken = haToken,
                isLive = liveAll,
                liveEntityId = group.liveEntityId
            )
        }
        _activeCameraFeed.value = null
        _activeMultiCameraGrid.value = MultiCameraGridState(
            cameras = feeds,
            haUrl = haUrl,
            haToken = haToken,
            isLiveAll = liveAll
        )
        // Names only: entity ids and stream details make the model read them out.
        val names = groups.take(8).joinToString(", ") { it.friendlyName } +
            (if (groups.size > 8) ", …" else "")
        return getString(
            if (liveAll) R.string.camera_wall_opened_live else R.string.camera_wall_opened,
            names
        )
    }

    private suspend fun executePlayMusicOrRadio(
        prefs: TrekoPreferences,
        query: String,
        device: String,
        mediaType: String,
        provider: String
    ): String {
        val normDevice = normalizeAsciiLower(device)
        val haUrl = prefs.haUrl.trim()
        val haToken = prefs.haToken.trim()
        val targetsPhone = isPhoneDeviceTarget(normDevice) ||
            (normDevice.isBlank() && (haUrl.isBlank() || haToken.isBlank()))
        if (targetsPhone) {
            return handlePhoneMusicOrRadio(query, provider)
        }
        if (haUrl.isBlank() || haToken.isBlank()) {
            return getString(R.string.tool_ha_not_configured)
        }
        return HomeAssistantClient(haUrl, haToken).playMusicOrRadio(
            query = query,
            targetDevice = device,
            mediaType = mediaType,
            provider = provider
        )
    }

    private suspend fun handlePhoneMusicOrRadio(query: String, provider: String): String {
        val normProvider = normalizeAsciiLower(provider)
        if (normProvider == "spotify") {
            if (tryOpenSpotifySearch(query)) {
                return "Abriendo Spotify en el teléfono para '$query'."
            }
        }
        val station = radioBrowserClient.findBestStation(query)
        if (station != null) {
            val started = playPhoneRadioStream(station.streamUrl)
            if (started) {
                val origin = station.country.ifBlank { "Radio" }
                return "Reproduciendo '${station.name}' ($origin) en el teléfono."
            }
            return try {
                val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(station.streamUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(viewIntent)
                "Abriendo transmisión de radio '${station.name}' en el teléfono."
            } catch (e: Exception) {
                "Encontré '${station.name}' pero no pude reproducir el stream directamente: ${station.streamUrl}"
            }
        }
        if (normProvider != "tunein" && tryOpenSpotifySearch(query)) {
            return "Abriendo Spotify en el teléfono para buscar '$query'."
        }
        return "No encontré una estación de radio en línea ni aplicación de música disponible para '$query'."
    }

    private suspend fun playPhoneRadioStream(streamUrl: String): Boolean {
        stopPhoneRadio()
        return withContext(Dispatchers.IO) {
            val prepared = withTimeoutOrNull(6000L) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val mp = MediaPlayer()
                    mp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    mp.setOnPreparedListener { readyPlayer ->
                        try {
                            readyPlayer.start()
                            synchronized(this@TrekoAssistantService) {
                                phoneMediaPlayer?.let { prev ->
                                    if (prev !== readyPlayer) {
                                        try { prev.release() } catch (_: Exception) {}
                                    }
                                }
                                phoneMediaPlayer = readyPlayer
                            }
                            if (cont.isActive) cont.resume(true)
                        } catch (e: Exception) {
                            try { readyPlayer.release() } catch (_: Exception) {}
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                    mp.setOnErrorListener { failedPlayer, what, extra ->
                        TrekoLogBuffer.w(TAG, "Phone MediaPlayer error what=$what extra=$extra for $streamUrl")
                        try { failedPlayer.release() } catch (_: Exception) {}
                        synchronized(this@TrekoAssistantService) {
                            if (phoneMediaPlayer === failedPlayer) {
                                phoneMediaPlayer = null
                            }
                        }
                        if (cont.isActive) cont.resume(false)
                        true
                    }
                    mp.setOnCompletionListener { finishedPlayer ->
                        synchronized(this@TrekoAssistantService) {
                            if (phoneMediaPlayer === finishedPlayer) {
                                phoneMediaPlayer = null
                            }
                        }
                        try { finishedPlayer.release() } catch (_: Exception) {}
                    }
                    cont.invokeOnCancellation {
                        try { mp.release() } catch (_: Exception) {}
                    }
                    try {
                        mp.setDataSource(streamUrl)
                        mp.prepareAsync()
                    } catch (e: Exception) {
                        TrekoLogBuffer.w(TAG, "Phone MediaPlayer setup failed: ${e.message}")
                        try { mp.release() } catch (_: Exception) {}
                        if (cont.isActive) cont.resume(false)
                    }
                }
            } ?: false
            if (!prepared) {
                stopPhoneRadio()
            }
            prepared
        }
    }

    private fun stopPhoneRadio() {
        synchronized(this) {
            val current = phoneMediaPlayer ?: return
            phoneMediaPlayer = null
            try {
                if (current.isPlaying) {
                    current.stop()
                }
            } catch (_: Exception) {
            }
            try {
                current.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun tryOpenSpotifySearch(query: String): Boolean {
        val spotifyIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("spotify:search:${Uri.encode(query)}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            if (spotifyIntent.resolveActivity(packageManager) != null) {
                startActivity(spotifyIntent)
                true
            } else {
                val launchIntent = packageManager.getLaunchIntentForPackage("com.spotify.music")
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            TrekoLogBuffer.w(TAG, "Spotify search launch failed: ${e.message}")
            false
        }
    }

    private fun buildWorkflowActions(prefs: TrekoPreferences): WorkflowActions {
        val haUrl = prefs.haUrl.trim()
        val haToken = prefs.haToken.trim()
        fun requireHaClient(): HomeAssistantClient? {
            if (haUrl.isBlank() || haToken.isBlank()) return null
            return HomeAssistantClient(haUrl, haToken)
        }
        return object : WorkflowActions {
            override suspend fun haControl(
                target: String,
                action: String,
                brightness: Int,
                color: String,
                source: String
            ): String {
                val client = requireHaClient()
                    ?: return "Home Assistant no está configurado en Ajustes de Treko."
                return client.controlDevice(
                    deviceQuery = target,
                    accion = action,
                    brillo = brightness,
                    colorOrTemp = color,
                    source = source
                )
            }

            override suspend fun playMedia(
                query: String,
                device: String,
                mediaType: String,
                provider: String
            ): String = executePlayMusicOrRadio(prefs, query, device, mediaType, provider)

            override suspend fun broadcast(message: String, target: String): String {
                val client = requireHaClient()
                    ?: return "Home Assistant no está configurado en Ajustes de Treko."
                return client.broadcastMessage(message, target)
            }

            override suspend fun triggerScene(scene: String): String {
                val client = requireHaClient()
                    ?: return "Home Assistant no está configurado en Ajustes de Treko."
                return client.triggerScene(scene)
            }

            override suspend fun openCameraWall(live: Boolean): String =
                openMultiCameraPanelInternal(prefs, liveAll = live)

            override suspend fun setCinemaMode(enabled: Boolean): String {
                Companion.setCinemaMode(this@TrekoAssistantService, enabled)
                return if (enabled) "Modo cine activado." else "Modo cine desactivado."
            }

            override suspend fun startTimer(minutes: Int, seconds: Int, label: String): String {
                val safeMin = if (minutes <= 0 && seconds <= 0) 1 else minutes
                val created = TrekoTimerManager.addNamedTimer(
                    minutes = safeMin,
                    seconds = seconds,
                    label = label
                )
                return "Temporizador '${created.label}' iniciado (${created.totalSeconds}s)."
            }

            override suspend fun getWeather(): String {
                val client = requireHaClient()
                    ?: return "Home Assistant no está configurado en Ajustes de Treko."
                return client.getWeatherSummary()
            }

            override suspend fun getHomeSummary(): String {
                val client = requireHaClient()
                    ?: return "Home Assistant no está configurado en Ajustes de Treko."
                return client.getHomeStatusSummary()
            }

            override suspend fun getTasksSummary(): String =
                TrekoTaskManager(this@TrekoAssistantService).listTasksSummary()

            override suspend fun getHabitsSummary(): String =
                TrekoHabitManager(this@TrekoAssistantService).listHabitsSummary()

            override suspend fun startMeditation(type: String, minutes: Int): String {
                val args = JSONObject().put("action", "start")
                if (type.isNotBlank()) args.put("type", type)
                if (minutes > 0) args.put("minutes", minutes)
                return meditation?.handleTool(args) ?: getString(R.string.meditation_tool_none)
            }
        }
    }

    private fun isPhoneDeviceTarget(normalizedDevice: String): Boolean {
        if (normalizedDevice.isBlank()) return false
        return normalizedDevice in setOf(
            "telefono", "celular", "movil", "phone", "aqui", "treko", "este dispositivo"
        ) || normalizedDevice.contains("telefono") ||
            normalizedDevice.contains("celular") ||
            normalizedDevice.contains("phone") ||
            normalizedDevice.contains("treko")
    }

    private fun normalizeAsciiLower(input: String): String {
        val decomposed = Normalizer.normalize(input.trim(), Normalizer.Form.NFD)
        return Regex("\\p{InCombiningDiacriticalMarks}+").replace(decomposed, "")
            .lowercase(Locale.ROOT)
    }

    fun abortConversation() {
        if (activeMeditationCue != null) {
            meditation?.onConversationStarted()
            cancelMeditationCue()
        }
        listeningTurnGeneration++
        biometricCheckJob?.cancel()
        complementaryBioJob?.cancel()
        isComplementaryBioCheckInFlight = false
        bargeInVoicedFrames = 0
        foreignVoiceResetJob?.cancel()
        cancelThinkingWatchdog()
        proactiveTtsFallbackJob?.cancel()
        activeVoiceProactiveInsight = null
        ttsDrainingJob?.cancel()
        try {
            localTts?.stop()
        } catch (_: Exception) {
        }
        isClosingTurn = false
        speechDetected = false
        _fullDuplexIndicator.value = FullDuplexIndicator.INACTIVE

        val shouldAbortRemote = biometricTurnGate.state == BiometricTurnGate.TurnAuthState.DISABLED ||
            biometricTurnGate.state == BiometricTurnGate.TurnAuthState.VERIFIED ||
            _serviceState.value == TrekoState.SPEAKING ||
            _serviceState.value == TrekoState.THINKING

        synchronized(opusSendOrderLock) {
            biometricTurnGate.reset()
            nearFieldGate.reset()
            bargeInGate.reset()
            complementaryQueue.reset(biometricEnabled = effectiveBiometricGateEnabled)
            speechDenoiser?.reset()
            clearPendingOpusPackets()
            opusCodec?.stopPlayback()
            opusCodec?.resetEncoder()
            NativeAudioEngine.resetSoftwareAec()
        }
        if (shouldAbortRemote) {
            wsClient?.abortSpeaking(reason = "user_cancelled")
        }
        transitionState(
            TrekoState.IDLE,
            if (_isMuted.value) TrekoEmotion.SLEEPY else TrekoEmotion.RELAX
        )
        _subtitleText.value = ""
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasMicPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_TOGGLE_CONVERSATION -> {
                if (_serviceState.value == TrekoState.IDLE || _serviceState.value == TrekoState.ERROR) {
                    beginConversation(TrekoPreferences(this).customWakeWord)
                } else {
                    abortConversation()
                }
            }
            ACTION_TOGGLE_MUTE -> {
                toggleMute(this)
            }
            ACTION_TOGGLE_CINEMA_MODE -> {
                toggleCinemaMode(this)
            }
            ACTION_STOP_WATCHES -> cameraWatch?.stopAll()
            ACTION_RELOAD_CONFIG -> applyLatestPreferences()
            ACTION_CONSUME_PROACTIVE_INSIGHT -> {
                cancelProactiveNotification(this)
                val insight = takePendingInsight(intent?.getStringExtra(EXTRA_INSIGHT_ID))
                if (insight != null) {
                    serviceScope.launch {
                        deliverProactiveInsightByVoice(insight, fromUserTap = true)
                    }
                } else {
                    beginConversation(TrekoPreferences(this).customWakeWord)
                }
            }
            ACTION_DISMISS_PROACTIVE_INSIGHT -> {
                dismissPendingProactiveInsight(this, intent?.getStringExtra(EXTRA_INSIGHT_ID))
            }
            ACTION_TRIGGER_PROACTIVE_CHECK -> {
                serviceScope.launch {
                    runProactiveCheck(force = true, overrideSpeak = null)
                }
            }
        }

        if (!_isMuted.value && !NativeAudioEngine.isRecording) {
            val prefs = TrekoPreferences(this)
            NativeAudioEngine.startRecording(serviceScope, preferVoiceCommunication = prefs.audioEchoModeEnabled)
        }
        val client = wsClient
        if (client != null && !client.isConnected) {
            _connectionStatus.value = WsConnectionStatus.CONNECTING
            client.connect()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val prefs = TrekoPreferences(this)
        if (cameraWatch?.hasActiveWatches == true) {
            // A watch the user asked for must keep running, or the alert would never come.
            TrekoLogBuffer.i(TAG, "Task removed; staying alive for active camera watches.")
        } else if (!prefs.isAlwaysListeningInBackground) {
            TrekoLogBuffer.i(TAG, "Task removed and background listening disabled; stopping service.")
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraWatch?.release()
        cameraWatch = null
        _activeWatchAreas.value = emptyList()
        _activeWatches.value = emptyList()
        isServiceRunning = false
        isTrainingMode = false

        if (isTorchOn) {
            try {
                val cm = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                val camId = cm?.cameraIdList?.firstOrNull()
                if (camId != null) cm.setTorchMode(camId, false)
            } catch (_: Exception) {
            }
            isTorchOn = false
        }

        biometricCheckJob?.cancel()
        foreignVoiceResetJob?.cancel()
        proactiveLoopJob?.cancel()
        proactiveTtsFallbackJob?.cancel()
        // Ends the session; its room restore runs even as the scope is cancelled (and is on disk).
        meditation?.shutdown()
        activeMeditationCue?.done?.complete(false)
        activeMeditationCue = null
        try {
            localTts?.stop()
            localTts?.shutdown()
        } catch (_: Exception) {
        }
        localTts = null
        isLocalTtsReady.set(false)
        abandonAudioFocus()
        NativeAudioEngine.setPlaybackActive(false)
        NativeAudioEngine.onPcmData = null
        NativeAudioEngine.stopRecording()

        synchronized(wakeWordLock) {
            wakeWordReleased = true
            wakeWordDetector?.release()
            wakeWordDetector = null
        }

        speechDenoiser?.release()
        speechDenoiser = null

        speakerBiometricEngine?.release()
        speakerBiometricEngine = null

        biometricTurnGate.reset()
        nearFieldGate.reset()

        playbackCollectorJob?.cancel()
        opusCodec?.release()
        opusCodec = null

        wsClient?.release()
        wsClient = null

        serviceScope.cancel()
        _serviceState.value = TrekoState.IDLE
        _currentEmotion.value = TrekoEmotion.RELAX
        _subtitleText.value = ""
        _playbackAudioLevel.value = AudioLevel()
        _lastBiometricResult.value = null
        stopPhoneRadio()
        _connectionStatus.value = WsConnectionStatus.DISCONNECTED
        _activeSessionId.value = null
        _activeCameraFeed.value = null
        _activeMultiCameraGrid.value = null
        if (instance == this) {
            instance = null
        }
        TrekoLogBuffer.i(TAG, "TrekoAssistantService destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        manager.createNotificationChannel(channel)

        TimerAlarms.ensureChannel(this)
        ensureProactiveNotificationChannel(this)
    }

    private fun startProactiveLoop() {
        proactiveLoopJob?.cancel()
        proactiveLoopJob = serviceScope.launch {
            while (isActive) {
                delay(60_000L)
                // A held alert that got stale leaves the banner even when nothing else happens.
                prunePendingInsights(this@TrekoAssistantService)
                val prefs = TrekoPreferences(this@TrekoAssistantService)
                if (!prefs.proactiveEnabled) continue
                val intervalMs = prefs.proactiveIntervalMinutes.toLong() * 60_000L
                val now = System.currentTimeMillis()
                if ((now - lastProactiveCheckMs) >= intervalMs) {
                    runProactiveCheck(force = false, overrideSpeak = null)
                }
            }
        }
    }

    /**
     * Runs one proactive check ([camerasOnly] = the camera sentinel). Returns false when the
     * check did not run (another one in progress, turned off, or it failed), true otherwise,
     * whether or not it found something to say.
     */
    private suspend fun runProactiveCheck(
        force: Boolean,
        overrideSpeak: Boolean? = null,
        camerasOnly: Boolean = false
    ): Boolean {
        val trace = coroutineContext[SentinelRunTrace]
        if (_isProactiveCheckRunning.value) {
            trace?.decide("skipped", "another proactive check is running")
            return false
        }
        _isProactiveCheckRunning.value = true
        // A detector-triggered camera check does not reset the regular schedule.
        if (!camerasOnly) lastProactiveCheckMs = System.currentTimeMillis()
        try {
            val prefs = TrekoPreferences(this)
            val enabled = if (camerasOnly) prefs.cameraSentinelEnabled else prefs.proactiveEnabled
            if (!force && !enabled) {
                trace?.decide("skipped", "camera sentinel is turned off")
                return false
            }
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val inQuietHours = TrekoProactiveManager.isWithinQuietHours(hour, prefs.proactiveQuietHoursEnabled)

            val manager = getOrCreateProactiveManager(this)
            val insight = manager.evaluateNow(
                // The sentinel is already gated by its own setting; the periodic check by the scan one.
                includeCameraScan = camerasOnly || prefs.proactiveCameraAnomalyEnabled,
                force = force,
                hourOfDay = hour,
                assistantName = resolveAssistantName(this),
                camerasOnly = camerasOnly
            )
            if (insight == null) {
                if (!camerasOnly) TrekoLogBuffer.d(TAG, "Proactive check: nothing to say right now.")
                // Every sentinel run that looked is in the alerts log, "no news" included.
                trace?.let { AlertRecording.recordSentinelCheck(this, it) }
                return true
            }
            var recorded = AlertRecording.recordInsight(insight, trace)
            if (recorded.category == ProactiveCategory.CAMERA_ANOMALY) {
                // Always a high-importance notification with the picture, spoken or not.
                val photo = AlertRecording.announceCameraAlert(this, recorded, serviceScope, currentWatchHaClient(), trace)
                recorded = AlertRecording.withPhotoNote(this, recorded, photo)
            }

            val speakOutLoud = overrideSpeak ?: prefs.proactiveSpeakOutLoud
            // A meditation keeps Treko quiet; only a camera alert interrupts it (then it resumes).
            val meditating = meditation?.isActive == true
            val urgentDuringMeditation = meditating && recorded.category == ProactiveCategory.CAMERA_ANOMALY
            // Only a meditation cue may be cut for it; a real turn (the user talking, the
            // opening line) is never cut: then the alert waits, as at any other busy moment.
            val canSpeakAutomatically = speakOutLoud &&
                (_serviceState.value == TrekoState.IDLE || urgentDuringMeditation && activeMeditationCue != null) &&
                !_isMuted.value &&
                !_isCinemaMode.value &&
                (!meditating || urgentDuringMeditation) &&
                (!inQuietHours || force)

            TrekoLogBuffer.i(
                TAG,
                "Proactive insight [${insight.category}] speakOutLoud=$speakOutLoud canSpeakNow=$canSpeakAutomatically: ${insight.summaryText}"
            )

            if (canSpeakAutomatically && urgentDuringMeditation) {
                meditation?.pauseForAlert()
                cancelMeditationCue()
                withTimeoutOrNull(3_000L) { while (_serviceState.value != TrekoState.IDLE) delay(100L) }
            }
            if (canSpeakAutomatically && _serviceState.value != TrekoState.IDLE) {
                // The user started talking meanwhile: their turn is not cut.
                trace?.decide("alert_notified", reason = "assistant busy (${_serviceState.value})", override = true)
                holdInsightForLater(this, recorded)
            } else if (canSpeakAutomatically) {
                trace?.decide("alert_spoken", override = true)
                deliverProactiveInsightByVoice(recorded, fromUserTap = false)
            } else {
                trace?.decide(
                    "alert_notified",
                    reason = when {
                        !speakOutLoud -> "speaking proactively is off"
                        inQuietHours && !force -> "quiet hours"
                        _isMuted.value -> "microphone muted"
                        _isCinemaMode.value -> "cinema mode"
                        meditating -> "meditation in progress"
                        else -> "assistant busy (${_serviceState.value})"
                    },
                    override = true
                )
                holdInsightForLater(this, recorded)
            }
            return true
        } catch (e: Exception) {
            TrekoLogBuffer.w(TAG, "Error running proactive check: ${e.message}")
            trace?.decide("error", e.message ?: e.javaClass.simpleName, override = true)
            return false
        } finally {
            _isProactiveCheckRunning.value = false
        }
    }

    private suspend fun deliverProactiveInsightByVoice(queued: ProactiveInsight, fromUserTap: Boolean) {
        // A meditation never talks over an alert (also one the user tapped): pause it and cut
        // its cue, so read_pending_alert reads the alert and not the cue.
        if (meditation?.isActive == true) {
            meditation?.pauseForAlert()
            if (activeMeditationCue != null) {
                cancelMeditationCue()
                withTimeoutOrNull(3_000L) { while (_serviceState.value != TrekoState.IDLE) delay(100L) }
            }
        }
        // A camera alert held for a while says how old it is ("Hace 12 minutos: …").
        val insight = withHeldAgeNote(this, queued)
        cancelProactiveNotification(this)
        removePendingInsight(insight.id)
        AlertRecording.markDelivered(insight, spoken = true)

        if (fromUserTap && _isMuted.value) {
            TrekoPreferences(this).isMicMuted = false
            applyMuteState(muted = false)
        }

        val prefs = TrekoPreferences(this)
        syncPolicyPreferences(prefs)
        listeningTurnGeneration++
        biometricCheckJob?.cancel()
        complementaryBioJob?.cancel()
        isComplementaryBioCheckInFlight = false
        foreignVoiceResetJob?.cancel()
        ttsDrainingJob?.cancel()
        proactiveTtsFallbackJob?.cancel()
        isClosingTurn = false
        suppressIdleTts = false
        listeningOpenedByAutoFollowUp = false
        hasTurnEmotion = false
        activeVoiceProactiveInsight = insight
        activeVoiceProactiveFromUserTap = fromUserTap

        synchronized(opusSendOrderLock) {
            clearPendingOpusPackets()
            opusCodec?.stopPlayback()
            NativeAudioEngine.resetSoftwareAec()
        }

        _subtitleText.value = "💬 ${insight.title}…"
        addChatMessage(_assistantName.value, "💬 ${insight.title}: ${insight.summaryText}")
        transitionState(TrekoState.THINKING, TrekoEmotion.PIENSA)
        startThinkingTimeout()

        val client = wsClient
        if (client != null) {
            if (!client.isConnected) {
                _connectionStatus.value = WsConnectionStatus.CONNECTING
                client.connect()
                var waitedMs = 0L
                while (waitedMs < PROACTIVE_SESSION_WAIT_MS && (!client.isConnected || _activeSessionId.value.isNullOrBlank())) {
                    delay(100L)
                    waitedMs += 100L
                }
            }
            val trigger = "${_assistantName.value} lee tu aviso pendiente"
            if (client.sendProactiveTrigger(trigger)) {
                proactiveTtsFallbackJob = serviceScope.launch {
                    delay(8500L)
                    val pendingInsight = activeVoiceProactiveInsight
                    if (pendingInsight?.id == insight.id && _serviceState.value == TrekoState.THINKING) {
                        activeVoiceProactiveInsight = null
                        cancelThinkingWatchdog()
                        TrekoLogBuffer.w(TAG, "Xiaozhi proactive TTS timeout; falling back to local Android TTS.")
                        speakLocalProactiveFallback(insight, openMicAfter = fromUserTap && !_isMuted.value)
                    }
                }
                return
            }
        }

        // Fallback si el servidor Xiaozhi no está conectado en este instante
        activeVoiceProactiveInsight = null
        cancelThinkingWatchdog()
        speakLocalProactiveFallback(insight, openMicAfter = fromUserTap && !_isMuted.value)
    }

    /** What the meditation controller needs from the service. */
    private val meditationHost = object : MeditationController.Host {
        override fun haClient(): HomeAssistantClient? = currentWatchHaClient()
        override fun detectedSkills(): Set<String> = TrekoPreferences(this@TrekoAssistantService).detectedSkills
        override val radio: RadioBrowserClient get() = radioBrowserClient

        override val xiaozhiChannel = object : XiaozhiMeditationVoice.Channel {
            override fun isConnected(): Boolean =
                wsClient?.let { !meditationXiaozhiUnreachable || it.isConnected } ?: false
            override suspend fun deliverCue(text: String): SpeakResult = deliverMeditationCue(text)
            override fun cancelCue() = cancelMeditationCue()
        }

        override fun isAssistantIdle(): Boolean =
            _serviceState.value == TrekoState.IDLE && activeMeditationCue == null

        override suspend fun playOnPlayer(query: String, entityId: String): Boolean {
            val ha = currentWatchHaClient() ?: return false
            // No search fallback: it could find an unrelated pop or news station.
            val result = ha.playMusicOrRadio(query = query, targetDevice = entityId, searchFallback = false)
            // HomeAssistantClient answers in Spanish; these are its success sentences.
            return result.startsWith("Reproduciendo") || result.startsWith("Sintonizando")
        }
    }

    /** Waits for a Xiaozhi session (the server drops idle ones, the client reconnects). */
    private suspend fun ensureXiaozhiSession(client: XiaozhiWebSocketClient): Boolean {
        if (client.isConnected && !_activeSessionId.value.isNullOrBlank()) return true
        _connectionStatus.value = WsConnectionStatus.CONNECTING
        client.connect()
        var waitedMs = 0L
        while (waitedMs < MEDITATION_SESSION_WAIT_MS && (!client.isConnected || _activeSessionId.value.isNullOrBlank())) {
            delay(100L)
            waitedMs += 100L
        }
        return client.isConnected && !_activeSessionId.value.isNullOrBlank()
    }

    /**
     * Has Xiaozhi read one meditation cue word for word, through the proactive trigger (the
     * model calls read_pending_alert and gets the cue). SAID once the speech ended normally.
     * The mic is not opened afterwards: the TTS stop handler returns to IDLE for a cue.
     * Every wait is cancellable: the session cancels a cue on pause or stop, and a cue
     * cancelled before its trigger is never sent.
     */
    private suspend fun deliverMeditationCue(text: String): SpeakResult {
        val client = wsClient ?: return SpeakResult.FAILED
        if (!ensureXiaozhiSession(client)) {
            meditationXiaozhiUnreachable = true
            TrekoLogBuffer.w(TAG, "Xiaozhi not reachable for a meditation cue.")
            return SpeakResult.FAILED
        }
        meditationXiaozhiUnreachable = false
        val idle = withTimeoutOrNull(10_000L) {
            while (_serviceState.value != TrekoState.IDLE || isTrainingMode) delay(200L)
        }
        if (idle == null) return SpeakResult.FAILED
        currentCoroutineContext().ensureActive()

        listeningTurnGeneration++
        val req = MeditationCueRequest(text, listeningTurnGeneration)
        ttsDrainingJob?.cancel()
        proactiveTtsFallbackJob?.cancel()
        isClosingTurn = false
        suppressIdleTts = false
        listeningOpenedByAutoFollowUp = false
        hasTurnEmotion = false
        synchronized(opusSendOrderLock) {
            clearPendingOpusPackets()
            opusCodec?.stopPlayback()
            NativeAudioEngine.resetSoftwareAec()
        }
        activeMeditationCue = req
        try {
            transitionState(TrekoState.THINKING, TrekoEmotion.RELAX)
            startThinkingTimeout()
            if (!client.sendProactiveTrigger("${_assistantName.value} lee tu aviso pendiente")) {
                endMeditationCue(req, abort = false)
                return SpeakResult.FAILED
            }
            val ok = withTimeoutOrNull(MeditationTimelineBuilder.estimateSpeechMs(text) * 3 + 20_000L) {
                val started = withTimeoutOrNull(MEDITATION_CUE_START_TIMEOUT_MS) {
                    while (!req.started && !req.done.isCompleted) delay(100L)
                }
                if (started == null) false else req.done.await()
            } ?: false
            if (!ok && !req.cancelled) {
                TrekoLogBuffer.w(TAG, "Meditation cue not read by Xiaozhi (consumed=${req.consumed}, started=${req.started}).")
            }
            return when {
                ok -> SpeakResult.SAID
                req.cancelled -> SpeakResult.CANCELLED
                else -> SpeakResult.FAILED
            }
        } finally {
            // Also when the session cancelled it mid-way: stop the server's speech.
            if (activeMeditationCue === req) endMeditationCue(req, abort = true)
        }
    }

    /** Ends [req] if it is still the cue in flight; [abort] also stops the server's speech. */
    private fun endMeditationCue(req: MeditationCueRequest, abort: Boolean) {
        synchronized(meditationCueLock) {
            if (activeMeditationCue !== req) return
            activeMeditationCue = null
        }
        req.done.complete(false)
        if (abort) {
            wsClient?.abortSpeaking(reason = "meditation_cue")
            synchronized(opusSendOrderLock) { opusCodec?.stopPlayback() }
        }
        // Only the cue's own state goes back to IDLE: a turn the user started meanwhile is theirs.
        if (listeningTurnGeneration != req.turnGeneration) return
        cancelThinkingWatchdog()
        if (_serviceState.value == TrekoState.THINKING || _serviceState.value == TrekoState.SPEAKING) {
            transitionState(TrekoState.IDLE, TrekoEmotion.RELAX)
            _subtitleText.value = ""
        }
    }

    private fun cancelMeditationCue() {
        activeMeditationCue?.let {
            it.cancelled = true
            endMeditationCue(it, abort = true)
        }
    }

    private suspend fun ensureLocalTtsReady(): TextToSpeech? {
        val existing = localTts
        if (existing != null && isLocalTtsReady.get()) {
            return existing
        }
        return withTimeoutOrNull(3500L) {
            suspendCancellableCoroutine { cont ->
                var created: TextToSpeech? = null
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                handler.post {
                    try {
                        created = TextToSpeech(this@TrekoAssistantService) { status ->
                            val ready = (status == TextToSpeech.SUCCESS)
                            isLocalTtsReady.set(ready)
                            if (ready) {
                                created?.let { setLocalTtsLanguage(it, preferPhoneLanguage = false) }
                                created?.setAudioAttributes(
                                    AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                        .build()
                                )
                            }
                            if (cont.isActive) {
                                cont.resume(if (ready) created else null)
                            }
                        }
                        localTts = created
                    } catch (e: Exception) {
                        TrekoLogBuffer.w(TAG, "No se pudo inicializar TextToSpeech local: ${e.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }
    }

    /**
     * What `deliver_pending` hands the voice model: read the alert word for word (no greeting,
     * emoji or extra questions), plus the facts only for follow-up questions.
     */
    private fun readExactlyToolResult(insight: ProactiveInsight): String {
        val speech = SpokenText.stripEmoji(insight.spokenSpeechText.ifBlank { insight.summaryText })
        val read = getString(R.string.proactive_tool_read_exactly, speech)
        val facts = insight.spokenPrompt.trim()
        return if (facts.isEmpty()) read else read + "\n" + getString(R.string.proactive_tool_followup_facts, facts)
    }

    private suspend fun speakLocalProactiveFallback(insight: ProactiveInsight, openMicAfter: Boolean) {
        val textToSpeak = SpokenText.stripEmoji(insight.spokenSpeechText.ifBlank { insight.summaryText })
        val name = _assistantName.value
        _subtitleText.value = "$name: $textToSpeak"
        addChatMessage(name, textToSpeak)

        val tts = ensureLocalTtsReady()
        if (tts == null) {
            transitionState(
                TrekoState.IDLE,
                if (_isMuted.value) TrekoEmotion.SLEEPY else TrekoEmotion.FELIZ
            )
            if (openMicAfter && !_isMuted.value) {
                startActiveListening(prompt = getString(R.string.status_follow_up))
            }
            return
        }

        transitionState(TrekoState.SPEAKING, TrekoEmotion.FELIZ_ABIERTO)
        val utteranceId = "proactive_${proactiveUtteranceSeq.incrementAndGet()}"
        withTimeoutOrNull(28_000L) {
            suspendCancellableCoroutine { cont ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) {
                        if (id == utteranceId && cont.isActive) cont.resume(Unit)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {
                        if (id == utteranceId && cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(id: String?, errorCode: Int) {
                        if (id == utteranceId && cont.isActive) cont.resume(Unit)
                    }
                })
                // Briefings are written in Spanish; camera alerts come from localized resources
                // and a prompt in the phone's language, so they use the phone's voice.
                setLocalTtsLanguage(tts, preferPhoneLanguage = insight.category == ProactiveCategory.CAMERA_ANOMALY)
                val res = tts.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                if (res != TextToSpeech.SUCCESS && cont.isActive) {
                    cont.resume(Unit)
                }
            }
        }

        if (_serviceState.value == TrekoState.SPEAKING) {
            if (openMicAfter && !_isMuted.value && wsClient?.isConnected == true) {
                startActiveListening(prompt = getString(R.string.status_follow_up))
            } else {
                transitionState(
                    TrekoState.IDLE,
                    if (_isMuted.value) TrekoEmotion.SLEEPY else TrekoEmotion.RELAX
                )
            }
        }
    }

    private fun updateNotification(state: TrekoState) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: TrekoState): Notification {
        val prefsWakeWord = try {
            TrekoPreferences(this).customWakeWord
        } catch (_: Exception) {
            getString(R.string.wake_word)
        }
        val statusText = when {
            _isMuted.value -> getString(R.string.status_muted)
            state == TrekoState.IDLE -> getString(R.string.status_idle, prefsWakeWord)
            state == TrekoState.LISTENING -> getString(R.string.status_listening)
            state == TrekoState.THINKING -> getString(R.string.status_thinking)
            state == TrekoState.SPEAKING -> getString(R.string.status_speaking)
            else -> getString(R.string.status_error)
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val toggleIntent = Intent(this, TrekoAssistantService::class.java).apply {
            action = ACTION_TOGGLE_CONVERSATION
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            1,
            toggleIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val actionLabel = if (state == TrekoState.IDLE || state == TrekoState.ERROR) {
            getString(R.string.notification_action_talk)
        } else {
            getString(R.string.notification_action_stop)
        }

        val muteIntent = Intent(this, TrekoAssistantService::class.java).apply {
            action = ACTION_TOGGLE_MUTE
        }
        val mutePendingIntent = PendingIntent.getService(
            this,
            2,
            muteIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val muteLabel = if (_isMuted.value) {
            getString(R.string.notification_action_unmute)
        } else {
            getString(R.string.notification_action_mute)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.assistant_header_title, resolveAssistantName(this)))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_treko_notif)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_btn_speak_now, actionLabel, togglePendingIntent)
            .addAction(android.R.drawable.ic_lock_silent_mode, muteLabel, mutePendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        val watching = _activeWatchAreas.value
        if (watching.isNotEmpty()) {
            builder.setSubText(getString(R.string.notification_watching, watching.joinToString(", ")))
            val stopWatches = PendingIntent.getService(
                this,
                3,
                Intent(this, TrekoAssistantService::class.java).apply { action = ACTION_STOP_WATCHES },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.watch_stop_cd), stopWatches)
        }
        return builder.build()
    }
}
