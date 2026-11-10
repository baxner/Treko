package com.treko.core.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cliente de Home Assistant para Android con soporte nativo de luces CCT físicas (Kelvin).
 */
class HomeAssistantClient(
    private var haUrl: String,
    private var haToken: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun updateCredentials(url: String, token: String) {
        var cleanUrl = url.trim().removeSuffix("/")
        if (cleanUrl.endsWith("/api")) {
            cleanUrl = cleanUrl.removeSuffix("/api")
        }
        this.haUrl = cleanUrl
        this.haToken = token.trim()
    }

    suspend fun controlDevice(
        deviceQuery: String,
        accion: String = "encender",
        brillo: Int = 0,
        colorOrTemp: String = ""
    ): String = withContext(Dispatchers.IO) {
        try {
            val entity = resolveEntity(deviceQuery) 
                ?: return@withContext "Dispositivo '$deviceQuery' no encontrado en Home Assistant."

            val isLight = entity.id.startsWith("light.")
            val a = accion.trim().lowercase()

            val hasSettings = colorOrTemp.isNotBlank() || brillo > 0
            val isOff = a.startsWith("apa") || a in listOf("off", "desactivar", "apagar")

            val svc = when {
                isOff && !hasSettings -> "turn_off"
                a.startsWith("enc") || a in listOf("on", "prender", "activar") || hasSettings -> "turn_on"
                a in listOf("alternar", "toggle") -> "toggle"
                else -> "turn_on"
            }

            val payload = JSONObject().apply {
                put("entity_id", entity.id)
                if (isLight && svc == "turn_on") {
                    if (brillo > 0) {
                        put("brightness_pct", brillo.coerceIn(1, 100))
                    }
                    if (colorOrTemp.isNotBlank()) {
                        applyColorOrTemperature(this, colorOrTemp)
                    }
                }
            }

            val domain = entity.id.substringBefore(".")
            val serviceUrl = "$haUrl/api/services/$domain/$svc"
            val body = payload.toString().toRequestBody("application/json".toMediaType())

            val req = Request.Builder()
                .url(serviceUrl)
                .addHeader("Authorization", "Bearer $haToken")
                .post(body)
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val statusText = if (svc == "turn_on") "encendido" else "apagado"
                    return@withContext "Dispositivo '${entity.friendlyName}' $statusText correctamente."
                } else {
                    return@withContext "Error en Home Assistant (${resp.code}): ${resp.message}"
                }
            }
        } catch (e: Exception) {
            "Excepción al conectar con Home Assistant: ${e.message}"
        }
    }

    private fun applyColorOrTemperature(payload: JSONObject, raw: String) {
        val c = raw.trim().lowercase()
        val cClean = c.replace(Regex("^(color\\s+(de\\s+)?|luz\\s+|tono\\s+)"), "").trim()

        // 1. Grados Kelvin explícitos (ej. "3000k")
        val kelvinMatch = Regex("(\\d{4})\\s*k?").find(c)
        if (kelvinMatch != null) {
            val k = kelvinMatch.groupValues[1].toInt()
            if (k in 2000..9000) {
                payload.put("color_temp_kelvin", k)
                return
            }
        }

        // 2. Cálida / suave -> 2700K
        if (listOf("calid", "cálid", "warm", "suave", "amarillent").any { it in c }) {
            payload.put("color_temp_kelvin", 2700)
            return
        }

        // 3. Fría / día -> 6000K
        if (listOf("fri", "frí", "cool", "dia", "día").any { it in c }) {
            payload.put("color_temp_kelvin", 6000)
            return
        }

        // 4. Blanca / neutra -> 4000K (LEDs blancos físicos, CERO simulación RGB)
        if (listOf("blanc", "white", "neutr", "natural").any { it in c }) {
            payload.put("color_temp_kelvin", 4000)
            return
        }

        // 5. Mapeo RGB básico
        val rgbMap = mapOf(
            "rojo" to "red", "azul" to "blue", "verde" to "green", "amarillo" to "yellow",
            "naranja" to "orange", "morado" to "purple", "rosa" to "pink"
        )
        val colorName = rgbMap[cClean] ?: cClean
        payload.put("color_name", colorName)
    }

    private data class HaEntity(val id: String, val friendlyName: String)

    private fun resolveEntity(query: String): HaEntity? {
        val q = query.trim().lowercase()
        val req = Request.Builder()
            .url("$haUrl/api/states")
            .addHeader("Authorization", "Bearer $haToken")
            .get()
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val jsonArray = JSONArray(resp.body?.string() ?: return null)
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val eid = item.getString("entity_id")
                val attrs = item.optJSONObject("attributes")
                val friendly = attrs?.optString("friendly_name") ?: eid
                if (friendly.lowercase() == q || eid.lowercase() == q || q in friendly.lowercase()) {
                    return HaEntity(eid, friendly)
                }
            }
        }
        return null
    }
}
