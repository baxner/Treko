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

        // 5. Mapeo RGB universal compatible con bombillas y tiras LED
        val rgbValues = mapOf(
            "azul" to listOf(0, 0, 255),
            "blue" to listOf(0, 0, 255),
            "rojo" to listOf(255, 0, 0),
            "red" to listOf(255, 0, 0),
            "verde" to listOf(0, 255, 0),
            "green" to listOf(0, 255, 0),
            "amarillo" to listOf(255, 255, 0),
            "yellow" to listOf(255, 255, 0),
            "morado" to listOf(128, 0, 128),
            "purple" to listOf(128, 0, 128),
            "naranja" to listOf(255, 165, 0),
            "orange" to listOf(255, 165, 0),
            "rosa" to listOf(255, 105, 180),
            "pink" to listOf(255, 105, 180),
            "cyan" to listOf(0, 255, 255),
            "turquesa" to listOf(64, 224, 208)
        )
        val rgb = rgbValues[cClean]
        if (rgb != null) {
            payload.put("rgb_color", JSONArray(rgb))
            return
        }
        val rgbMap = mapOf(
            "rojo" to "red", "azul" to "blue", "verde" to "green", "amarillo" to "yellow",
            "naranja" to "orange", "morado" to "purple", "rosa" to "pink"
        )
        val colorName = rgbMap[cClean] ?: cClean
        payload.put("color_name", colorName)
    }

    suspend fun listEntities(domainFilter: String = ""): String = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$haUrl/api/states")
                .addHeader("Authorization", "Bearer $haToken")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext "Error conectando a Home Assistant (${resp.code})"
                val array = JSONArray(resp.body?.string() ?: return@withContext "Respuesta vacía")
                val results = mutableListOf<String>()
                val dFilter = domainFilter.trim().lowercase().removeSuffix("s")

                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val eid = item.getString("entity_id")
                    if (eid.contains("_segment_")) continue // Omitir subsegmentos de tiras WLED

                    val state = item.optString("state", "desconocido")
                    val attrs = item.optJSONObject("attributes")
                    val friendly = attrs?.optString("friendly_name") ?: eid
                    val domain = eid.substringBefore(".")

                    if (dFilter.isNotBlank() && dFilter != "all" && !domain.startsWith(dFilter)) {
                        continue
                    }
                    if (domain in listOf("light", "switch", "climate", "cover", "sensor", "binary_sensor")) {
                        results.add("$friendly ($eid: $state)")
                    }
                }
                if (results.isEmpty()) {
                    "No se encontraron dispositivos en Home Assistant para el filtro '$domainFilter'."
                } else {
                    "Dispositivos en Home Assistant (${results.size}): " + results.joinToString(", ")
                }
            }
        } catch (e: Exception) {
            "Error obteniendo lista de entidades: ${e.message}"
        }
    }

    private data class HaEntity(val id: String, val friendlyName: String)

    private fun resolveEntity(query: String): HaEntity? {
        val qRaw = query.trim().lowercase()
        val qClean = qRaw
            .removePrefix("light.")
            .removePrefix("switch.")
            .replace(Regex("^(el|la|los|las|luz|luces|foco|focos|de|mi|del)\\s+"), "")
            .replace("_", " ")
            .trim()

        val req = Request.Builder()
            .url("$haUrl/api/states")
            .addHeader("Authorization", "Bearer $haToken")
            .get()
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val jsonArray = JSONArray(resp.body?.string() ?: return null)
            val allLights = mutableListOf<HaEntity>()
            val candidates = mutableListOf<Pair<HaEntity, Int>>()

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val eid = item.getString("entity_id")
                if (eid.contains("_segment_")) continue // omitir subsegmentos

                val attrs = item.optJSONObject("attributes")
                val friendly = attrs?.optString("friendly_name") ?: eid
                val entity = HaEntity(eid, friendly)

                if (eid.startsWith("light.")) {
                    allLights.add(entity)
                }

                val eidClean = eid.removePrefix("light.").removePrefix("switch.").replace("_", " ").lowercase()
                val friendlyClean = friendly.lowercase().replace("_", " ")

                // 1. Coincidencia exacta
                if (eid.equals(qRaw, ignoreCase = true) || friendly.equals(qRaw, ignoreCase = true)) {
                    return entity
                }
                if (eidClean == qClean || friendlyClean == qClean) {
                    return entity
                }

                // 2. Coincidencia de subcadena
                var score = 0
                if (friendlyClean.contains(qClean) || eidClean.contains(qClean)) {
                    score += 50
                }
                if (qClean.contains(friendlyClean) || qClean.contains(eidClean)) {
                    score += 40
                }

                // 3. Coincidencia por palabras (ej. "colors", "smart", "ambiente")
                val qWords = qClean.split(" ", "_").filter { it.length > 2 }
                for (w in qWords) {
                    if (eidClean.contains(w) || friendlyClean.contains(w)) {
                        score += 25
                    }
                }

                if (score > 0) {
                    candidates.add(entity to score)
                }
            }

            val best = candidates.maxByOrNull { it.second }
            if (best != null && best.second >= 25) {
                return best.first
            }

            // Fallback para términos generales ("luces", "mi cuarto", "cuarto")
            if (qRaw in listOf("luz", "luces", "cuarto", "recamara", "mi cuarto", "habitacion") ||
                qClean in listOf("cuarto", "recamara", "habitacion", "")
            ) {
                return allLights.firstOrNull { it.id == "light.colors_smart" } ?: allLights.firstOrNull()
            }
        }
        return null
    }
}
