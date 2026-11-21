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
import com.treko.assistant.MainActivity
import com.treko.assistant.R
import com.treko.assistant.data.CameraAnomalyCheckResult
import com.treko.assistant.data.ProactiveCameraCandidate
import com.treko.assistant.data.ProactiveDataSources
import com.treko.assistant.data.ProactiveInsight
import com.treko.assistant.data.SentinelRunTrace
import com.treko.assistant.data.TrekoProactiveManager
import com.treko.assistant.data.TrekoWorkflowManager
import com.treko.assistant.data.WorkflowActions
import com.treko.core.audio.FullDuplexComplementaryQueue
import com.treko.core.audio.NativeAudioEngine
import com.treko.core.audio.NearFieldListeningGate
import com.treko.core.audio.OpusAudioCodec
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
import com.treko.core.mcp.HomeAssistantClient
import com.treko.core.mcp.watch.WatchSensor
import com.treko.core.common.model.TrekoSkills
import com.treko.core.mcp.RadioBrowserClient
import com.treko.core.network.XiaozhiWebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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

        fun stopAllWatches() {
            instance?.cameraWatch?.stopAll()
        }

        private val _conversationHistory = MutableStateFlow<List<ChatMessage>>(emptyList())
        val conversationHistory: StateFlow<List<ChatMessage>> = _conversationHistory.asStateFlow()

        private val _pendingProactiveInsight = MutableStateFlow<ProactiveInsight?>(null)
        val pendingProactiveInsight: StateFlow<ProactiveInsight?> = _pendingProactiveInsight.asStateFlow()

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

        private fun getOrCreateProactiveManager(context: Context): TrekoProactiveManager {
            val appCtx = context.applicationContext
            return synchronized(sharedProactiveLock) {
                sharedProactiveManager ?: TrekoProactiveManager(
                    sources = object : ProactiveDataSources {
                        override suspend fun getHomeStatusSummary(): String {
                            val p = TrekoPreferences(appCtx)
                            val haUrl = p.haUrl.trim()
                            val haToken = p.haToken.trim()
                            if (haUrl.isBlank() || haToken.isBlank()) return ""
                            return HomeAssistantClient(haUrl, haToken).getHomeStatusSummary()
                        }

                        override suspend fun scanPriorityCamerasForAnomalies(
                            homeStatusSummary: String
                        ): List<CameraAnomalyCheckResult> {
                            // Callers decide whether cameras are scanned (includeCameraScan): the
                            // periodic check follows the camera-scan setting, the sentinel its own.
                            val p = TrekoPreferences(appCtx)
                            val trace = coroutineContext[SentinelRunTrace]
                            val haUrl = p.haUrl.trim()
                            val haToken = p.haToken.trim()
                            if (haUrl.isBlank() || haToken.isBlank()) {
                                trace?.decide("skipped", "Home Assistant not configured")
                                return emptyList()
                            }
                            val client = HomeAssistantClient(haUrl, haToken)
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
                                val sensors = client.getWatchCatalog().orEmpty()
                                    .filter { it.source == WatchSensor.Source.FRIGATE_CAMERA && it.cameraEntityId != null }
                                val states = client.getStates(sensors.map { it.entityId }.toSet())
                                if (sensors.isNotEmpty() && states.isNotEmpty()) {
                                    frigateCameras = sensors.mapNotNull { it.cameraEntityId }.toSet()
                                    frigateBusy = sensors.filter { states[it.entityId] == "on" }.mapNotNull { it.cameraEntityId }.toSet()
                                }
                                trace?.stage("catalog", System.currentTimeMillis() - catalogStartMs)
                                trace?.note("frigate_busy=${frigateBusy.sorted().ifEmpty { listOf("none") }.joinToString(",")}")
                            }
                            fun idsOf(g: HomeAssistantClient.HaCameraGroup) = setOf(g.primaryEntityId, g.snapshotEntityId)
                            fun candidateOf(g: HomeAssistantClient.HaCameraGroup) = ProactiveCameraCandidate(
                                friendlyName = g.friendlyName,
                                entityId = g.snapshotEntityId.ifBlank { g.primaryEntityId },
                                state = g.state
                            )
                            // Cameras where Frigate sees something now go first, so a detection
                            // is always looked at; the remaining slots rotate as before.
                            val busy = groups.filter { g -> idsOf(g).any { it in frigateBusy } }.map(::candidateOf)
                            val others = groups.filter { g -> idsOf(g).none { it in frigateCameras } }.map(::candidateOf)
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
                                        val visionStartMs = System.currentTimeMillis()
                                        val report = client.analyzeCameraWithLlmVision(
                                            cam.entityId,
                                            TrekoProactiveManager.SENTINEL_VISION_PROMPT
                                        )?.trim().orEmpty()
                                        val result = if (report.isBlank()) {
                                            null
                                        } else {
                                            CameraAnomalyCheckResult(
                                                cameraName = cam.friendlyName,
                                                entityId = cam.entityId,
                                                visionReport = report,
                                                hasAnomaly = TrekoProactiveManager.detectCameraAnomaly(report)
                                            )
                                        }
                                        trace?.look(
                                            cameraName = cam.friendlyName,
                                            imageEntityId = cam.entityId,
                                            durationMs = System.currentTimeMillis() - visionStartMs,
                                            verdict = report,
                                            anomaly = result?.hasAnomaly
                                        )
                                        result
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
                    }
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

            val openIntent = Intent(appCtx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val contentPendingIntent = PendingIntent.getActivity(
                appCtx,
                PROACTIVE_NOTIFICATION_ID,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val speakIntent = Intent(appCtx, TrekoAssistantService::class.java).apply {
                action = ACTION_CONSUME_PROACTIVE_INSIGHT
            }
            val speakPendingIntent = PendingIntent.getService(
                appCtx,
                PROACTIVE_NOTIFICATION_ID + 1,
                speakIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val dismissIntent = Intent(appCtx, TrekoAssistantService::class.java).apply {
                action = ACTION_DISMISS_PROACTIVE_INSIGHT
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
            } catch (e: SecurityException) {
                TrekoLogBuffer.w("TrekoService", "Notification permission missing for proactive alert: ${e.message}")
            }
        }

        private fun cancelProactiveNotification(context: Context) {
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
                    val insight = manager.evaluateNow(
                        includeCameraScan = prefs.proactiveCameraAnomalyEnabled,
                        force = true,
                        assistantName = resolveAssistantName(appCtx)
                    ) ?: return@launch
                    val shouldSpeak = (forceSpeak ?: prefs.proactiveSpeakOutLoud) && hasMicPermission(appCtx)
                    if (shouldSpeak) {
                        pendingProactiveDelivery = insight
                        startService(appCtx)
                    } else {
                        _pendingProactiveInsight.value = insight
                        postProactiveNotification(appCtx, insight)
                    }
                } finally {
                    _isProactiveCheckRunning.value = false
                }
            }
        }

        fun consumePendingProactiveInsight(context: Context) {
            val appCtx = context.applicationContext
            cancelProactiveNotification(appCtx)
            val insight = _pendingProactiveInsight.value ?: return
            _pendingProactiveInsight.value = null
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

        fun dismissPendingProactiveInsight(context: Context) {
            _pendingProactiveInsight.value = null
            cancelProactiveNotification(context.applicationContext)
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

        val prefs = TrekoPreferences(this)
        _isMuted.value = prefs.isMicMuted

        TimerAlarms.install(this)
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
                            if (_serviceState.value == TrekoState.SPEAKING) {
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
                                    !_isMuted.value && effectiveAutoFollowUpEnabled && client?.isConnected == true -> {
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
                "MCP tool '$toolName' finished in ${durationMs}ms: ${LogText.preview(result, TOOL_RESULT_LOG_CHARS).ifEmpty { "<no result>" }}"
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
                p.cameraSentinelEnabled && TrekoSkills.FRIGATE in p.detectedSkills
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

    /** Short spoken alert with the phone's own voice, only when Treko is not mid-conversation. */
    private suspend fun speakWatchAlert(text: String) {
        if (_serviceState.value != TrekoState.IDLE || isTrainingMode) return
        val tts = ensureLocalTtsReady() ?: return
        // Watch alerts come from localized resources: speak them in the phone's language.
        setLocalTtsLanguage(tts, preferPhoneLanguage = true)
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "watch_${System.currentTimeMillis()}")
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
                    "Treko Device: Battery $batteryLevel% (Charging: $isCharging). Current date/time: $currentTime."
                }
                "manage_vault_memory" -> {
                    val action = args.optString("action", "list").lowercase(Locale.ROOT)
                    val key = args.optString("key", "").trim()
                    val value = args.optString("value", "").trim()
                    val vaultPrefs = getSharedPreferences("treko_vault_memory", Context.MODE_PRIVATE)

                    when (action) {
                        "set" -> {
                            if (key.isBlank()) return@withContext "Error: 'key' is required to store in Vault."
                            vaultPrefs.edit().putString(key, value).apply()
                            "Saved to Vault: '$key' = '$value'."
                        }
                        "get" -> {
                            if (key.isBlank()) return@withContext "Error: 'key' is required to read from Vault."
                            val stored = vaultPrefs.getString(key, null)
                            if (stored != null) "Vault[$key]: $stored" else "No entry found for '$key' in Vault."
                        }
                        "list" -> {
                            val all = vaultPrefs.all
                            if (all.isEmpty()) {
                                "Vault memory is currently empty."
                            } else {
                                val items = all.entries.joinToString("; ") { "${it.key}: ${it.value}" }
                                "Stored Vault memories: $items"
                            }
                        }
                        "delete" -> {
                            vaultPrefs.edit().remove(key).apply()
                            "Deleted '$key' from Vault memory."
                        }
                        else -> "Unknown manage_vault_memory action '$action'."
                    }
                }
                "home_assistant_call_service" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext "Home Assistant is not configured in Treko Settings."
                    }
                    val domain = args.optString("domain", "").trim()
                    val service = args.optString("service", "").trim()

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
                                if (key != "domain" && key != "service") {
                                    payload.put(key, args.get(key))
                                }
                            }
                            payload
                        }
                    }

                    HomeAssistantClient(haUrl, haToken).callService(domain, service, serviceData)
                }
                "home_assistant_broadcast" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext "Home Assistant is not configured in Treko Settings."
                    }
                    val message = args.optString("message", "").trim()
                    val target = args.optString("target", "").trim()
                    if (message.isBlank()) {
                        return@withContext "Broadcast message cannot be empty."
                    }
                    HomeAssistantClient(haUrl, haToken).broadcastMessage(message, target)
                }
                "home_assistant_list_entities" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext "Home Assistant is not configured in Treko Settings."
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
                        return@withContext "Home Assistant is not configured in Treko Settings."
                    }
                    val entityId = args.optString("entity_id", "").trim()
                    if (entityId.isBlank()) {
                        return@withContext "'entity_id' is required."
                    }
                    HomeAssistantClient(haUrl, haToken).getEntityState(entityId)
                }
                "home_assistant_list_services" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext "Home Assistant is not configured in Treko Settings."
                    }
                    HomeAssistantClient(haUrl, haToken).listServices(args.optString("domain", ""))
                }
                "home_assistant_control" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext "Home Assistant is not configured in Treko Settings."
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
                        source = source
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
                            if (title.isBlank()) return@withContext "Error: habit title is required."
                            val item = habitManager.addHabit(title)
                            "Habit added: '${item.title}' ${item.icon} (ID: ${item.id})."
                        }
                        "toggle", "check", "complete" -> {
                            val lookup = TrekoHabitManager.findTarget(id.takeIf { it != -1 }, title)
                            lookup.failureMessage("habit", TrekoHabitManager.habitsFlow.value) { "#${it.id} ${it.title}" }
                                ?.let { return@withContext it }
                            val targetId = (lookup as TargetLookup.Found).item.id
                            habitManager.toggleHabit(targetId)
                            val item = TrekoHabitManager.habitsFlow.value.find { it.id == targetId }
                            val state = if (item?.isDone == true) "completed" else "pending"
                            "Habit '${item?.title}' marked as $state."
                        }
                        "delete", "remove" -> {
                            val lookup = TrekoHabitManager.findTarget(id.takeIf { it != -1 }, title)
                            lookup.failureMessage("habit", TrekoHabitManager.habitsFlow.value) { "#${it.id} ${it.title}" }
                                ?.let { return@withContext it }
                            val targetId = (lookup as TargetLookup.Found).item.id
                            val deletedTitle = (lookup as TargetLookup.Found).item.title
                            habitManager.deleteHabit(targetId)
                            "Habit '$deletedTitle' deleted."
                        }
                        "add_water", "drink_water", "tomar_agua", "increment_water" -> {
                            val nextCount = TrekoHabitManager.waterGlassesFlow.value + 1
                            habitManager.setWaterGlasses(nextCount)
                            "Registrado 1 vaso de agua. Ahora llevas $nextCount de 8 vasos hoy."
                        }
                        "set_water" -> {
                            if (waterCount < 0) return@withContext "water_count is required."
                            habitManager.setWaterGlasses(waterCount)
                            "Water counter updated to $waterCount glasses."
                        }
                        "list", "get" -> habitManager.listHabitsSummary()
                        else -> "Unknown manage_habits action '$action'."
                    }
                }
                "manage_tasks" -> {
                    val taskManager = TrekoTaskManager(this@TrekoAssistantService)
                    val action = args.optString("action", "list").lowercase(Locale.ROOT)
                    val title = args.optString("title", "").trim()
                    val id = args.optInt("id", -1)

                    when (action) {
                        "add", "create" -> {
                            if (title.isBlank()) return@withContext "Error: task title is required."
                            val item = taskManager.addTask(title)
                            "Task added: '${item.title}' (ID: ${item.id})."
                        }
                        "toggle", "check", "complete" -> {
                            val lookup = TrekoTaskManager.findTarget(id.takeIf { it != -1 }, title)
                            lookup.failureMessage("task", TrekoTaskManager.tasksFlow.value) { "#${it.id} ${it.title}" }
                                ?.let { return@withContext it }
                            val targetId = (lookup as TargetLookup.Found).item.id
                            taskManager.toggleTask(targetId)
                            val item = TrekoTaskManager.tasksFlow.value.find { it.id == targetId }
                            val state = if (item?.isDone == true) "completed" else "pending"
                            "Task '${item?.title}' marked as $state."
                        }
                        "delete", "remove" -> {
                            val lookup = TrekoTaskManager.findTarget(id.takeIf { it != -1 }, title)
                            lookup.failureMessage("task", TrekoTaskManager.tasksFlow.value) { "#${it.id} ${it.title}" }
                                ?.let { return@withContext it }
                            val targetId = (lookup as TargetLookup.Found).item.id
                            val deletedTitle = (lookup as TargetLookup.Found).item.title
                            taskManager.deleteTask(targetId)
                            "Task '$deletedTitle' deleted."
                        }
                        "list", "get" -> taskManager.listTasksSummary()
                        else -> "Unknown manage_tasks action '$action'."
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
                                    val totalDesc = if (created.totalSeconds >= 60 && created.totalSeconds % 60 == 0) {
                                        "${created.totalSeconds / 60} min"
                                    } else {
                                        "${created.totalSeconds}s"
                                    }
                                    "Started timer #${created.id} '${created.label}' for $totalDesc."
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
                                action in listOf("set", "fijar", "poner") -> {
                                    val created = TrekoTimerManager.addNamedTimer(minutes = 10, label = label)
                                    "Started timer #${created.id} '${created.label}' for 10 min."
                                }
                                else -> {
                                    TrekoTimerManager.start()
                                    TrekoTimerManager.getStatusDescription()
                                }
                            }
                        }
                        "cancel", "cancelar", "delete", "eliminar", "remove", "borrar" -> {
                            when {
                                label.equals("all", ignoreCase = true) || label.equals("todos", ignoreCase = true) -> {
                                    val n = TrekoTimerManager.cancelAllNamedTimers()
                                    "Cancelled $n active timer(s)."
                                }
                                timerId > 0 -> {
                                    if (TrekoTimerManager.cancelNamedTimer(timerId)) {
                                        "Cancelled timer #$timerId."
                                    } else {
                                        "Timer #$timerId not found."
                                    }
                                }
                                label.isNotBlank() -> {
                                    val removed = TrekoTimerManager.cancelNamedTimerByLabel(label)
                                    if (removed != null) {
                                        "Cancelled timer '${removed.label}'."
                                    } else {
                                        "No active timer matching '$label'."
                                    }
                                }
                                else -> {
                                    val active = TrekoTimerManager.namedTimers.value
                                    if (active.size == 1) {
                                        TrekoTimerManager.cancelNamedTimer(active.first().id)
                                        "Cancelled timer '${active.first().label}'."
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
                        "Camera service unavailable on this device."
                    } else {
                        val cameraId = cm.cameraIdList.firstOrNull() ?: return@withContext "No flash found on device."
                        val action = args.optString("action", "toggle").lowercase(Locale.ROOT)
                        val newState = when (action) {
                            "turn_on", "on", "encender", "prender" -> true
                            "turn_off", "off", "apagar" -> false
                            else -> !isTorchOn
                        }
                        try {
                            cm.setTorchMode(cameraId, newState)
                            isTorchOn = newState
                            if (newState) "Flashlight turned on." else "Flashlight turned off."
                        } catch (e: Exception) {
                            "Failed to toggle flashlight: ${e.message}"
                        }
                    }
                }
                "open_app" -> {
                    val appName = args.optString("app_name", "").trim().lowercase(Locale.ROOT)
                    if (appName.isBlank()) return@withContext "Error: app_name is required."
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
                        return@withContext "No installed application found matching '$appName'."
                    }

                    val launchIntent = pm.getLaunchIntentForPackage(targetPkg)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                        "Opening $appName..."
                    } else {
                        "Could not launch '$appName'."
                    }
                }
                "set_device_volume" -> {
                    val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    if (am == null) {
                        "Audio volume control unavailable."
                    } else {
                        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val action = args.optString("action", "set").lowercase(Locale.ROOT)
                        val level = args.optInt("level", -1)

                        when (action) {
                            "up", "subir" -> {
                                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                                val newVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                                "Volume raised to ${(newVol * 100) / maxVol}%."
                            }
                            "down", "bajar" -> {
                                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                                val newVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                                "Volume lowered to ${(newVol * 100) / maxVol}%."
                            }
                            "mute", "silenciar" -> {
                                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                                "Volume muted."
                            }
                            "set", "fijar" -> {
                                if (level in 0..100) {
                                    val target = ((level / 100.0) * maxVol).toInt()
                                    am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
                                    "Volume set to $level%."
                                } else {
                                    "Please provide a valid volume percentage between 0 and 100."
                                }
                            }
                            else -> "Current volume is ${(currentVol * 100) / maxVol}%."
                        }
                    }
                }
                "set_alarm" -> {
                    val hour = args.optInt("hour", -1)
                    val minute = args.optInt("minute", 0)
                    val message = args.optString("message", "Treko Alarm").trim()
                    if (hour !in 0..23 || minute !in 0..59) {
                        return@withContext "Specify valid hour (0-23) and minute (0-59)."
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
                        "Alarm set for $timeStr ($message)."
                    } catch (e: Exception) {
                        "Failed to set alarm: ${e.message}"
                    }
                }
                // get_weather se renombró a get_home_weather: chocaba con la herramienta
                // del servidor Xiaozhi ("Duplicate tool names").
                "get_home_weather", "get_weather" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext "Home Assistant is not configured in Treko Settings."
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
                        return@withContext "Home Assistant is not configured in Treko Settings."
                    }
                    val volume = if (args.has("volume_level")) args.optDouble("volume_level", 0.5).toFloat() else null
                    HomeAssistantClient(haUrl, haToken).mediaPlayerPlayback(device, action, volume)
                }
                "trigger_scene" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext "Home Assistant is not configured in Treko Settings."
                    }
                    val scene = args.optString("scene", "").trim()
                    if (scene.isBlank()) return@withContext "Scene name is required."
                    HomeAssistantClient(haUrl, haToken).triggerScene(scene)
                }
                "view_camera" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext "Home Assistant is not configured in Treko Settings."
                    }
                    val cameraQuery = args.optString("camera", "").trim()
                    val action = args.optString("action", "view").trim().lowercase(Locale.ROOT)
                    val normQuery = normalizeAsciiLower(cameraQuery)
                    val haClient = HomeAssistantClient(haUrl, haToken)

                    if (action == "dismiss" || action == "close" || action == "cerrar") {
                        dismissCameraFeed(closeGrid = true)
                        return@withContext "Camera viewer dismissed."
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
                    "Showing camera '$resolvedName' on screen."
                }
                "analyze_camera" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext "Home Assistant is not configured in Treko Settings."
                    }
                    val cameraQuery = args.optString("camera", "").trim()
                    val question = args.optString("question", "Describe in detail what is visible in the camera.")
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

                    val visionAnalysis = haClient.analyzeCameraWithLlmVision(camInfo.entityId, question)
                    if (!visionAnalysis.isNullOrBlank()) {
                        "AI Vision analysis of camera '${camInfo.friendlyName}': $visionAnalysis. (Snapshot displayed on screen)."
                    } else {
                        val sizeKb = bytes.size / 1024
                        "Snapshot from '${camInfo.friendlyName}' retrieved (${sizeKb} KB, state: ${camInfo.state}) and displayed on screen."
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
                        return@withContext "Home Assistant is not configured in Treko Settings."
                    }
                    HomeAssistantClient(haUrl, haToken).get3dPrinterStatus()
                }
                "home_status_summary" -> {
                    val haUrl = prefs.haUrl.trim()
                    val haToken = prefs.haToken.trim()
                    if (haUrl.isBlank() || haToken.isBlank()) {
                        return@withContext "Home Assistant is not configured in Treko Settings."
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
                "manage_proactive_assistant" -> {
                    val action = normalizeAsciiLower(args.optString("action", "status"))
                    val proactiveManager = getOrCreateProactiveManager(this@TrekoAssistantService)
                    when (action) {
                        "deliver_pending", "read_pending_alert", "get_pending", "pending", "leer", "read", "briefing" -> {
                            val activeVoice = activeVoiceProactiveInsight
                            val pending = _pendingProactiveInsight.value
                            val recent = proactiveManager.lastDeliveredInsight?.takeIf {
                                (System.currentTimeMillis() - it.createdAtMs) < 180_000L
                            }
                            val chosen = activeVoice ?: pending ?: recent
                            if (chosen != null) {
                                activeVoiceProactiveInsight = null
                                _pendingProactiveInsight.value = null
                                cancelProactiveNotification(this@TrekoAssistantService)
                                "Di esto al usuario en voz alta ahora mismo con naturalidad: \"${chosen.spokenSpeechText}\"\nContexto detallado para preguntas de seguimiento: ${chosen.spokenPrompt}"
                            } else {
                                // "What were you going to tell me?": a quick look at the other
                                // topics, never a slow camera scan.
                                val fresh = proactiveManager.evaluateNow(
                                    includeCameraScan = false,
                                    force = true,
                                    assistantName = resolveAssistantName(this@TrekoAssistantService)
                                )
                                if (fresh != null) {
                                    "Di esto al usuario en voz alta ahora mismo: \"${fresh.spokenSpeechText}\"\nContexto adicional: ${fresh.spokenPrompt}"
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
                            )
                            if (fresh != null) {
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
                else -> "Tool '$name' is not implemented in Treko."
            }
        } catch (e: Exception) {
            "Error executing $name: ${e.message}"
        }
    }

    private suspend fun openMultiCameraPanelInternal(
        prefs: TrekoPreferences,
        liveAll: Boolean = false
    ): String {
        val haUrl = prefs.haUrl.trim()
        val haToken = prefs.haToken.trim()
        if (haUrl.isBlank() || haToken.isBlank()) {
            return "Home Assistant is not configured in Treko Settings."
        }
        val groups = HomeAssistantClient(haUrl, haToken).getDeduplicatedCameras()
        if (groups.isEmpty()) {
            return "No cameras found in Home Assistant."
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
        val modeLabel = if (liveAll) "vivo" else "vista previa"
        val sb = StringBuilder("Panel multi-cámara abierto en pantalla ($modeLabel, ${feeds.size} cámaras):\n")
        groups.forEach { group ->
            val liveTag = if (!group.liveEntityId.isNullOrBlank()) " +RTSP" else ""
            sb.append("• ${group.friendlyName} (${group.snapshotEntityId}$liveTag) [${group.state}]\n")
        }
        return sb.toString().trim()
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
            return "Home Assistant is not configured in Treko Settings."
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
                val insight = _pendingProactiveInsight.value
                _pendingProactiveInsight.value = null
                if (insight != null) {
                    serviceScope.launch {
                        deliverProactiveInsightByVoice(insight, fromUserTap = true)
                    }
                } else {
                    beginConversation(TrekoPreferences(this).customWakeWord)
                }
            }
            ACTION_DISMISS_PROACTIVE_INSIGHT -> {
                dismissPendingProactiveInsight(this)
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
                return true
            }

            val speakOutLoud = overrideSpeak ?: prefs.proactiveSpeakOutLoud
            val canSpeakAutomatically = speakOutLoud &&
                _serviceState.value == TrekoState.IDLE &&
                !_isMuted.value &&
                !_isCinemaMode.value &&
                (!inQuietHours || force)

            TrekoLogBuffer.i(
                TAG,
                "Proactive insight [${insight.category}] speakOutLoud=$speakOutLoud canSpeakNow=$canSpeakAutomatically: ${insight.summaryText}"
            )

            if (canSpeakAutomatically) {
                trace?.decide("alert_spoken", override = true)
                deliverProactiveInsightByVoice(insight, fromUserTap = false)
            } else {
                trace?.decide(
                    "alert_notified",
                    reason = when {
                        !speakOutLoud -> "speaking proactively is off"
                        inQuietHours && !force -> "quiet hours"
                        _isMuted.value -> "microphone muted"
                        _isCinemaMode.value -> "cinema mode"
                        else -> "assistant busy (${_serviceState.value})"
                    },
                    override = true
                )
                _pendingProactiveInsight.value = insight
                postProactiveNotification(this, insight)
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

    private suspend fun deliverProactiveInsightByVoice(insight: ProactiveInsight, fromUserTap: Boolean) {
        cancelProactiveNotification(this)
        _pendingProactiveInsight.value = null

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

    private suspend fun speakLocalProactiveFallback(insight: ProactiveInsight, openMicAfter: Boolean) {
        val textToSpeak = insight.spokenSpeechText.ifBlank { insight.summaryText }
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
                // Proactive texts are written in Spanish.
                setLocalTtsLanguage(tts, preferPhoneLanguage = false)
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
