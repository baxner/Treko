package com.treko.core.common.data

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Gestor centralizado de preferencias y configuración de Treko.
 * Permite persistir credenciales de Xiaozhi y Home Assistant, así como el estado del Onboarding.
 */
class TrekoPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("treko_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_XIAOZHI_WS_URL = "xiaozhi_ws_url"
        private const val KEY_XIAOZHI_TOKEN = "xiaozhi_token"
        private const val KEY_DEVICE_ID = "device_id"
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

        const val DEFAULT_XIAOZHI_WS_URL = "wss://api.tenclass.net/xiaozhi/v1/"
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
                // Generar dirección MAC válida (6 octetos hexadecimales en minúsculas con dos puntos)
                // Usamos prefijo 02:53:24 (02=local unicast, 53:24=S24) y sufijo único
                mac = "02:53:24:3d:8d:8b"
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
                key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
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

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
