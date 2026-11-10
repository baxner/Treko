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
 * Cliente universal y dinámico de Home Assistant para Android.
 * Permite descubrir y controlar cualquier entidad, estado y servicio disponible
 * en cualquier instancia de Home Assistant sin hardcodear dispositivos locales.
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
        this.haUrl = sanitizeUrl(url)
        this.haToken = token.trim()
    }

    private fun getBaseUrl(): String = sanitizeUrl(haUrl)

    private fun sanitizeUrl(url: String): String {
        var cleanUrl = url.trim().removeSuffix("/")
        if (cleanUrl.endsWith("/api")) {
            cleanUrl = cleanUrl.removeSuffix("/api")
        }
        return cleanUrl
    }

    /**
     * Llama dinámicamente a cualquier servicio de Home Assistant (POST /api/services/{domain}/{service}).
     * Universal: funciona para notify, media_player, light, switch, climate, scene, script, automation, cover, vacuum, lock, etc.
     */
    suspend fun callService(
        domain: String,
        service: String,
        serviceData: JSONObject? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            var d = domain.trim().lowercase().removePrefix("service.")
            var s = service.trim().lowercase()

            // Manejar si el servicio viene con prefijo "domain.service"
            if (s.contains(".")) {
                if (d.isBlank()) {
                    d = s.substringBefore(".")
                }
                s = s.substringAfter(".")
            }

            if (d.isBlank() || s.isBlank()) {
                return@withContext "Error: Dominio o servicio no especificado para Home Assistant."
            }

            val url = "${getBaseUrl()}/api/services/$d/$s"
            val payload = serviceData ?: JSONObject()
            val body = payload.toString().toRequestBody("application/json".toMediaType())

            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $haToken")
                .post(body)
                .build()

            client.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    "Servicio '$d.$s' ejecutado correctamente en Home Assistant."
                } else {
                    "Error ejecutando servicio '$d.$s' en Home Assistant (${resp.code}): $respBody"
                }
            }
        } catch (e: Exception) {
            "Excepción al llamar al servicio '$domain.$service': ${e.message}"
        }
    }

    /**
     * Envía anuncios o mensajes de voz a través de los altavoces (Alexa, Echo, Google Cast, etc.)
     * descubriendo dinámicamente los servicios 'notify' disponibles en la instancia.
     */
    suspend fun broadcastMessage(
        message: String,
        target: String = ""
    ): String = withContext(Dispatchers.IO) {
        try {
            val notifyServices = getAvailableServicesForDomain("notify")
            val tClean = target.trim().lowercase()

            // Seleccionar dinámicamente el servicio de notificación adecuado
            val chosenService = when {
                // Caso 1: Destino específico solicitado por el usuario (ej. 'cocina', 'recamara', 'salon')
                tClean.isNotBlank() && tClean !in listOf("todas", "casa", "en todas partes", "all") -> {
                    notifyServices.firstOrNull { it.contains(tClean) }
                        ?: notifyServices.firstOrNull { it.contains("alexa_media") }
                        ?: "notify"
                }
                // Caso 2: Transmisión general a toda la casa
                else -> {
                    notifyServices.firstOrNull { 
                        it.contains("en_todas_partes") || it.contains("todas") || it.contains("all") || it.contains("broadcast")
                    } ?: notifyServices.firstOrNull { it.contains("alexa_media") }
                      ?: "notify"
                }
            }

            val payload = JSONObject().apply {
                put("message", message)
                put("title", "Treko")
                put("data", JSONObject().apply {
                    put("type", "announce")
                })
            }

            val result = callService(domain = "notify", service = chosenService, serviceData = payload)
            if (result.contains("correctamente")) {
                "Anuncio enviado con éxito mediante el servicio '$chosenService': \"$message\""
            } else {
                result
            }
        } catch (e: Exception) {
            "Error enviando anuncio por Home Assistant: ${e.message}"
        }
    }

    /**
     * Consulta el estado y atributos de una entidad específica (GET /api/states/{entity_id}).
     */
    suspend fun getEntityState(entityId: String): String = withContext(Dispatchers.IO) {
        try {
            val eid = entityId.trim().lowercase()
            val url = "${getBaseUrl()}/api/states/$eid"

            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $haToken")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext "No se encontró la entidad '$entityId' en Home Assistant (${resp.code})."
                }
                val json = JSONObject(resp.body?.string() ?: return@withContext "Respuesta vacía de Home Assistant.")
                val state = json.optString("state", "desconocido")
                val attrs = json.optJSONObject("attributes")
                val friendlyName = attrs?.optString("friendly_name") ?: eid

                val summary = StringBuilder("Entidad: $friendlyName ($eid)\nEstado: $state")
                if (attrs != null) {
                    summary.append("\nAtributos:")
                    val keys = attrs.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key in listOf("icon", "supported_features", "entity_picture")) continue
                        val value = attrs.opt(key)
                        if (value != null && value.toString().length < 100) {
                            summary.append("\n  - $key: $value")
                        }
                    }
                }
                summary.toString()
            }
        } catch (e: Exception) {
            "Excepción al consultar estado de '$entityId': ${e.message}"
        }
    }

    /**
     * Lista servicios disponibles en Home Assistant (GET /api/services), opcionalmente filtrados por dominio.
     */
    suspend fun listServices(domainFilter: String = ""): String = withContext(Dispatchers.IO) {
        try {
            val url = "${getBaseUrl()}/api/services"
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $haToken")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext "Error obteniendo servicios de Home Assistant (${resp.code})."
                }
                val array = JSONArray(resp.body?.string() ?: return@withContext "Respuesta vacía.")
                val filter = domainFilter.trim().lowercase()
                val sb = StringBuilder()

                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val domain = obj.optString("domain")
                    if (filter.isNotBlank() && filter != "all" && !domain.equals(filter, ignoreCase = true)) {
                        continue
                    }
                    val servicesObj = obj.optJSONObject("services")
                    val serviceNames = mutableListOf<String>()
                    if (servicesObj != null) {
                        val it = servicesObj.keys()
                        while (it.hasNext()) {
                            serviceNames.add(it.next())
                        }
                    }
                    if (serviceNames.isNotEmpty()) {
                        sb.append("Dominio '$domain': ${serviceNames.joinToString(", ")}\n")
                    }
                }

                if (sb.isEmpty()) {
                    "No se encontraron servicios para el dominio '$domainFilter'."
                } else {
                    sb.toString().trim()
                }
            }
        } catch (e: Exception) {
            "Error listando servicios: ${e.message}"
        }
    }

    /**
     * Lista entidades dinámicamente con soporte para filtros de dominio y búsqueda por texto.
     */
    suspend fun listEntities(domainFilter: String = "", search: String = ""): String = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${getBaseUrl()}/api/states")
                .addHeader("Authorization", "Bearer $haToken")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext "Error conectando a Home Assistant (${resp.code})"
                val array = JSONArray(resp.body?.string() ?: return@withContext "Respuesta vacía")
                val results = mutableListOf<String>()
                val dFilter = domainFilter.trim().lowercase().removeSuffix("s")
                val sQuery = search.trim().lowercase()

                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val eid = item.getString("entity_id")
                    if (eid.contains("_segment_")) continue // Omitir subsegmentos internos de WLED

                    val domain = eid.substringBefore(".")
                    if (dFilter.isNotBlank() && dFilter != "all") {
                        if (!domain.startsWith(dFilter) && !dFilter.startsWith(domain)) {
                            continue
                        }
                    }

                    val state = item.optString("state", "desconocido")
                    val attrs = item.optJSONObject("attributes")
                    val friendly = attrs?.optString("friendly_name") ?: eid

                    if (sQuery.isNotBlank()) {
                        val matchesSearch = friendly.lowercase().contains(sQuery) ||
                                eid.lowercase().contains(sQuery) ||
                                domain.contains(sQuery)
                        if (!matchesSearch) continue
                    }

                    results.add("$friendly ($eid: $state)")
                }

                if (results.isEmpty()) {
                    val desc = if (sQuery.isNotBlank()) "búsqueda '$search'" else "filtro '$domainFilter'"
                    "No se encontraron entidades en Home Assistant para $desc."
                } else {
                    val count = results.size
                    val listToShow = if (count > 40) {
                        results.take(40).joinToString(", ") + "... (+${count - 40} entidades más)"
                    } else {
                        results.joinToString(", ")
                    }
                    "Entidades encontradas ($count): $listToShow"
                }
            }
        } catch (e: Exception) {
            "Error obteniendo lista de entidades: ${e.message}"
        }
    }

    /**
     * Helper de alto nivel para controlar dispositivos comunes (luces, interruptores, enchufes).
     * Resuelve nombres amigables con búsqueda aproximada dinámica SIN NINGÚN DISPOSITIVO HARDCODEADO.
     */
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
            val serviceUrl = "${getBaseUrl()}/api/services/$domain/$svc"
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

    private data class HaEntity(val id: String, val friendlyName: String)

    private fun resolveEntity(query: String): HaEntity? {
        val qRaw = query.trim().lowercase()
        val qClean = qRaw
            .removePrefix("light.")
            .removePrefix("switch.")
            .removePrefix("media_player.")
            .replace(Regex("^(el|la|los|las|luz|luces|foco|focos|de|mi|del)\\s+"), "")
            .replace("_", " ")
            .trim()

        val req = Request.Builder()
            .url("${getBaseUrl()}/api/states")
            .addHeader("Authorization", "Bearer $haToken")
            .get()
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val jsonArray = JSONArray(resp.body?.string() ?: return null)
            val allControllable = mutableListOf<HaEntity>()
            val candidates = mutableListOf<Pair<HaEntity, Int>>()

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val eid = item.getString("entity_id")
                if (eid.contains("_segment_")) continue // omitir subsegmentos

                val attrs = item.optJSONObject("attributes")
                val friendly = attrs?.optString("friendly_name") ?: eid
                val entity = HaEntity(eid, friendly)

                val domain = eid.substringBefore(".")
                if (domain in listOf("light", "switch", "media_player", "fan", "cover")) {
                    allControllable.add(entity)
                }

                val eidClean = eid.substringAfter(".").replace("_", " ").lowercase()
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

                // 3. Coincidencia por palabras
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

            // Fallback genérico sin ningún dispositivo hardcodeado
            if (qRaw in listOf("luz", "luces") || qClean.isEmpty()) {
                return allControllable.firstOrNull { it.id.startsWith("light.") } ?: allControllable.firstOrNull()
            }
        }
        return null
    }

    private fun getAvailableServicesForDomain(domain: String): List<String> {
        return try {
            val url = "${getBaseUrl()}/api/services"
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $haToken")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val array = JSONArray(resp.body?.string().orEmpty())
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    if (obj.optString("domain") == domain) {
                        val servicesObj = obj.optJSONObject("services") ?: return emptyList()
                        val result = mutableListOf<String>()
                        val it = servicesObj.keys()
                        while (it.hasNext()) {
                            result.add(it.next())
                        }
                        return result
                    }
                }
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
