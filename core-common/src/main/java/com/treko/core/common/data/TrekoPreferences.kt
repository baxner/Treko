package com.treko.core.common.data

import android.content.Context
import android.content.SharedPreferences
import com.treko.core.common.model.FullDuplexMode
import java.security.SecureRandom
import java.util.UUID

/**
 * Gestor centralizado de preferencias y configuración de Treko.
 * Permite persistir credenciales de Xiaozhi y Home Assistant, calibración de voz y estado del Onboarding.
 */
class TrekoPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("treko_settings", Context.MODE_PRIVATE)

    init {
        val schemaVersion = prefs.getInt(KEY_PREFS_SCHEMA_VERSION, 1)
        if (schemaVersion < CURRENT_PREFS_SCHEMA_VERSION) {
            prefs.edit()
                .putBoolean(KEY_VOICE_BARGE_IN_ENABLED, false)
                .putInt(KEY_PREFS_SCHEMA_VERSION, CURRENT_PREFS_SCHEMA_VERSION)
                .apply()
        }
    }

    companion object {
        private const val CURRENT_PREFS_SCHEMA_VERSION = 2
        private const val KEY_PREFS_SCHEMA_VERSION = "prefs_schema_version"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_XIAOZHI_WS_URL = "xiaozhi_ws_url"
        private const val KEY_XIAOZHI_TOKEN = "xiaozhi_token"
        private const val KEY_MAC_ADDRESS = "mac_address"
        private const val KEY_HMAC_KEY = "hmac_key"
        private const val KEY_XIAOZHI_LINKED = "xiaozhi_linked"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_HA_URL = "ha_url"
        private const val KEY_HA_TOKEN = "ha_token"
        private const val KEY_CUSTOM_WAKE_WORD = "custom_wake_word"
        private const val KEY_WAKE_WORD_BOOST = "wake_word_boost"
        private const val KEY_WAKE_WORD_SENSITIVITY = "wake_word_sensitivity"
        private const val KEY_ALWAYS_LISTEN_IN_BACKGROUND = "always_listen_in_background"

        private const val KEY_WAKE_WORD_CALIBRATED = "wake_word_calibrated"
        private const val KEY_CALIBRATED_NOISE_FLOOR_RMS = "calibrated_noise_floor_rms"
        private const val KEY_CALIBRATED_SPEECH_RMS = "calibrated_speech_rms"
        private const val KEY_CALIBRATED_SNR_DB = "calibrated_snr_db"
        private const val KEY_WAKE_WORD_VOICEPRINT_JSON = "wake_word_voiceprint_json"
        private const val KEY_WAKE_WORD_ACOUSTIC_TEMPLATES_JSON = "wake_word_acoustic_templates_json"
        private const val KEY_WAKE_WORD_DTW_THRESHOLD = "wake_word_dtw_threshold"
        private const val KEY_VAD_ADAPTIVE_ENABLED = "vad_adaptive_enabled"
        private const val KEY_SPEAKER_VERIFICATION_ENABLED = "speaker_verification_enabled"
        private const val KEY_AUDIO_ECHO_MODE_ENABLED = "audio_echo_mode_enabled"

        private const val KEY_MIC_MUTED = "mic_muted"
        private const val KEY_CINEMA_MODE_ENABLED = "cinema_mode_enabled"
        private const val KEY_AUTO_FOLLOW_UP_ENABLED = "auto_follow_up_enabled"
        private const val KEY_VOICE_BARGE_IN_ENABLED = "voice_barge_in_enabled"
        private const val KEY_NEAR_FIELD_GATE_ENABLED = "near_field_gate_enabled"
        private const val KEY_NEAR_FIELD_MIN_SNR_DB = "near_field_min_snr_db"
        private const val KEY_SPEECH_DENOISER_ENABLED = "speech_denoiser_enabled"
        private const val KEY_BIOMETRIC_SPEAKER_GATE_ENABLED = "biometric_speaker_gate_enabled"
        private const val KEY_BIOMETRIC_SPEAKER_THRESHOLD = "biometric_speaker_threshold"
        private const val KEY_BIOMETRIC_VOICEPRINT_JSON = "biometric_voiceprint_json"
        private const val KEY_DETECTED_SKILLS = "detected_ha_skills"
        private const val KEY_FULL_DUPLEX_MODE = "full_duplex_mode"
        private const val KEY_SOFTWARE_AEC3_ENABLED = "software_aec3_enabled"
        private const val KEY_COMPLEMENTARY_MIN_SPEECH_MS = "complementary_min_speech_ms"

        private const val KEY_PROACTIVE_ENABLED = "proactive_enabled"
        private const val KEY_PROACTIVE_SPEAK_OUT_LOUD = "proactive_speak_out_loud"
        private const val KEY_PROACTIVE_INTERVAL_MINUTES = "proactive_interval_minutes"
        private const val KEY_PROACTIVE_CAMERA_ANOMALY_ENABLED = "proactive_camera_anomaly_enabled"
        private const val KEY_CAMERA_SENTINEL_ENABLED = "camera_sentinel_enabled"
        private const val KEY_PROACTIVE_QUIET_HOURS_ENABLED = "proactive_quiet_hours_enabled"

        const val DEFAULT_XIAOZHI_WS_URL = "wss://api.tenclass.net/xiaozhi/v1/"
        const val DEFAULT_BIOMETRIC_THRESHOLD = 0.56f
        const val DEFAULT_COMPLEMENTARY_MIN_SPEECH_MS = 420L

        private val secureRandom = SecureRandom()

        fun resolveEffectiveAutoFollowUp(autoFollowUp: Boolean, cinemaMode: Boolean): Boolean =
            autoFollowUp && !cinemaMode

        fun resolveEffectiveVoiceBargeIn(voiceBargeIn: Boolean, cinemaMode: Boolean): Boolean =
            voiceBargeIn && !cinemaMode

        fun resolveEffectiveFullDuplexMode(mode: FullDuplexMode, cinemaMode: Boolean): FullDuplexMode =
            if (cinemaMode) FullDuplexMode.HALF_DUPLEX else mode

        fun resolveEffectiveNearFieldMinSnrDb(minSnrDb: Float, cinemaMode: Boolean): Float =
            (minSnrDb + if (cinemaMode) 2.5f else 0f).coerceIn(3.0f, 20.0f)

        /**
         * Generates a random locally-administered unicast MAC address (02:xx:xx:xx:xx:xx)
         * so each Treko installation has a unique Xiaozhi Device-Id.
         */
        /**
         * Before the sentinel had its own switch it ran when both the proactive check and the
         * camera scan were on: existing users keep exactly that until they change it.
         */
        fun initialCameraSentinelEnabled(proactiveEnabled: Boolean, cameraScanEnabled: Boolean): Boolean =
            proactiveEnabled && cameraScanEnabled

        fun generateRandomMacAddress(): String {
            val bytes = ByteArray(6)
            secureRandom.nextBytes(bytes)
            // Set locally-administered bit (bit 1) and clear multicast bit (bit 0)
            bytes[0] = ((bytes[0].toInt() and 0xFE) or 0x02).toByte()
            return bytes.joinToString(":") { "%02x".format(it) }
        }

        /**
         * Generates a random 256-bit (64 hex chars) HMAC key for Xiaozhi OTA v2 activation.
         */
        fun generateRandomHmacKey(): String {
            val bytes = ByteArray(32)
            secureRandom.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    var isOnboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, value).apply()

    var xiaozhiWsUrl: String
        get() = prefs.getString(KEY_XIAOZHI_WS_URL, DEFAULT_XIAOZHI_WS_URL) ?: DEFAULT_XIAOZHI_WS_URL
        set(value) = prefs.edit().putString(KEY_XIAOZHI_WS_URL, value.trim()).apply()

    var xiaozhiToken: String
        get() = prefs.getString(KEY_XIAOZHI_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_XIAOZHI_TOKEN, value.trim()).apply()

    var macAddress: String
        get() {
            var mac = prefs.getString(KEY_MAC_ADDRESS, null)
            if (mac.isNullOrBlank()) {
                mac = generateRandomMacAddress()
                prefs.edit().putString(KEY_MAC_ADDRESS, mac).apply()
            }
            return mac
        }
        set(value) = prefs.edit().putString(KEY_MAC_ADDRESS, value.trim()).apply()

    val deviceId: String
        get() = macAddress

    var hmacKey: String
        get() {
            var key = prefs.getString(KEY_HMAC_KEY, null)
            if (key.isNullOrBlank()) {
                key = generateRandomHmacKey()
                prefs.edit().putString(KEY_HMAC_KEY, key).apply()
            }
            return key
        }
        set(value) = prefs.edit().putString(KEY_HMAC_KEY, value).apply()

    var isXiaozhiLinked: Boolean
        get() = prefs.getBoolean(KEY_XIAOZHI_LINKED, false)
        set(value) = prefs.edit().putBoolean(KEY_XIAOZHI_LINKED, value).apply()

    val clientId: String
        get() {
            var cid = prefs.getString(KEY_CLIENT_ID, null)
            if (cid.isNullOrBlank()) {
                cid = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_CLIENT_ID, cid).apply()
            }
            return cid
        }

    var haUrl: String
        get() = prefs.getString(KEY_HA_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HA_URL, value.trim()).apply()

    var haToken: String
        get() = prefs.getString(KEY_HA_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HA_TOKEN, value.trim()).apply()

    var customWakeWord: String
        get() = prefs.getString(KEY_CUSTOM_WAKE_WORD, "Treko")?.takeIf { it.isNotBlank() } ?: "Treko"
        set(value) = prefs.edit().putString(KEY_CUSTOM_WAKE_WORD, value.trim().ifEmpty { "Treko" }).apply()

    var wakeWordBoost: Float
        get() = prefs.getFloat(KEY_WAKE_WORD_BOOST, 3.0f)
        set(value) = prefs.edit().putFloat(KEY_WAKE_WORD_BOOST, value.coerceIn(1.0f, 5.0f)).apply()

    var wakeWordSensitivity: Float
        get() = prefs.getFloat(KEY_WAKE_WORD_SENSITIVITY, 0.05f)
        set(value) = prefs.edit().putFloat(KEY_WAKE_WORD_SENSITIVITY, value.coerceIn(0.01f, 0.20f)).apply()

    var isAlwaysListeningInBackground: Boolean
        get() = prefs.getBoolean(KEY_ALWAYS_LISTEN_IN_BACKGROUND, true)
        set(value) = prefs.edit().putBoolean(KEY_ALWAYS_LISTEN_IN_BACKGROUND, value).apply()

    var isWakeWordCalibrated: Boolean
        get() = prefs.getBoolean(KEY_WAKE_WORD_CALIBRATED, false)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_WORD_CALIBRATED, value).apply()

    var calibratedNoiseFloorRms: Float
        get() = prefs.getFloat(KEY_CALIBRATED_NOISE_FLOOR_RMS, 0.005f)
        set(value) = prefs.edit().putFloat(KEY_CALIBRATED_NOISE_FLOOR_RMS, value.coerceIn(0.0005f, 0.25f)).apply()

    var calibratedSpeechRms: Float
        get() = prefs.getFloat(KEY_CALIBRATED_SPEECH_RMS, 0.05f)
        set(value) = prefs.edit().putFloat(KEY_CALIBRATED_SPEECH_RMS, value.coerceIn(0.002f, 1.0f)).apply()

    var calibratedSnrDb: Float
        get() = prefs.getFloat(KEY_CALIBRATED_SNR_DB, 0f)
        set(value) = prefs.edit().putFloat(KEY_CALIBRATED_SNR_DB, value.coerceIn(-10f, 60f)).apply()

    var wakeWordVoicePrintJson: String
        get() = prefs.getString(KEY_WAKE_WORD_VOICEPRINT_JSON, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WAKE_WORD_VOICEPRINT_JSON, value).apply()

    var wakeWordAcousticTemplatesJson: String
        get() = prefs.getString(KEY_WAKE_WORD_ACOUSTIC_TEMPLATES_JSON, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WAKE_WORD_ACOUSTIC_TEMPLATES_JSON, value).apply()

    var wakeWordDtwThreshold: Float
        get() = prefs.getFloat(KEY_WAKE_WORD_DTW_THRESHOLD, 0.078f)
        set(value) = prefs.edit().putFloat(KEY_WAKE_WORD_DTW_THRESHOLD, value.coerceIn(0.04f, 0.15f)).apply()

    var vadAdaptiveEnabled: Boolean
        get() = prefs.getBoolean(KEY_VAD_ADAPTIVE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_VAD_ADAPTIVE_ENABLED, value).apply()

    var speakerVerificationEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPEAKER_VERIFICATION_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SPEAKER_VERIFICATION_ENABLED, value).apply()

    var audioEchoModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_ECHO_MODE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO_ECHO_MODE_ENABLED, value).apply()

    var isMicMuted: Boolean
        get() = prefs.getBoolean(KEY_MIC_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_MIC_MUTED, value).apply()

    var cinemaModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_CINEMA_MODE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_CINEMA_MODE_ENABLED, value).apply()

    var autoFollowUpEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_FOLLOW_UP_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_FOLLOW_UP_ENABLED, value).apply()

    var voiceBargeInEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_BARGE_IN_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_BARGE_IN_ENABLED, value).apply()

    var nearFieldGateEnabled: Boolean
        get() = prefs.getBoolean(KEY_NEAR_FIELD_GATE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_NEAR_FIELD_GATE_ENABLED, value).apply()

    var nearFieldMinSnrDb: Float
        get() = prefs.getFloat(KEY_NEAR_FIELD_MIN_SNR_DB, 7.5f)
        set(value) = prefs.edit().putFloat(KEY_NEAR_FIELD_MIN_SNR_DB, value.coerceIn(3.0f, 18.0f)).apply()

    var speechDenoiserEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPEECH_DENOISER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SPEECH_DENOISER_ENABLED, value).apply()

    var biometricSpeakerGateEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_SPEAKER_GATE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC_SPEAKER_GATE_ENABLED, value).apply()

    var biometricSpeakerThreshold: Float
        get() = prefs.getFloat(KEY_BIOMETRIC_SPEAKER_THRESHOLD, DEFAULT_BIOMETRIC_THRESHOLD)
        set(value) = prefs.edit().putFloat(KEY_BIOMETRIC_SPEAKER_THRESHOLD, value.coerceIn(0.35f, 0.85f)).apply()

    var biometricVoicePrintJson: String
        get() = prefs.getString(KEY_BIOMETRIC_VOICEPRINT_JSON, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BIOMETRIC_VOICEPRINT_JSON, value).apply()

    var fullDuplexMode: FullDuplexMode
        get() = FullDuplexMode.fromStored(prefs.getString(KEY_FULL_DUPLEX_MODE, FullDuplexMode.COMPLEMENTARY.name))
        set(value) = prefs.edit().putString(KEY_FULL_DUPLEX_MODE, value.name).apply()

    var softwareAec3Enabled: Boolean
        get() = prefs.getBoolean(KEY_SOFTWARE_AEC3_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SOFTWARE_AEC3_ENABLED, value).apply()

    var complementaryMinSpeechMs: Long
        get() = prefs.getLong(KEY_COMPLEMENTARY_MIN_SPEECH_MS, DEFAULT_COMPLEMENTARY_MIN_SPEECH_MS)
            .coerceIn(240L, 1200L)
        set(value) = prefs.edit().putLong(KEY_COMPLEMENTARY_MIN_SPEECH_MS, value.coerceIn(240L, 1200L)).apply()

    var proactiveEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROACTIVE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_PROACTIVE_ENABLED, value).apply()

    var proactiveSpeakOutLoud: Boolean
        get() = prefs.getBoolean(KEY_PROACTIVE_SPEAK_OUT_LOUD, false)
        set(value) = prefs.edit().putBoolean(KEY_PROACTIVE_SPEAK_OUT_LOUD, value).apply()

    var proactiveIntervalMinutes: Int
        get() = prefs.getInt(KEY_PROACTIVE_INTERVAL_MINUTES, 30).coerceIn(5, 240)
        set(value) = prefs.edit().putInt(KEY_PROACTIVE_INTERVAL_MINUTES, value.coerceIn(5, 240)).apply()

    var proactiveCameraAnomalyEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROACTIVE_CAMERA_ANOMALY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_PROACTIVE_CAMERA_ANOMALY_ENABLED, value).apply()

    /**
     * Camera sentinel: looks with the vision model when Frigate detects someone. Independent of
     * the periodic proactive check, so turning briefings off keeps camera security on. The first
     * read stores the value inherited from the old settings (see [initialCameraSentinelEnabled]).
     */
    var cameraSentinelEnabled: Boolean
        get() {
            if (prefs.contains(KEY_CAMERA_SENTINEL_ENABLED)) return prefs.getBoolean(KEY_CAMERA_SENTINEL_ENABLED, true)
            val inherited = initialCameraSentinelEnabled(proactiveEnabled, proactiveCameraAnomalyEnabled)
            prefs.edit().putBoolean(KEY_CAMERA_SENTINEL_ENABLED, inherited).apply()
            return inherited
        }
        set(value) = prefs.edit().putBoolean(KEY_CAMERA_SENTINEL_ENABLED, value).apply()

    var proactiveQuietHoursEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROACTIVE_QUIET_HOURS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_PROACTIVE_QUIET_HOURS_ENABLED, value).apply()

    /** Home Assistant integrations found last time (see TrekoSkills); empty until detected. */
    var detectedSkills: Set<String>
        get() = prefs.getStringSet(KEY_DETECTED_SKILLS, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_DETECTED_SKILLS, value.toSet()).apply()

    val isBiometricVoiceEnrolled: Boolean
        get() = biometricVoicePrintJson.isNotBlank()

    /** Saves a freshly recorded voice print and turns voice verification on. */
    fun applyBiometricVoicePrint(profileJson: String, recommendedThreshold: Float) {
        biometricVoicePrintJson = profileJson
        biometricSpeakerThreshold = recommendedThreshold
        biometricSpeakerGateEnabled = true
    }

    fun effectiveAutoFollowUp(): Boolean = resolveEffectiveAutoFollowUp(autoFollowUpEnabled, cinemaModeEnabled)

    fun effectiveVoiceBargeIn(): Boolean = resolveEffectiveVoiceBargeIn(voiceBargeInEnabled, cinemaModeEnabled)

    fun effectiveFullDuplexMode(): FullDuplexMode = resolveEffectiveFullDuplexMode(fullDuplexMode, cinemaModeEnabled)

    fun effectiveNearFieldMinSnrDb(): Float = resolveEffectiveNearFieldMinSnrDb(nearFieldMinSnrDb, cinemaModeEnabled)

    fun resetBiometricVoicePrint() {
        prefs.edit()
            .remove(KEY_BIOMETRIC_VOICEPRINT_JSON)
            .remove(KEY_BIOMETRIC_SPEAKER_THRESHOLD)
            .putBoolean(KEY_BIOMETRIC_SPEAKER_GATE_ENABLED, false)
            .apply()
    }

    fun resetWakeWordCalibration() {
        prefs.edit()
            .remove(KEY_WAKE_WORD_CALIBRATED)
            .remove(KEY_CALIBRATED_NOISE_FLOOR_RMS)
            .remove(KEY_CALIBRATED_SPEECH_RMS)
            .remove(KEY_CALIBRATED_SNR_DB)
            .remove(KEY_WAKE_WORD_VOICEPRINT_JSON)
            .remove(KEY_WAKE_WORD_ACOUSTIC_TEMPLATES_JSON)
            .remove(KEY_WAKE_WORD_DTW_THRESHOLD)
            .putBoolean(KEY_SPEAKER_VERIFICATION_ENABLED, false)
            .apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
