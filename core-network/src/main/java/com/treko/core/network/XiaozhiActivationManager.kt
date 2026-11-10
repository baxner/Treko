package com.treko.core.network

import android.util.Log
import com.treko.core.common.data.TrekoPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

sealed class ActivationState {
    object Idle : ActivationState()
    object FetchingCode : ActivationState()
    data class CodeReady(val code: String, val challenge: String) : ActivationState()
    data class PollingApproval(val code: String) : ActivationState()
    object Success : ActivationState()
    data class Error(val message: String) : ActivationState()
}

/**
 * Gestor oficial de activación y vinculación de dispositivos con Xiaozhi.
 * Implementa el protocolo v2 OTA (HMAC-SHA256) de Xiaozhi.
 */
class XiaozhiActivationManager(
    private val prefs: TrekoPreferences,
    private val otaUrl: String = "https://api.tenclass.net/xiaozhi/ota/"
) {
    private val TAG = "XiaozhiActivation"
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Solicita al servidor OTA de Xiaozhi el código de verificación de 6 dígitos.
     */
    suspend fun fetchActivationCode(): ActivationState = withContext(Dispatchers.IO) {
        try {
            val mac = prefs.macAddress
            val clientId = prefs.clientId
            val hmacKey = prefs.hmacKey

            val payload = JSONObject().apply {
                put("application", JSONObject().apply {
                    put("version", "1.0.0")
                    put("elf_sha256", hmacKey)
                })
                put("board", JSONObject().apply {
                    put("type", "android")
                    put("name", "Treko")
                    put("ip", "127.0.0.1")
                    put("mac", mac)
                })
            }

            val request = Request.Builder()
                .url(otaUrl)
                .addHeader("Device-Id", mac)
                .addHeader("Client-Id", clientId)
                .addHeader("Activation-Version", "2")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return@withContext ActivationState.Error("Error HTTP ${response.code}: $body")
            }

            val json = JSONObject(body)

            // Guardar configuración websocket si el servidor ya la envía
            if (json.has("websocket")) {
                val ws = json.getJSONObject("websocket")
                if (ws.has("url")) prefs.xiaozhiWsUrl = ws.getString("url")
                if (ws.has("token")) prefs.xiaozhiToken = ws.getString("token")
            }

            if (json.has("activation")) {
                val act = json.getJSONObject("activation")
                val code = act.optString("code", "")
                val challenge = act.optString("challenge", "")
                if (code.isNotBlank()) {
                    return@withContext ActivationState.CodeReady(code, challenge)
                }
            }

            // Si el servidor no envía "activation", el dispositivo ya fue autorizado y vinculado
            prefs.isXiaozhiLinked = true
            return@withContext ActivationState.Success
        } catch (e: Exception) {
            Log.e(TAG, "Error al solicitar código de activación", e)
            return@withContext ActivationState.Error(e.message ?: "Error desconocido")
        }
    }

    /**
     * Espera a que el usuario ingrese los 6 dígitos en la consola web de xiaozhi.me.
     * Envía la firma HMAC-SHA256 periódicamente hasta recibir HTTP 200.
     */
    suspend fun pollActivation(
        challenge: String,
        code: String,
        onStateChange: (ActivationState) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val mac = prefs.macAddress
        val clientId = prefs.clientId
        val hmacKey = prefs.hmacKey
        val activateUrl = "${otaUrl.removeSuffix("/")}/activate"

        val signature = computeHmacSha256(hmacKey, challenge)

        val payload = JSONObject().apply {
            put("Payload", JSONObject().apply {
                put("algorithm", "hmac-sha256")
                put("serial_number", "SN-TREKO-${mac.replace(":", "")}")
                put("challenge", challenge)
                put("hmac", signature)
            })
        }

        val request = Request.Builder()
            .url(activateUrl)
            .addHeader("Device-Id", mac)
            .addHeader("Client-Id", clientId)
            .addHeader("Activation-Version", "2")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        onStateChange(ActivationState.PollingApproval(code))

        // Polling hasta 80 veces (~4 minutos)
        for (attempt in 1..80) {
            try {
                val response = client.newCall(request).execute()
                val codeStatus = response.code
                val resBody = response.body?.string() ?: ""
                response.close()

                if (codeStatus == 200) {
                    prefs.isXiaozhiLinked = true
                    onStateChange(ActivationState.Success)
                    return@withContext true
                }
                // HTTP 202 = Esperando que el usuario presione 'Link' en xiaozhi.me
                delay(3000L)
            } catch (e: Exception) {
                delay(3000L)
            }
        }
        onStateChange(ActivationState.Error("Tiempo de espera agotado. Vuelve a intentarlo."))
        return@withContext false
    }

    private fun computeHmacSha256(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        val hash = mac.doFinal(data.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
