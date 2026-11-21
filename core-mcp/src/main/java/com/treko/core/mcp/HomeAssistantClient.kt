package com.treko.core.mcp

import android.util.Log
import com.treko.core.common.model.TrekoSkills
import com.treko.core.common.util.TrekoLogBuffer
import com.treko.core.mcp.watch.HaEventStream
import com.treko.core.mcp.watch.HaStateLite
import com.treko.core.mcp.watch.WatchCatalog
import com.treko.core.mcp.watch.WatchSensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
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
    companion object {
        private const val TAG = "HomeAssistantClient"
        private const val STATES_CACHE_TTL_MS = 3000L
        private const val VISION_PROVIDER_TTL_MS = 30 * 60_000L
        private const val VISION_PROVIDER_MISSING_TTL_MS = 5 * 60_000L

        /** Live-view suffixes some integrations give camera entities (English and Spanish names). */
        private val LIVE_SUFFIX = Regex("_(live|live_view|liveview|vista_en_vivo)$")

        /**
         * The `_snapshot` sibling of a live-view camera entity (`camera.door_live` →
         * `camera.door_snapshot`), tried first for vision because battery and live-view cameras
         * often cannot hand out a still image. Only a trailing live suffix counts, so ids that
         * merely contain "live" (`camera.delivery_door`) are left alone; null when not live.
         */
        fun snapshotSiblingOf(cameraEntityId: String): String? {
            val id = cameraEntityId.trim()
            val dot = id.indexOf('.')
            if (dot <= 0 || dot == id.lastIndex) return null
            val objectId = id.substring(dot + 1)
            if (!LIVE_SUFFIX.containsMatchIn(objectId)) return null
            val base = objectId.replace(LIVE_SUFFIX, "")
            if (base.isEmpty()) return null
            return id.substring(0, dot + 1) + base + "_snapshot"
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedStates: JSONArray? = null

    @Volatile
    private var cachedStatesTimestampMs: Long = 0L

    internal var radioBrowserClient: RadioBrowserClient = RadioBrowserClient()

    fun updateCredentials(url: String, token: String) {
        this.haUrl = sanitizeUrl(url)
        this.haToken = token.trim()
        invalidateStatesCache()
        visionProviderCache = null
    }

    private fun invalidateStatesCache() {
        cachedStates = null
        cachedStatesTimestampMs = 0L
    }

    private fun getBaseUrl(): String = sanitizeUrl(haUrl)

    internal fun sanitizeUrl(url: String): String {
        var cleanUrl = url.trim().removeSuffix("/")
        if (cleanUrl.endsWith("/api")) {
            cleanUrl = cleanUrl.removeSuffix("/api")
        }
        return cleanUrl
    }

    internal fun hasValidConfig(): Boolean {
        val base = getBaseUrl()
        val hasHttpScheme = base.startsWith("http://", ignoreCase = true) ||
            base.startsWith("https://", ignoreCase = true)
        return hasHttpScheme && haToken.isNotBlank()
    }

    private fun fetchStatesArray(forceRefresh: Boolean = false): JSONArray? {
        val now = System.currentTimeMillis()
        val cached = cachedStates
        if (!forceRefresh && cached != null && (now - cachedStatesTimestampMs) < STATES_CACHE_TTL_MS) {
            return cached
        }
        if (!hasValidConfig()) return null
        val req = Request.Builder()
            .url("${getBaseUrl()}/api/states")
            .addHeader("Authorization", "Bearer $haToken")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val array = JSONArray(resp.body?.string().orEmpty())
            cachedStates = array
            cachedStatesTimestampMs = System.currentTimeMillis()
            return array
        }
    }

    /**
     * Llama dinámicamente a cualquier servicio de Home Assistant (POST /api/services/{domain}/{service}).
     * Universal: funciona para notify, media_player, light, switch, climate, scene, script, automation, cover, vacuum, lock, etc.
     * Incluye autocorrección tolerante a erratas de entity_id generadas por LLMs.
     */
    suspend fun callService(
        domain: String,
        service: String,
        serviceData: JSONObject? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            if (!hasValidConfig()) {
                return@withContext "Error: Home Assistant no está configurado con una URL válida (http/https) y token."
            }
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

            val payload = serviceData ?: JSONObject()

            // Auto-corrección inteligente de entity_id con tolerancia a erratas de LLMs (solo si es String)
            val rawEntityVal = payload.opt("entity_id")
            val requestedEid = if (rawEntityVal is String) rawEntityVal.trim() else ""
            var correctedEid = requestedEid
            if (requestedEid.isNotBlank()) {
                correctedEid = resolveEntityIdOrFuzzy(requestedEid, d)
                if (correctedEid != requestedEid) {
                    Log.i(TAG, "Corrigiendo entity_id con errata del LLM: '$requestedEid' -> '$correctedEid'")
                    payload.put("entity_id", correctedEid)
                }
            }

            val url = "${getBaseUrl()}/api/services/$d/$s"
            val body = payload.toString().toRequestBody("application/json".toMediaType())

            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $haToken")
                .post(body)
                .build()

            client.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    invalidateStatesCache()
                    val correctionNote = if (correctedEid.isNotBlank() && correctedEid != requestedEid) " (en '$correctedEid')" else ""
                    "Servicio '$d.$s' ejecutado correctamente en Home Assistant$correctionNote."
                } else {
                    "Error ejecutando servicio '$d.$s' en Home Assistant (${resp.code}): $respBody"
                }
            }
        } catch (e: Exception) {
            "Excepción al llamar al servicio '$domain.$service': ${e.message}"
        }
    }

    /**
     * Resuelve un entity_id exacto o busca la entidad real más cercana si el LLM tuvo un error tipográfico.
     */
    private fun resolveEntityIdOrFuzzy(rawEid: String, domainHint: String): String {
        try {
            val array = fetchStatesArray() ?: return rawEid
            val allEids = mutableListOf<String>()
            val domainEids = mutableListOf<String>()

            for (i in 0 until array.length()) {
                val eid = array.getJSONObject(i).optString("entity_id")
                if (eid.isBlank() || eid.contains("_segment_")) continue
                allEids.add(eid)
                if (domainHint.isNotBlank() && eid.startsWith("$domainHint.")) {
                    domainEids.add(eid)
                }
            }

            if (allEids.contains(rawEid)) {
                return rawEid
            }

            val candidatesPool = if (domainEids.isNotEmpty()) domainEids else allEids
            var bestEid: String? = null
            var bestSim = 0.0

            for (cand in candidatesPool) {
                val sim = stringSimilarity(rawEid.lowercase(), cand.lowercase())
                if (sim > bestSim) {
                    bestSim = sim
                    bestEid = cand
                }
            }

            if (bestEid != null && bestSim >= 0.65) {
                return bestEid
            }
        } catch (_: Exception) {
            // Mantener rawEid si la consulta falla
        }
        return rawEid
    }

    /**
     * Cálculo de similitud de cadenas basado en distancia Levenshtein normalizada (0.0 a 1.0).
     */
    private fun stringSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        val l1 = s1.length
        val l2 = s2.length
        val dp = Array(l1 + 1) { IntArray(l2 + 1) }
        for (i in 0..l1) dp[i][0] = i
        for (j in 0..l2) dp[0][j] = j
        for (i in 1..l1) {
            for (j in 1..l2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        val dist = dp[l1][l2]
        return 1.0 - (dist.toDouble() / maxOf(l1, l2))
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
                        // access_token (cameras) must never reach the model or the logs.
                        if (key in listOf("icon", "supported_features", "entity_picture", "access_token")) continue
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
     * Formatea los IDs de forma inequívoca para evitar alucinaciones en modelos de lenguaje.
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

                    results.add("• $friendly -> ID: $eid [Estado: $state]")
                }

                if (results.isEmpty()) {
                    val desc = if (sQuery.isNotBlank()) "búsqueda '$search'" else "filtro '$domainFilter'"
                    "No se encontraron entidades en Home Assistant para $desc."
                } else {
                    val count = results.size
                    val listToShow = if (count > 30) {
                        results.take(30).joinToString("\n") + "\n... (+${count - 30} entidades más)"
                    } else {
                        results.joinToString("\n")
                    }
                    "Entidades encontradas ($count):\n$listToShow"
                }
            }
        } catch (e: Exception) {
            "Error obteniendo lista de entidades: ${e.message}"
        }
    }

    /**
     * Helper de alto nivel para controlar cualquier dispositivo común (televisores, luces, interruptores, enchufes, etc.).
     * Resuelve nombres amigables con búsqueda semántica dinámica SIN NINGÚN DISPOSITIVO HARDCODEADO.
     */
    suspend fun controlDevice(
        deviceQuery: String,
        accion: String = "encender",
        brillo: Int = 0,
        colorOrTemp: String = "",
        source: String = ""
    ): String = withContext(Dispatchers.IO) {
        try {
            val entity = resolveEntity(deviceQuery) 
                ?: return@withContext "Dispositivo '$deviceQuery' no encontrado en Home Assistant. Usa 'home_assistant_list_entities' para ver los dispositivos disponibles."

            val domain = entity.domain
            val a = accion.trim().lowercase()
            val sInput = source.trim()

            // 1. Manejo especial de cambio de entrada / fuente de video (ej. HDMI)
            if (sInput.isNotBlank() || a in listOf("hdmi", "source", "entrada", "cambiar_fuente", "select_source") || a.contains("hdmi")) {
                val targetSource = when {
                    sInput.isNotBlank() -> sInput
                    a.contains("hdmi") -> "HDMI"
                    else -> "TV"
                }

                if (domain == "media_player") {
                    val serviceUrl = "${getBaseUrl()}/api/services/media_player/select_source"
                    val payload = JSONObject().apply {
                        put("entity_id", entity.id)
                        put("source", targetSource)
                    }
                    val body = payload.toString().toRequestBody("application/json".toMediaType())
                    val req = Request.Builder()
                        .url(serviceUrl)
                        .addHeader("Authorization", "Bearer $haToken")
                        .post(body)
                        .build()

                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            return@withContext "Entrada de '${entity.friendlyName}' cambiada a '$targetSource' correctamente."
                        } else {
                            return@withContext "Error cambiando entrada en Home Assistant (${resp.code}): ${resp.message}"
                        }
                    }
                }
            }

            val hasSettings = colorOrTemp.isNotBlank() || brillo > 0
            val isOff = a.startsWith("apa") || a in listOf("off", "desactivar", "apagar", "cerrar", "close", "detener", "stop")
            val isToggle = a in listOf("alternar", "toggle")

            val svc = when (domain) {
                "cover" -> if (isOff) "close_cover" else "open_cover"
                else -> when {
                    isOff && !hasSettings -> "turn_off"
                    isToggle -> "toggle"
                    else -> "turn_on"
                }
            }

            val payload = JSONObject().apply {
                put("entity_id", entity.id)
                if (domain == "light" && svc == "turn_on") {
                    if (brillo > 0) {
                        put("brightness_pct", brillo.coerceIn(1, 100))
                    }
                    if (colorOrTemp.isNotBlank()) {
                        applyColorOrTemperature(this, colorOrTemp)
                    }
                }
            }

            val serviceUrl = "${getBaseUrl()}/api/services/$domain/$svc"
            val body = payload.toString().toRequestBody("application/json".toMediaType())

            val req = Request.Builder()
                .url(serviceUrl)
                .addHeader("Authorization", "Bearer $haToken")
                .post(body)
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val statusText = when {
                        isOff -> "apagado"
                        isToggle -> "alternado"
                        else -> "encendido"
                    }
                    return@withContext "Dispositivo '${entity.friendlyName}' ($domain) $statusText correctamente."
                } else {
                    return@withContext "Error en Home Assistant (${resp.code}): ${resp.message}"
                }
            }
        } catch (e: Exception) {
            "Excepción al conectar con Home Assistant: ${e.message}"
        }
    }

    internal fun applyColorOrTemperature(payload: JSONObject, raw: String) {
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
            "celeste" to listOf(100, 180, 255),
            "rojo" to listOf(255, 0, 0),
            "red" to listOf(255, 0, 0),
            "verde" to listOf(0, 255, 0),
            "green" to listOf(0, 255, 0),
            "amarillo" to listOf(255, 255, 0),
            "yellow" to listOf(255, 255, 0),
            "dorado" to listOf(255, 215, 0),
            "morado" to listOf(128, 0, 128),
            "violeta" to listOf(138, 43, 226),
            "lila" to listOf(180, 120, 230),
            "purple" to listOf(128, 0, 128),
            "naranja" to listOf(255, 165, 0),
            "orange" to listOf(255, 165, 0),
            "rosa" to listOf(255, 105, 180),
            "pink" to listOf(255, 105, 180),
            "magenta" to listOf(255, 0, 255),
            "cyan" to listOf(0, 255, 255),
            "cian" to listOf(0, 255, 255),
            "turquesa" to listOf(64, 224, 208)
        )
        val rgb = rgbValues[cClean]
        if (rgb != null) {
            payload.put("rgb_color", JSONArray(rgb))
            return
        }
        val rgbMap = mapOf(
            "rojo" to "red", "azul" to "blue", "verde" to "green", "amarillo" to "yellow",
            "naranja" to "orange", "morado" to "purple", "rosa" to "pink", "cian" to "cyan"
        )
        val colorName = rgbMap[cClean] ?: cClean
        payload.put("color_name", colorName)
    }

    private data class HaEntity(
        val id: String,
        val friendlyName: String,
        val domain: String,
        val deviceClass: String,
        val state: String
    )

    private fun resolveEntity(query: String): HaEntity? {
        val qRaw = query.trim().lowercase()
        val qClean = qRaw
            .removePrefix("light.")
            .removePrefix("switch.")
            .removePrefix("media_player.")
            .removePrefix("remote.")
            .removePrefix("fan.")
            .removePrefix("cover.")
            .replace(Regex("^(el|la|los|las|de|mi|del|un|una|dispositivo|aparato|apaga|prende|enciende|pon|cambia|cambiar|a)\\s+"), "")
            .replace("_", " ")
            .trim()

        val tvKeywords = listOf("tele", "television", "televisión", "televisor", "pantalla", "tv", "hdmi", "entrada")
        val lightKeywords = listOf("luz", "luces", "foco", "focos", "bombilla", "bombillas", "lampara", "lámpara")
        val speakerKeywords = listOf("bocina", "bocinas", "altavoz", "altavoces", "parlante", "parlantes", "echo", "alexa")
        val switchKeywords = listOf("enchufe", "enchufes", "switch", "interruptor", "contacto", "tomacorriente")

        val isTv = tvKeywords.any { qClean.contains(it) || qRaw.contains(it) }
        val isLight = lightKeywords.any { qClean.contains(it) || qRaw.contains(it) }
        val isSpeaker = speakerKeywords.any { qClean.contains(it) || qRaw.contains(it) }
        val isSwitch = switchKeywords.any { qClean.contains(it) || qRaw.contains(it) }

        val synonyms = mapOf(
            "tele" to listOf("tv", "television", "televisor", "qled"),
            "television" to listOf("tv", "tele", "televisor", "qled"),
            "televisión" to listOf("tv", "tele", "televisor", "qled"),
            "pantalla" to listOf("tv", "tele", "televisor", "qled"),
            "tv" to listOf("tele", "television", "televisor", "qled"),
            "foco" to listOf("luz", "bombilla", "lampara"),
            "focos" to listOf("luces", "bombillas", "lamparas"),
            "luz" to listOf("foco", "bombilla", "lampara"),
            "luces" to listOf("focos", "bombillas", "lamparas"),
            "bocina" to listOf("altavoz", "speaker", "echo", "alexa"),
            "bocinas" to listOf("altavoces", "speakers", "echo", "alexa"),
            "altavoz" to listOf("bocina", "speaker", "echo", "alexa"),
            "altavoces" to listOf("bocinas", "speakers", "echo", "alexa"),
            "cuarto" to listOf("dormitorio", "recamara", "recámara", "habitacion", "habitación", "pieza", "bedroom", "room"),
            "recamara" to listOf("dormitorio", "cuarto", "habitacion", "habitación", "bedroom"),
            "recámara" to listOf("dormitorio", "cuarto", "habitacion", "habitación", "bedroom"),
            "dormitorio" to listOf("cuarto", "recamara", "recámara", "habitacion", "habitación", "bedroom"),
            "habitacion" to listOf("cuarto", "dormitorio", "recamara", "recámara", "bedroom"),
            "habitación" to listOf("cuarto", "dormitorio", "recamara", "recámara", "bedroom"),
            "sala" to listOf("living", "estancia")
        )

        val jsonArray = fetchStatesArray() ?: return null
        run {
            val candidates = mutableListOf<Pair<HaEntity, Int>>()
            val allControllable = mutableListOf<HaEntity>()

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val eid = item.getString("entity_id")
                if (eid.contains("_segment_")) continue // omitir subsegmentos

                val domain = eid.substringBefore(".")
                if (domain !in listOf("media_player", "light", "switch", "remote", "fan", "cover")) {
                    continue
                }

                val attrs = item.optJSONObject("attributes")
                val friendly = attrs?.optString("friendly_name") ?: eid
                val deviceClass = attrs?.optString("device_class") ?: ""
                val state = item.optString("state", "unknown")
                val entity = HaEntity(eid, friendly, domain, deviceClass, state)

                allControllable.add(entity)

                val eidClean = eid.substringAfter(".").replace("_", " ").lowercase()
                val friendlyClean = friendly.lowercase().replace("_", " ")

                // 1. Coincidencia exacta
                if (eid.equals(qRaw, ignoreCase = true) || friendly.equals(qRaw, ignoreCase = true)) {
                    return entity
                }
                if (eidClean == qClean || friendlyClean == qClean) {
                    return entity
                }

                var score = 0

                // Penalizar fuertemente dispositivos desconectados / no disponibles
                if (state in listOf("unavailable", "unknown")) {
                    score -= 200
                } else {
                    score += 20 // Bonus por estar disponible
                }

                // Bonificación por categoría semántica y device_class
                if (isTv) {
                    if (deviceClass == "tv") {
                        score += 100
                    } else if (domain in listOf("media_player", "remote")) {
                        score += 40
                    }
                    if (domain == "switch" || domain == "sensor") {
                        score -= 80
                    }
                }

                if (isLight) {
                    if (domain == "light") {
                        score += 80
                    } else if (domain == "switch") {
                        score -= 30
                    }
                }

                if (isSwitch && domain == "switch") {
                    score += 80
                }

                if (isSpeaker) {
                    if (deviceClass == "speaker" || friendlyClean.contains("echo") || friendlyClean.contains("alexa")) {
                        score += 80
                    }
                }

                // Coincidencia de palabras clave y sinónimos
                val qWords = qClean.split(" ", "_").filter { it.length > 1 }
                for (w in qWords) {
                    if (friendlyClean.contains(w) || eidClean.contains(w)) {
                        score += 40
                    }
                    val synList = synonyms[w] ?: emptyList()
                    for (syn in synList) {
                        if (friendlyClean.contains(syn) || eidClean.contains(syn)) {
                            score += 30
                        }
                    }
                }

                // Coincidencia de subcadena completa
                if (qClean.isNotBlank() && (friendlyClean.contains(qClean) || eidClean.contains(qClean))) {
                    score += 50
                }
                if (friendlyClean.isNotBlank() && qClean.contains(friendlyClean)) {
                    score += 40
                }

                if (score > 0) {
                    candidates.add(entity to score)
                }
            }

            val best = candidates.maxByOrNull { it.second }
            if (best != null && best.second >= 25) {
                return best.first
            }

            // Fallbacks genéricos por tipo de consulta
            if (isTv) {
                return allControllable.firstOrNull { it.deviceClass == "tv" && it.state !in listOf("unavailable", "unknown") }
                    ?: allControllable.firstOrNull { it.domain in listOf("media_player", "remote") && it.state !in listOf("unavailable", "unknown") }
            }
            if (isLight || qRaw in listOf("luz", "luces") || qClean.isEmpty()) {
                return allControllable.firstOrNull { it.domain == "light" && it.state !in listOf("unavailable", "unknown") }
                    ?: allControllable.firstOrNull { it.domain == "light" }
                    ?: allControllable.firstOrNull()
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

    data class CameraInfo(
        val entityId: String,
        val friendlyName: String,
        val state: String,
        val snapshotUrl: String
    )

    data class HaCameraGroup(
        val key: String,
        val friendlyName: String,
        val primaryEntityId: String,
        val snapshotEntityId: String,
        val liveEntityId: String?,
        val state: String,
        val snapshotUrl: String
    )

    internal data class HomeEntitySnapshot(
        val entityId: String,
        val friendlyName: String,
        val state: String,
        val deviceClass: String = "",
        val unitOfMeasurement: String = "",
        val mediaTitle: String = "",
        val mediaArtist: String = "",
        val source: String = "",
        val appName: String = "",
        val temperature: Double? = null,
        val currentTemperature: Double? = null,
        val temperatureUnit: String = "°C",
        val humidity: Double? = null,
        val hvacAction: String = "",
        val isAlexa: Boolean = false
    )

    internal data class MediaPlayerCandidate(
        val entityId: String,
        val friendlyName: String,
        val state: String,
        val deviceClass: String = "",
        val isAlexa: Boolean = false
    )

    internal data class PlayMediaCommandDraft(
        val mediaContentId: String,
        val mediaContentType: String
    )

    internal enum class MediaIntent {
        ROUTINE,
        RADIO,
        MUSIC
    }

    internal sealed class MediaTargetResolution {
        data class Found(val target: MediaPlayerCandidate) : MediaTargetResolution()
        data class Unreachable(val target: MediaPlayerCandidate, val available: List<MediaPlayerCandidate>) : MediaTargetResolution()
        data class NotFound(val requested: String, val available: List<MediaPlayerCandidate>) : MediaTargetResolution()
        data object NoPlayers : MediaTargetResolution()
    }

    suspend fun getWeatherSummary(): String = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${getBaseUrl()}/api/states")
                .addHeader("Authorization", "Bearer $haToken")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext "No se pudo obtener el estado del clima de Home Assistant (${resp.code})."
                val jsonArray = JSONArray(resp.body?.string().orEmpty())
                var weatherObj: JSONObject? = null
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    if (item.getString("entity_id").startsWith("weather.")) {
                        weatherObj = item
                        break
                    }
                }
                if (weatherObj == null) {
                    return@withContext "No se encontró ninguna entidad de clima (weather.*) en Home Assistant."
                }

                val attrs = weatherObj.optJSONObject("attributes") ?: JSONObject()
                val friendlyName = attrs.optString("friendly_name", "Casa")
                val state = weatherObj.optString("state", "desconocido")
                val temp = attrs.optDouble("temperature", Double.NaN)
                val tempUnit = attrs.optString("temperature_unit", "°C")
                val humidity = attrs.optDouble("humidity", Double.NaN)
                val windSpeed = attrs.optDouble("wind_speed", Double.NaN)
                val windSpeedUnit = attrs.optString("wind_speed_unit", "km/h")

                val conditionSpanish = translateWeatherConditionSpanish(state)

                val sb = StringBuilder("Pronóstico de $friendlyName: Actualmente está $conditionSpanish")
                if (!temp.isNaN()) sb.append(" con una temperatura de $temp$tempUnit")
                if (!humidity.isNaN()) sb.append(", humedad del ${humidity.toInt()}%")
                if (!windSpeed.isNaN()) sb.append(" y vientos de $windSpeed $windSpeedUnit")
                sb.append(".")
                sb.toString()
            }
        } catch (e: Exception) {
            "Error consultando el clima: ${e.message}"
        }
    }

    suspend fun mediaPlayerPlayback(
        deviceQuery: String,
        action: String,
        volume: Float? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            val q = deviceQuery.ifBlank { "media_player" }
            val entity = resolveEntity(q)
            val targetEid = when {
                entity != null && entity.domain == "media_player" -> entity.id
                else -> findActiveMediaPlayer() ?: entity?.id
            } ?: return@withContext "No se encontró ningún reproductor multimedia disponible en Home Assistant."

            val act = action.lowercase().trim()
            val (service, data) = when (act) {
                "play", "reproducir", "resume", "reanudar" -> "media_play" to JSONObject().put("entity_id", targetEid)
                "pause", "pausar" -> "media_pause" to JSONObject().put("entity_id", targetEid)
                "play_pause", "toggle" -> "media_play_pause" to JSONObject().put("entity_id", targetEid)
                "next", "siguiente" -> "media_next_track" to JSONObject().put("entity_id", targetEid)
                "previous", "anterior" -> "media_previous_track" to JSONObject().put("entity_id", targetEid)
                "stop", "detener" -> "media_stop" to JSONObject().put("entity_id", targetEid)
                "volume_up", "subir_volumen" -> "volume_up" to JSONObject().put("entity_id", targetEid)
                "volume_down", "bajar_volumen" -> "volume_down" to JSONObject().put("entity_id", targetEid)
                "mute", "silenciar" -> "volume_mute" to JSONObject().put("entity_id", targetEid).put("is_volume_muted", true)
                "unmute", "desilenciar" -> "volume_mute" to JSONObject().put("entity_id", targetEid).put("is_volume_muted", false)
                "volume_set", "fijar_volumen" -> {
                    val lvl = (volume ?: 0.5f).coerceIn(0f, 1f)
                    "volume_set" to JSONObject().put("entity_id", targetEid).put("volume_level", lvl)
                }
                else -> "media_play_pause" to JSONObject().put("entity_id", targetEid)
            }

            callService("media_player", service, data)
        } catch (e: Exception) {
            "Error en control multimedia: ${e.message}"
        }
    }

    private fun findActiveMediaPlayer(): String? {
        return try {
            val req = Request.Builder()
                .url("${getBaseUrl()}/api/states")
                .addHeader("Authorization", "Bearer $haToken")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val array = JSONArray(resp.body?.string().orEmpty())
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val eid = item.getString("entity_id")
                    if (eid.startsWith("media_player.")) {
                        val state = item.optString("state", "")
                        if (state == "playing" || state == "on") {
                            return eid
                        }
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun triggerScene(sceneQuery: String): String = withContext(Dispatchers.IO) {
        try {
            val clean = sceneQuery.trim().lowercase().replace(Regex("\\bescena\\b"), "").trim()
            if (clean.isBlank()) {
                return@withContext "Por favor indica el nombre de la escena a activar."
            }
            val array = fetchStatesArray()
                ?: return@withContext "Error consultando escenas en Home Assistant."
            var targetEid: String? = null
            var friendlyName = ""
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val eid = item.getString("entity_id")
                if (eid.startsWith("scene.")) {
                    val fn = item.optJSONObject("attributes")?.optString("friendly_name", "") ?: ""
                    if (eid.contains(clean, ignoreCase = true) || fn.contains(clean, ignoreCase = true)) {
                        targetEid = eid
                        friendlyName = fn.ifBlank { eid }
                        break
                    }
                }
            }
            if (targetEid == null) {
                return@withContext "No se encontró ninguna escena que coincida con '$sceneQuery'."
            }
            val result = callService("scene", "turn_on", JSONObject().put("entity_id", targetEid))
            if (result.contains("correctamente")) {
                "Escena '$friendlyName' activada con éxito."
            } else {
                result
            }
        } catch (e: Exception) {
            "Error activando escena: ${e.message}"
        }
    }

    suspend fun getCameras(): List<CameraInfo> = withContext(Dispatchers.IO) {
        try {
            // The shared states cache (a few seconds): a sentinel or proactive run that just read
            // every state does not download them again.
            val array = fetchStatesArray() ?: run {
                Log.w(TAG, "getCameras(): could not read the states")
                return@withContext emptyList()
            }
            val list = mutableListOf<CameraInfo>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val eid = item.getString("entity_id")
                if (eid.startsWith("camera.")) {
                    val fn = item.optJSONObject("attributes")?.optString("friendly_name", eid) ?: eid
                    val st = item.optString("state", "idle")
                    val snapUrl = "${getBaseUrl()}/api/camera_proxy/$eid"
                    list.add(CameraInfo(eid, fn, st, snapUrl))
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun getSynonymsForQuery(query: String): List<String> {
        val q = query.lowercase().trim()
        val synonyms = mutableListOf(q)
        if (q.contains("ring") || q.contains("timbre") || q.contains("doorbell") || q.contains("intercom")) {
            synonyms.addAll(listOf("puerta", "puerta_principal", "doorbell", "ring", "timbre", "frente", "entrada"))
        }
        if (q.contains("puerta") || q.contains("entrada") || q.contains("frente") || q.contains("fachada") || q.contains("acceso")) {
            synonyms.addAll(listOf("puerta", "puerta_principal", "entrada", "doorbell", "front", "door"))
        }
        if (q.contains("jardin") || q.contains("jardín") || q.contains("patio") || q.contains("trasero") || q.contains("backyard")) {
            synonyms.addAll(listOf("jardin", "jardín", "patio", "garden", "backyard"))
        }
        if (q.contains("cochera") || q.contains("garaje") || q.contains("garage") || q.contains("auto") || q.contains("carro") || q.contains("estacionamiento")) {
            synonyms.addAll(listOf("cochera", "garage", "garaje", "driveway", "parking"))
        }
        if (q.contains("impresora") || q.contains("3d") || q.contains("octoprint")) {
            synonyms.addAll(listOf("octoprint", "impresora", "printer"))
        }
        return synonyms.distinct()
    }

    internal fun rankCameraCandidates(cams: List<CameraInfo>, cameraQuery: String, preferSnapshot: Boolean = true): List<CameraInfo> {
        if (cams.isEmpty()) return emptyList()
        val clean = cameraQuery.trim().lowercase()
            .replace(Regex("\\b(c[aá]mara|camara|la|el|de|del)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val synonyms = if (clean.isNotBlank()) getSynonymsForQuery(clean) else emptyList()

        // Sin coincidencia devolvemos lista vacía: antes se usaban todas las cámaras y se
        // describía la primera como si fuera la pedida.
        val matching = if (clean.isBlank()) {
            cams
        } else {
            val terms = (synonyms + clean.split(" ").filter { it.length >= 4 })
                .map { normalizeAsciiLower(it).replace(" ", "_") }
                .filter { it.isNotBlank() }
                .distinct()
            cams.filter { cam ->
                val eid = normalizeAsciiLower(cam.entityId)
                val name = normalizeAsciiLower(cam.friendlyName).replace(" ", "_")
                terms.any { term -> eid.contains(term) || name.contains(term) }
            }
        }

        return matching.sortedWith { a, b ->
            val aAvail = if (a.state != "unavailable") 0 else 1
            val bAvail = if (b.state != "unavailable") 0 else 1
            if (aAvail != bAvail) return@sortedWith aAvail.compareTo(bAvail)

            if (preferSnapshot) {
                val aSnap = if (a.entityId.contains("snapshot") || a.friendlyName.contains("snapshot", ignoreCase = true)) 0 else 1
                val bSnap = if (b.entityId.contains("snapshot") || b.friendlyName.contains("snapshot", ignoreCase = true)) 0 else 1
                if (aSnap != bSnap) return@sortedWith aSnap.compareTo(bSnap)

                val aLive = if (a.entityId.contains("vista_en_vivo") || a.entityId.contains("live")) 1 else 0
                val bLive = if (b.entityId.contains("vista_en_vivo") || b.entityId.contains("live")) 1 else 0
                if (aLive != bLive) return@sortedWith aLive.compareTo(bLive)
            }
            0
        }
    }

    suspend fun getCameraSnapshot(cameraQuery: String): Pair<CameraInfo, ByteArray>? = withContext(Dispatchers.IO) {
        try {
            val cams = getCameras()
            if (cams.isEmpty()) return@withContext null
            val candidates = rankCameraCandidates(cams, cameraQuery, preferSnapshot = true)

            for (candidate in candidates) {
                try {
                    val req = Request.Builder()
                        .url(candidate.snapshotUrl)
                        .addHeader("Authorization", "Bearer $haToken")
                        .get()
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val bytes = resp.body?.bytes()
                            if (bytes != null && bytes.isNotEmpty()) {
                                val frameBytes = extractEmbeddedImageBytes(bytes)
                                if (frameBytes != null) {
                                    return@withContext candidate to frameBytes
                                }
                                Log.w(TAG, "Snapshot ${candidate.entityId} devolvió payload no decodificable (${bytes.size}B, CT=${resp.header("Content-Type")})")
                            }
                        } else {
                            Log.w(TAG, "Snapshot falló para ${candidate.entityId} (HTTP ${resp.code}), probando stream/siguiente...")
                        }
                    }
                    // Fallback: intentar extraer el primer frame JPEG desde camera_proxy_stream
                    fetchFirstStreamFrameBytes(candidate.entityId)?.let { streamFrame ->
                        return@withContext candidate to streamFrame
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Excepción al consultar snapshot de ${candidate.entityId}: ${e.message}")
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo snapshot de cámara: ${e.message}")
            null
        }
    }

    /**
     * Explica al modelo por qué falló una consulta de cámara: si ningún nombre coincide,
     * lista las cámaras disponibles para que pregunte en vez de inventar.
     */
    suspend fun describeCameraLookupFailure(cameraQuery: String): String {
        val cams = getCameras()
        if (cams.isEmpty()) return "No cameras found in Home Assistant."
        val names = groupCameraCandidates(cams).joinToString(", ") { it.friendlyName }
        return if (rankCameraCandidates(cams, cameraQuery).isEmpty()) {
            "No camera matches '$cameraQuery'. Do not describe any other camera; tell the user and ask which one they mean. Available cameras: $names."
        } else {
            "Camera '$cameraQuery' exists but did not return an image (it may be offline). Available cameras: $names."
        }
    }

    private fun extractEmbeddedImageBytes(bytes: ByteArray): ByteArray? {
        if (bytes.size < 16) return null
        // Direct JPEG, PNG, GIF, or RIFF/WebP header
        val isJpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        val isPng = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte()
        val isWebpOrGif = (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte()) ||
            (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte())
        if (isJpeg || isPng || isWebpOrGif) return bytes

        // Scan for embedded JPEG SOI (FF D8 FF) inside multipart response
        for (i in 0 until bytes.size - 2) {
            if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD8.toByte() && bytes[i + 2] == 0xFF.toByte()) {
                var end = bytes.size
                for (j in i + 3 until bytes.size - 1) {
                    if (bytes[j] == 0xFF.toByte() && bytes[j + 1] == 0xD9.toByte()) {
                        end = j + 2
                        break
                    }
                }
                if (end - i > 64) return bytes.copyOfRange(i, end)
            }
        }
        return null
    }

    private fun fetchFirstStreamFrameBytes(entityId: String): ByteArray? {
        return try {
            val req = Request.Builder()
                .url("${getBaseUrl()}/api/camera_proxy_stream/$entityId")
                .addHeader("Authorization", "Bearer $haToken")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val input = resp.body?.byteStream() ?: return null
                val buf = ByteArray(16 * 1024)
                val out = java.io.ByteArrayOutputStream(128 * 1024)
                while (out.size() < 1500 * 1024) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    val arr = out.toByteArray()
                    val frame = extractEmbeddedImageBytes(arr)
                    if (frame != null && frame.size >= 4 &&
                        frame[frame.size - 2] == 0xFF.toByte() &&
                        frame[frame.size - 1] == 0xD9.toByte()
                    ) {
                        return frame
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** LLM Vision provider (config entry) of this Home Assistant, looked up once in a while. */
    private class VisionProviderLookup(val providerId: String?, val atMs: Long, val key: String)

    @Volatile
    private var visionProviderCache: VisionProviderLookup? = null

    /** One client for every vision call (they can take tens of seconds). */
    private val visionClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(50, TimeUnit.SECONDS)
            .build()
    }

    /**
     * The llmvision provider entry to use, cached per URL (30 min when found, 5 min when this
     * Home Assistant has none) instead of reading every config entry on each vision call.
     */
    private fun llmVisionProviderId(): String? {
        val key = getBaseUrl()
        val now = System.currentTimeMillis()
        visionProviderCache?.let { cached ->
            val ttl = if (cached.providerId != null) VISION_PROVIDER_TTL_MS else VISION_PROVIDER_MISSING_TTL_MS
            if (cached.key == key && now - cached.atMs < ttl) return cached.providerId
        }
        var providerId: String? = null
        var fallbackLlmVisionEntryId: String? = null
        try {
            val reqEntries = Request.Builder()
                .url("$key/api/config/config_entries/entry")
                .addHeader("Authorization", "Bearer $haToken")
                .get()
                .build()
            client.newCall(reqEntries).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "config_entries for llmvision answered HTTP ${resp.code}")
                    return null
                }
                val arr = JSONArray(resp.body?.string().orEmpty())
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    if (obj.optString("domain") == "llmvision") {
                        val title = obj.optString("title", "")
                        val entryId = obj.optString("entry_id", "")
                        if (entryId.isBlank()) continue
                        val looksLikeSettings = title.contains("setting", ignoreCase = true) ||
                            title.contains("config", ignoreCase = true)
                        if (title.contains("Provider", ignoreCase = true) ||
                            title.contains("OpenAI", ignoreCase = true) ||
                            title.contains("Gemini", ignoreCase = true) ||
                            title.contains("Anthropic", ignoreCase = true) ||
                            title.contains("Claude", ignoreCase = true) ||
                            title.contains("Ollama", ignoreCase = true) ||
                            title.contains("Groq", ignoreCase = true) ||
                            !looksLikeSettings
                        ) {
                            providerId = entryId
                            break
                        } else if (fallbackLlmVisionEntryId == null) {
                            fallbackLlmVisionEntryId = entryId
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Not cached: the next call tries again.
            Log.w(TAG, "No se pudo consultar config_entries para llmvision: ${e.message}")
            return null
        }
        val chosen = providerId ?: fallbackLlmVisionEntryId
        visionProviderCache = VisionProviderLookup(chosen, now, key)
        return chosen
    }

    suspend fun analyzeCameraWithLlmVision(cameraEntityId: String, question: String): String? = withContext(Dispatchers.IO) {
        try {
            // 1. Proveedor llmvision de esta instancia de Home Assistant (en caché)
            val providerId = llmVisionProviderId()

            // Preparar entidades candidatas: priorizar la entidad de snapshot si es cámara de batería o live view
            val entitiesToTry = mutableListOf<String>()
            snapshotSiblingOf(cameraEntityId)?.let { entitiesToTry.add(it) }
            entitiesToTry.add(cameraEntityId)

            for (eid in entitiesToTry) {
                try {
                    val prompt = if (question.isBlank()) "Describe lo que ves en la cámara con detalle y concisión en español." else question
                    val payload = JSONObject().apply {
                        if (!providerId.isNullOrBlank()) {
                            put("provider", providerId)
                        }
                        put("image_entity", JSONArray().apply { put(eid) })
                        put("message", prompt)
                        put("include_filename", false)
                    }

                    val req = Request.Builder()
                        .url("${getBaseUrl()}/api/services/llmvision/image_analyzer?return_response")
                        .addHeader("Authorization", "Bearer $haToken")
                        .post(payload.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                    visionClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val bodyStr = resp.body?.string().orEmpty()
                            val json = JSONObject(bodyStr)
                            val serviceResponse = json.optJSONObject("service_response")
                            val responseText = serviceResponse?.optString("response_text")
                            if (!responseText.isNullOrBlank()) {
                                return@withContext responseText.trim()
                            }
                            TrekoLogBuffer.w(TAG, "llmvision answered HTTP ${resp.code} for $eid but response_text was empty.")
                        } else {
                            Log.w(TAG, "llmvision falló para $eid (HTTP ${resp.code})")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error llamando llmvision para $eid: ${e.message}")
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error general en analyzeCameraWithLlmVision: ${e.message}")
            null
        }
    }

    /**
     * Devuelve la lista de cámaras físicas deduplicadas agrupando entidades `_snapshot`
     * con su contraparte en vivo (`_vista_en_vivo`, `_live`, `_stream`).
     */
    suspend fun getDeduplicatedCameras(): List<HaCameraGroup> {
        val cams = getCameras()
        return groupCameraCandidates(cams)
    }

    internal fun groupCameraCandidates(cams: List<CameraInfo>): List<HaCameraGroup> {
        if (cams.isEmpty()) return emptyList()

        data class ParsedCamera(
            val cam: CameraInfo,
            val key: String,
            val cleanName: String,
            val isSnapshot: Boolean,
            val isLive: Boolean
        )

        val groups = linkedMapOf<String, MutableList<ParsedCamera>>()
        for (cam in cams) {
            val eidNoDomain = cam.entityId.substringAfter(".").trim()
            val eidStripped = stripCameraEntitySuffix(eidNoDomain)
            val hadEidSuffix = !eidStripped.equals(eidNoDomain, ignoreCase = true)

            val fnStripped = stripCameraFriendlySuffix(cam.friendlyName)
            val hadFnSuffix = fnStripped != cam.friendlyName.trim()

            val groupKey = when {
                hadEidSuffix && eidStripped.isNotBlank() -> eidStripped.lowercase()
                hadFnSuffix && fnStripped.isNotBlank() -> slugifyText(fnStripped)
                else -> eidNoDomain.lowercase()
            }

            val eidLower = eidNoDomain.lowercase()
            val fnLower = normalizeAsciiLower(cam.friendlyName)
            val isSnapshot = eidLower.endsWith("_snapshot") || Regex("""\bsnapshot\b""").containsMatchIn(fnLower)
            val isLive = eidLower.endsWith("_vista_en_vivo") ||
                eidLower.endsWith("_en_vivo") ||
                eidLower.endsWith("_live") ||
                eidLower.endsWith("_stream") ||
                Regex("""\b(vista\s+en\s+vivo|en\s+vivo|live\s+view|live|stream)\b""").containsMatchIn(fnLower)

            val cleanName = fnStripped.ifBlank {
                groupKey.replace("_", " ").replaceFirstChar { it.uppercase() }
            }

            groups.getOrPut(groupKey) { mutableListOf() }
                .add(ParsedCamera(cam, groupKey, cleanName, isSnapshot, isLive))
        }

        return groups.entries.map { (key, entries) ->
            val available = entries.filter { it.cam.state.lowercase() !in listOf("unavailable", "unknown") }

            val snapshotEntry = available.firstOrNull { it.isSnapshot }
                ?: available.firstOrNull()
                ?: entries.firstOrNull { it.isSnapshot }
                ?: entries.first()

            val liveEntry = available.firstOrNull { it.isLive }
                ?: entries.firstOrNull { it.isLive }
                ?: if (entries.size == 1 && !entries.first().isSnapshot) {
                    entries.first()
                } else {
                    available.firstOrNull { it.cam.entityId != snapshotEntry.cam.entityId }
                        ?: entries.firstOrNull { it.cam.entityId != snapshotEntry.cam.entityId }
                }

            val displayName = entries
                .map { it.cleanName }
                .firstOrNull { it.isNotBlank() && !it.startsWith("camera.", ignoreCase = true) }
                ?: snapshotEntry.cleanName

            val resolvedState = when {
                snapshotEntry.cam.state.lowercase() !in listOf("unavailable", "unknown") -> snapshotEntry.cam.state
                liveEntry != null && liveEntry.cam.state.lowercase() !in listOf("unavailable", "unknown") -> liveEntry.cam.state
                else -> snapshotEntry.cam.state
            }

            HaCameraGroup(
                key = key,
                friendlyName = displayName,
                primaryEntityId = snapshotEntry.cam.entityId,
                snapshotEntityId = snapshotEntry.cam.entityId,
                liveEntityId = liveEntry?.cam?.entityId,
                state = resolvedState,
                snapshotUrl = snapshotEntry.cam.snapshotUrl
            )
        }
    }

    /**
     * Consulta /api/states una sola vez y devuelve un resumen ejecutivo en español
     * con luces, multimedia/Alexas, clima/sensores, puertas/movimiento y cámaras deduplicadas.
     */
    /**
     * 3D printer status from the OctoPrint integration (progress, state, finish time,
     * temperatures). Real data, unlike reading the progress off the printer's camera.
     */
    suspend fun get3dPrinterStatus(): String = withContext(Dispatchers.IO) {
        try {
            if (!hasValidConfig()) {
                return@withContext "Error: Home Assistant no está configurado con una URL válida (http/https) y token."
            }
            val array = fetchStatesArray(forceRefresh = true)
                ?: return@withContext "No se pudo consultar Home Assistant para ver la impresora."
            val integrationIds = fetchIntegrationEntityIds("octoprint")
            val printerEntities = ArrayList<PrinterStatus.Entity>()
            val cameraIds = ArrayList<String>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val eid = item.optString("entity_id")
                if (eid.startsWith("camera.")) cameraIds += eid
                val belongs = integrationIds?.contains(eid) ?: PrinterStatus.looksLikeOctoPrint(eid)
                if (!belongs) continue
                val attrs = item.optJSONObject("attributes")
                printerEntities += PrinterStatus.Entity(
                    entityId = eid,
                    friendlyName = attrs?.optString("friendly_name").orEmpty().ifBlank { eid },
                    state = item.optString("state", "unknown"),
                    unit = attrs?.optString("unit_of_measurement").orEmpty()
                )
            }
            PrinterStatus.summarize(printerEntities, cameraIds)
        } catch (e: Exception) {
            "Error consultando la impresora 3D: ${e.message}"
        }
    }

    /**
     * Which optional skills (HA integrations in [candidates]) this Home Assistant has.
     * Null when Home Assistant cannot be reached, so callers keep their last known answer.
     */
    suspend fun detectSkills(candidates: List<String> = TrekoSkills.KNOWN_INTEGRATIONS): Set<String>? = withContext(Dispatchers.IO) {
        if (!hasValidConfig()) return@withContext emptySet()
        val states = try {
            fetchStatesLite(forceRefresh = true)
        } catch (e: Exception) {
            null
        } ?: return@withContext null
        val integrations = SkillDetection.buildTemplate(candidates)
            ?.let { renderTemplate(it) }
            ?.let { SkillDetection.parseTemplateOutput(it, candidates) }
            // Template API unavailable: fall back to default entity naming.
            ?: SkillDetection.integrationsFromEntityIds(states.map { it.entityId })
        integrations + SkillDetection.fromStates(states)
    }

    /** All states, reduced to what the watch catalog and skill detection need. */
    private fun fetchStatesLite(forceRefresh: Boolean): List<HaStateLite>? {
        val array = fetchStatesArray(forceRefresh) ?: return null
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            val attrs = item.optJSONObject("attributes")
            HaStateLite(
                entityId = item.optString("entity_id"),
                state = item.optString("state"),
                friendlyName = attrs?.optString("friendly_name").orEmpty(),
                deviceClass = attrs?.optString("device_class").orEmpty()
            )
        }
    }

    /** What can be watched in this home: Frigate cameras/zones and generic motion sensors. */
    suspend fun getWatchCatalog(): List<WatchSensor>? = withContext(Dispatchers.IO) {
        if (!hasValidConfig()) return@withContext null
        val states = try {
            fetchStatesLite(forceRefresh = true)
        } catch (e: Exception) {
            null
        } ?: return@withContext null
        WatchCatalog.build(states, fetchIntegrationDevices(TrekoSkills.FRIGATE))
    }

    /**
     * Entity id → device id for every entity of an integration (entities without a device
     * map to ""). Null when the template API is unavailable.
     */
    private fun fetchIntegrationDevices(integration: String): Map<String, String>? {
        if (!integration.matches(Regex("[a-z0-9_]+"))) return null
        val output = renderTemplate(
            "{% for e in integration_entities('$integration') %}{{ e }}={{ device_id(e) or '' }};{% endfor %}"
        ) ?: return null
        return output.split(';')
            .mapNotNull { pair ->
                val eid = pair.substringBefore('=').trim()
                if (!eid.contains('.')) null else eid to pair.substringAfter('=', "").trim()
            }
            .toMap()
            .takeIf { it.isNotEmpty() }
    }

    /**
     * Current state of some entities ("on"/"off"…); missing ones are left out. [forceRefresh]
     * false accepts the states read a few seconds ago by the same client.
     */
    suspend fun getStates(entityIds: Set<String>, forceRefresh: Boolean = true): Map<String, String> = withContext(Dispatchers.IO) {
        val states = try {
            fetchStatesLite(forceRefresh = forceRefresh)
        } catch (e: Exception) {
            null
        } ?: return@withContext emptyMap()
        states.filter { it.entityId in entityIds }.associate { it.entityId to it.state }
    }

    /** JPEG/PNG bytes of a camera or image entity, through Home Assistant's proxy. */
    suspend fun fetchEntityImage(entityId: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!hasValidConfig()) return@withContext null
        val path = when {
            entityId.startsWith("image.") -> "image_proxy"
            entityId.startsWith("camera.") -> "camera_proxy"
            else -> return@withContext null
        }
        val req = Request.Builder()
            .url("${getBaseUrl()}/api/$path/$entityId")
            .addHeader("Authorization", "Bearer $haToken")
            .get()
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    resp.body?.bytes()?.takeIf { it.isNotEmpty() }
                        ?: null.also { TrekoLogBuffer.w(TAG, "Image fetch for $entityId returned an empty body.") }
                } else {
                    TrekoLogBuffer.w(TAG, "Image fetch for $entityId failed with HTTP ${resp.code}.")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Image fetch for $entityId failed: ${e.message}")
            null
        }
    }

    /** Live event stream bound to this client's Home Assistant. */
    fun openEventStream(
        onSensorOn: (entityId: String, replay: Boolean) -> Unit,
        onStatus: (message: String, ok: Boolean) -> Unit
    ): HaEventStream =
        HaEventStream(getBaseUrl(), haToken, onSensorOn, onStatus)

    /** Renders a Jinja template in Home Assistant; null if the call fails. */
    private fun renderTemplate(template: String): String? {
        val body = JSONObject().put("template", template).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("${getBaseUrl()}/api/template")
            .addHeader("Authorization", "Bearer $haToken")
            .post(body)
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string().orEmpty() else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Template render failed: ${e.message}")
            null
        }
    }

    /**
     * Entity ids that belong to an integration, whatever the user renamed them to.
     * Null when the template API is unavailable, so callers can fall back to name matching.
     */
    private fun fetchIntegrationEntityIds(integration: String): Set<String>? {
        if (!integration.matches(Regex("[a-z0-9_]+"))) return null
        return renderTemplate("{{ integration_entities('$integration') | join(',') }}")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.contains('.') }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
    }


    suspend fun getHomeStatusSummary(): String = withContext(Dispatchers.IO) {
        try {
            if (!hasValidConfig()) {
                return@withContext "Error: Home Assistant no está configurado con una URL válida (http/https) y token."
            }
            val array = fetchStatesArray(forceRefresh = true)
                ?: return@withContext "No se pudo consultar el estado del hogar en Home Assistant."

            val snapshots = ArrayList<HomeEntitySnapshot>(array.length())
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val eid = item.optString("entity_id")
                if (eid.isBlank() || eid.contains("_segment_")) continue
                val state = item.optString("state", "unknown")
                val attrs = item.optJSONObject("attributes")
                val friendlyName = attrs?.optString("friendly_name").orEmpty().ifBlank { eid }
                val deviceClass = attrs?.optString("device_class").orEmpty()
                val unit = attrs?.optString("unit_of_measurement").orEmpty()
                val mediaTitle = attrs?.optString("media_title").orEmpty()
                val mediaArtist = attrs?.optString("media_artist").orEmpty()
                val source = attrs?.optString("source").orEmpty()
                val appName = attrs?.optString("app_name").orEmpty()
                val temp = attrs?.optDouble("temperature", Double.NaN)?.takeIf { !it.isNaN() }
                val currentTemp = attrs?.optDouble("current_temperature", Double.NaN)?.takeIf { !it.isNaN() }
                val tempUnit = attrs?.optString("temperature_unit").orEmpty().ifBlank { "°C" }
                val humidity = attrs?.optDouble("humidity", Double.NaN)?.takeIf { !it.isNaN() }
                val hvacAction = attrs?.optString("hvac_action").orEmpty()
                val isAlexa = if (eid.startsWith("media_player.")) {
                    isAlexaMediaPlayer(eid, friendlyName, deviceClass, emptySet())
                } else {
                    false
                }

                snapshots.add(
                    HomeEntitySnapshot(
                        entityId = eid,
                        friendlyName = friendlyName,
                        state = state,
                        deviceClass = deviceClass,
                        unitOfMeasurement = unit,
                        mediaTitle = mediaTitle,
                        mediaArtist = mediaArtist,
                        source = source,
                        appName = appName,
                        temperature = temp,
                        currentTemperature = currentTemp,
                        temperatureUnit = tempUnit,
                        humidity = humidity,
                        hvacAction = hvacAction,
                        isAlexa = isAlexa
                    )
                )
            }
            formatHomeStatusSummary(snapshots)
        } catch (e: Exception) {
            "Error obteniendo el resumen del hogar: ${e.message}"
        }
    }

    internal fun formatHomeStatusSummary(entities: List<HomeEntitySnapshot>): String {
        if (entities.isEmpty()) {
            return "No se encontraron entidades en Home Assistant."
        }
        val sections = mutableListOf<String>()

        // 1. Luces
        val lights = entities.filter { it.entityId.startsWith("light.") }
        val lightsOn = lights.filter { it.state.equals("on", ignoreCase = true) }
        if (lights.isNotEmpty()) {
            if (lightsOn.isEmpty()) {
                sections.add("Luces: todas apagadas (${lights.size} en total).")
            } else {
                val names = lightsOn.take(8).joinToString(", ") { it.friendlyName }
                val extra = if (lightsOn.size > 8) " y ${lightsOn.size - 8} más" else ""
                sections.add("Luces encendidas (${lightsOn.size}/${lights.size}): $names$extra.")
            }
        }

        // 2. Reproductores multimedia y Alexas
        val players = entities.filter { it.entityId.startsWith("media_player.") }
        if (players.isNotEmpty()) {
            val playing = players.filter { it.state.equals("playing", ignoreCase = true) }
            val availableAlexas = players.filter {
                it.isAlexa && it.state.lowercase() !in listOf("unavailable", "unknown")
            }
            val playingText = if (playing.isNotEmpty()) {
                val items = playing.take(5).joinToString("; ") { p ->
                    val meta = listOfNotNull(
                        p.mediaTitle.takeIf { it.isNotBlank() },
                        p.mediaArtist.takeIf { it.isNotBlank() },
                        p.source.takeIf { it.isNotBlank() } ?: p.appName.takeIf { it.isNotBlank() }
                    ).joinToString(" - ")
                    if (meta.isNotBlank()) "${p.friendlyName} ($meta)" else p.friendlyName
                }
                "Reproduciendo ahora: $items"
            } else {
                "Ningún reproductor está sonando ahora"
            }
            val alexaText = if (availableAlexas.isNotEmpty()) {
                "Alexas disponibles (${availableAlexas.size}): ${availableAlexas.take(6).joinToString(", ") { it.friendlyName }}"
            } else {
                "sin bocinas Alexa disponibles"
            }
            sections.add("Multimedia: $playingText; $alexaText.")
        }

        // 3. Clima, temperatura y humedad
        val weatherParts = mutableListOf<String>()
        val weathers = entities.filter {
            it.entityId.startsWith("weather.") && it.state.lowercase() !in listOf("unavailable", "unknown")
        }
        weathers.firstOrNull()?.let { w ->
            val cond = translateWeatherConditionSpanish(w.state)
            val tempStr = w.temperature?.let { " ${it}${w.temperatureUnit}" }.orEmpty()
            val humStr = w.humidity?.let { ", ${it.toInt()}% hum." }.orEmpty()
            weatherParts.add("Exterior (${w.friendlyName}): $cond$tempStr$humStr")
        }

        val climates = entities.filter {
            it.entityId.startsWith("climate.") && it.state.lowercase() !in listOf("unavailable", "unknown")
        }
        for (c in climates.take(3)) {
            val cur = c.currentTemperature?.let { "actual ${it}${c.temperatureUnit}" }
            val tgt = c.temperature?.let { "objetivo ${it}${c.temperatureUnit}" }
            val details = listOfNotNull(c.state, cur, tgt).joinToString(", ")
            weatherParts.add("Clima ${c.friendlyName}: $details")
        }

        val tempSensors = entities.filter {
            it.entityId.startsWith("sensor.") &&
                it.state.lowercase() !in listOf("unavailable", "unknown") &&
                (it.deviceClass.equals("temperature", ignoreCase = true) ||
                    it.unitOfMeasurement in listOf("°C", "°F", "℃", "℉")) &&
                it.state.toDoubleOrNull() != null
        }
        if (tempSensors.isNotEmpty()) {
            val formatted = tempSensors.take(4).joinToString(", ") {
                val unit = it.unitOfMeasurement.ifBlank { "°C" }
                "${it.friendlyName}: ${it.state}$unit"
            }
            weatherParts.add("Temperaturas: $formatted")
        }

        val humSensors = entities.filter {
            it.entityId.startsWith("sensor.") &&
                it.state.lowercase() !in listOf("unavailable", "unknown") &&
                (it.deviceClass.equals("humidity", ignoreCase = true) ||
                    (it.unitOfMeasurement == "%" && (it.friendlyName.contains("hum", ignoreCase = true) || it.entityId.contains("hum", ignoreCase = true)))) &&
                it.state.toDoubleOrNull() != null
        }
        if (humSensors.isNotEmpty()) {
            val formatted = humSensors.take(4).joinToString(", ") { "${it.friendlyName}: ${it.state}%" }
            weatherParts.add("Humedad: $formatted")
        }
        if (weatherParts.isNotEmpty()) {
            sections.add("Ambiente: ${weatherParts.joinToString(" | ")}.")
        }

        // 4. Puertas, ventanas, cerraduras y movimiento
        val securityClasses = setOf("door", "window", "garage_door", "opening", "lock", "motion", "occupancy", "presence")
        val securityBinary = entities.filter {
            it.entityId.startsWith("binary_sensor.") && it.deviceClass.lowercase() in securityClasses
        }
        val activeBinary = securityBinary.filter { it.state.equals("on", ignoreCase = true) }
        val locks = entities.filter { it.entityId.startsWith("lock.") }
        val unlockedLocks = locks.filter { it.state.lowercase() in listOf("unlocked", "open", "jammed") }
        val covers = entities.filter { it.entityId.startsWith("cover.") }
        val openCovers = covers.filter { it.state.lowercase() in listOf("open", "opening") }

        val activeAlerts = mutableListOf<String>()
        activeBinary.forEach { sensor ->
            val label = when (sensor.deviceClass.lowercase()) {
                "motion", "occupancy", "presence" -> "movimiento"
                else -> "abierto"
            }
            activeAlerts.add("${sensor.friendlyName} ($label)")
        }
        unlockedLocks.forEach { lock ->
            activeAlerts.add("${lock.friendlyName} (${lock.state})")
        }
        openCovers.forEach { cover ->
            activeAlerts.add("${cover.friendlyName} (abierta)")
        }

        if (activeAlerts.isNotEmpty()) {
            val shown = activeAlerts.take(8).joinToString(", ")
            val extra = if (activeAlerts.size > 8) " y ${activeAlerts.size - 8} más" else ""
            sections.add("Sensores y accesos activos (${activeAlerts.size}): $shown$extra.")
        } else if (securityBinary.isNotEmpty() || locks.isNotEmpty() || covers.isNotEmpty()) {
            sections.add("Puertas, ventanas y movimiento: todo cerrado y sin movimiento activo.")
        }

        // 5. Cámaras deduplicadas
        val rawCams = entities
            .filter { it.entityId.startsWith("camera.") }
            .map { CameraInfo(it.entityId, it.friendlyName, it.state, "${getBaseUrl()}/api/camera_proxy/${it.entityId}") }
        val groupedCams = groupCameraCandidates(rawCams)
        if (groupedCams.isNotEmpty()) {
            val availableCount = groupedCams.count { it.state.lowercase() !in listOf("unavailable", "unknown") }
            val camList = groupedCams.take(8).joinToString(", ") { "${it.friendlyName} (${it.state})" }
            val extra = if (groupedCams.size > 8) " y ${groupedCams.size - 8} más" else ""
            sections.add("Cámaras (${groupedCams.size}, $availableCount disponibles): $camList$extra.")
        } else {
            sections.add("Cámaras: sin cámaras configuradas.")
        }

        return sections.joinToString("\n")
    }

    /**
     * Reproduce música, artistas, playlists, rutinas o estaciones de radio en bocinas Alexa/Echo
     * o reproductores multimedia (Cast/DLNA/TV) de Home Assistant.
     */
    suspend fun playMusicOrRadio(
        query: String,
        targetDevice: String = "",
        mediaType: String = "auto",
        provider: String = "auto"
    ): String = withContext(Dispatchers.IO) {
        try {
            if (!hasValidConfig()) {
                return@withContext "Error: Home Assistant no está configurado con una URL válida (http/https) y token."
            }
            val trimmedQuery = query.trim()
            val normType = mediaType.trim().lowercase()
            if (trimmedQuery.isBlank() && normType != "radio") {
                return@withContext if (normType == "routine") {
                    "Por favor indica el nombre de la rutina de Alexa que deseas ejecutar."
                } else {
                    "Por favor indica qué canción, artista, género o estación de radio deseas reproducir."
                }
            }
            val effectiveQuery = trimmedQuery.ifBlank { "una estación de radio" }

            val states = fetchStatesArray()
                ?: return@withContext "Error consultando los reproductores multimedia en Home Assistant."

            val notifyServices = getAvailableServicesForDomain("notify")
            val alexaNotifySuffixes = notifyServices
                .filter { it.startsWith("alexa_media_") }
                .map { it.removePrefix("alexa_media_").lowercase() }
                .toSet()

            val candidates = mutableListOf<MediaPlayerCandidate>()
            for (i in 0 until states.length()) {
                val item = states.getJSONObject(i)
                val eid = item.optString("entity_id")
                if (!eid.startsWith("media_player.") || eid.contains("_segment_")) continue
                val attrs = item.optJSONObject("attributes")
                val friendlyName = attrs?.optString("friendly_name").orEmpty().ifBlank { eid }
                val deviceClass = attrs?.optString("device_class").orEmpty()
                val state = item.optString("state", "unknown")
                val isAlexa = isAlexaMediaPlayer(eid, friendlyName, deviceClass, alexaNotifySuffixes)
                candidates.add(MediaPlayerCandidate(eid, friendlyName, state, deviceClass, isAlexa))
            }

            when (val resolution = resolveMediaPlayerTarget(candidates, targetDevice)) {
                MediaTargetResolution.NoPlayers -> {
                    "No se encontraron reproductores multimedia (media_player) en Home Assistant."
                }
                is MediaTargetResolution.NotFound -> {
                    val availText = formatAvailablePlayers(resolution.available)
                    "No encontré un reproductor que coincida con '${resolution.requested}'. Reproductores disponibles: $availText."
                }
                is MediaTargetResolution.Unreachable -> {
                    val others = resolution.available.filter { it.entityId != resolution.target.entityId }
                    val othersNote = if (others.isNotEmpty()) {
                        " Otros disponibles: ${formatAvailablePlayers(others)}."
                    } else {
                        ""
                    }
                    "El reproductor '${resolution.target.friendlyName}' (${resolution.target.entityId}) no está disponible en este momento (estado: ${resolution.target.state}).$othersNote"
                }
                is MediaTargetResolution.Found -> {
                    executePlayOnTarget(resolution.target, effectiveQuery, normType, provider)
                }
            }
        } catch (e: Exception) {
            "Error al reproducir contenido en Home Assistant: ${e.message}"
        }
    }

    private suspend fun executePlayOnTarget(
        target: MediaPlayerCandidate,
        query: String,
        mediaType: String,
        provider: String
    ): String {
        val intent = detectMediaIntent(query, mediaType, provider)

        if (!target.isAlexa) {
            if (intent == MediaIntent.ROUTINE) {
                return "Las rutinas solo están disponibles en dispositivos Alexa/Echo; '${target.friendlyName}' no es un dispositivo Alexa."
            }
            val directStream = query.startsWith("http://", ignoreCase = true) || query.startsWith("https://", ignoreCase = true)
            val resolvedStation = if (directStream) null else radioBrowserClient.findBestStation(query)
            val streamUrl = if (directStream) query.trim() else resolvedStation?.streamUrl.orEmpty()
            if (streamUrl.isBlank()) {
                return "No encontré un flujo de audio reproducible para '$query' en '${target.friendlyName}'."
            }
            val payload = JSONObject().apply {
                put("entity_id", target.entityId)
                put("media_content_id", streamUrl)
                put("media_content_type", "music")
            }
            val result = callService("media_player", "play_media", payload)
            return if (result.contains("correctamente")) {
                val label = resolvedStation?.name ?: query
                "Reproduciendo '$label' en '${target.friendlyName}'."
            } else {
                result
            }
        }

        val needsUpfrontStation = intent == MediaIntent.RADIO &&
            (radioBrowserClient.isGenericQuery(query) || isRadioGenreQuery(query))
        val resolvedStation = if (needsUpfrontStation) {
            radioBrowserClient.findBestStation(query)
        } else {
            null
        }
        val drafts = buildAlexaMediaDrafts(query, intent, provider, resolvedStation)

        var lastError = ""
        for (draft in drafts) {
            val payload = JSONObject().apply {
                put("entity_id", target.entityId)
                put("media_content_id", draft.mediaContentId)
                put("media_content_type", draft.mediaContentType)
            }
            val result = callService("media_player", "play_media", payload)
            if (result.contains("correctamente")) {
                return when (intent) {
                    MediaIntent.ROUTINE -> "Rutina '$query' ejecutada en '${target.friendlyName}'."
                    MediaIntent.RADIO -> {
                        val label = when {
                            draft.mediaContentType == "TUNEIN" -> draft.mediaContentId
                            resolvedStation != null -> resolvedStation.name
                            else -> cleanMediaCommandQuery(query).ifBlank { query }
                        }
                        "Sintonizando '$label' en '${target.friendlyName}'."
                    }
                    MediaIntent.MUSIC -> {
                        val label = cleanMediaCommandQuery(query).ifBlank { query }
                        "Reproduciendo '$label' en '${target.friendlyName}'."
                    }
                }
            }
            lastError = result
        }

        // Fallback con flujo de RadioBrowser cuando TuneIn/custom/Amazon Music fallaron
        if (intent == MediaIntent.MUSIC || (intent == MediaIntent.RADIO && !needsUpfrontStation)) {
            val fallbackStation = radioBrowserClient.findBestStation(query)
            if (fallbackStation != null) {
                val payload = JSONObject().apply {
                    put("entity_id", target.entityId)
                    put("media_content_id", fallbackStation.streamUrl)
                    put("media_content_type", "music")
                }
                val result = callService("media_player", "play_media", payload)
                if (result.contains("correctamente")) {
                    return "Reproduciendo '${fallbackStation.name}' en '${target.friendlyName}'."
                }
            }
        }

        return if (lastError.isNotBlank()) lastError else "No se pudo iniciar la reproducción en '${target.friendlyName}'."
    }

    internal fun isAlexaMediaPlayer(
        entityId: String,
        friendlyName: String,
        deviceClass: String,
        alexaNotifySuffixes: Set<String>
    ): Boolean {
        val eidSuffix = entityId.substringAfter("media_player.").lowercase()
        val fnNorm = normalizeAsciiLower(friendlyName)
        val fnSlug = slugifyText(friendlyName)
        if (alexaNotifySuffixes.any { it.isNotBlank() && (eidSuffix == it || fnSlug == it) }) {
            return true
        }

        val eidTokens = eidSuffix.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()
        val fnTokens = fnNorm.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()
        val allTokens = eidTokens + fnTokens

        val nonAlexaSingleWords = setOf(
            "tv", "tele", "bravia", "samsung", "lg", "roku", "chromecast",
            "dlna", "kodi", "xbox", "playstation"
        )
        val nonAlexaPhrases = listOf(
            "google home", "google nest", "nest mini", "android tv", "fire tv", "apple tv"
        )
        val eidSpaces = eidSuffix.replace("_", " ")
        if (deviceClass.equals("tv", ignoreCase = true) ||
            allTokens.any { it in nonAlexaSingleWords } ||
            nonAlexaPhrases.any { phrase -> eidSpaces.contains(phrase) || fnNorm.contains(phrase) }
        ) {
            return false
        }

        val alexaSingleWords = setOf("echo", "alexa", "dot", "show", "pop", "spot", "cuarto", "sala")
        val alexaPhrases = listOf("todas partes", "en todas partes", "todas_partes", "en_todas_partes")
        return allTokens.any { it in alexaSingleWords } ||
            alexaPhrases.any { phrase -> eidSuffix.contains(phrase) || fnNorm.contains(phrase) }
    }

    internal fun resolveMediaPlayerTarget(
        candidates: List<MediaPlayerCandidate>,
        targetDevice: String
    ): MediaTargetResolution {
        if (candidates.isEmpty()) return MediaTargetResolution.NoPlayers

        val available = candidates.filter { it.state.lowercase() !in listOf("unavailable", "unknown") }
        val cleanTarget = normalizeAsciiLower(targetDevice)
            .replace(Regex("^(en|el|la|los|las|de|del|mi|a)\\s+"), "")
            .replace("_", " ")
            .trim()

        if (cleanTarget.isBlank()) {
            val preferred = available.firstOrNull {
                it.isAlexa && !it.entityId.contains("todas_partes", ignoreCase = true) && !it.entityId.contains("everywhere", ignoreCase = true)
            } ?: available.firstOrNull { it.isAlexa }
                ?: available.firstOrNull { !it.deviceClass.equals("tv", ignoreCase = true) }
                ?: available.firstOrNull()

            return if (preferred != null) {
                MediaTargetResolution.Found(preferred)
            } else {
                MediaTargetResolution.Unreachable(candidates.first(), emptyList())
            }
        }

        val isAllGroup = cleanTarget in setOf(
            "todas", "todos", "all", "everywhere", "toda la casa", "en todas partes", "todas partes", "casa"
        )
        if (isAllGroup) {
            val groupMatch = candidates.firstOrNull {
                val eid = it.entityId.lowercase()
                val fn = normalizeAsciiLower(it.friendlyName)
                eid.contains("todas_partes") || eid.contains("everywhere") ||
                    eid.contains("all_speakers") || fn.contains("todas partes") || fn.contains("everywhere")
            }
            if (groupMatch != null) {
                return if (groupMatch.state.lowercase() !in listOf("unavailable", "unknown")) {
                    MediaTargetResolution.Found(groupMatch)
                } else {
                    MediaTargetResolution.Unreachable(groupMatch, available)
                }
            }
            val fallbackAlexa = available.firstOrNull { it.isAlexa } ?: available.firstOrNull()
            return if (fallbackAlexa != null) {
                MediaTargetResolution.Found(fallbackAlexa)
            } else {
                MediaTargetResolution.NotFound(targetDevice, available)
            }
        }

        val roomSynonyms = mapOf(
            "cuarto" to listOf("recamara", "dormitorio", "habitacion", "pieza", "bedroom", "room"),
            "recamara" to listOf("cuarto", "dormitorio", "habitacion", "bedroom"),
            "dormitorio" to listOf("cuarto", "recamara", "habitacion", "bedroom"),
            "habitacion" to listOf("cuarto", "recamara", "dormitorio", "bedroom"),
            "bedroom" to listOf("cuarto", "recamara", "dormitorio", "habitacion"),
            "sala" to listOf("living", "estancia", "salon"),
            "living" to listOf("sala", "estancia", "salon"),
            "cocina" to listOf("kitchen"),
            "kitchen" to listOf("cocina"),
            "oficina" to listOf("estudio", "despacho", "office"),
            "office" to listOf("oficina", "estudio"),
            "bano" to listOf("bathroom"),
            "patio" to listOf("jardin", "terraza", "backyard"),
            "tv" to listOf("tele", "television", "televisor", "pantalla"),
            "tele" to listOf("tv", "television", "televisor", "pantalla"),
            "echo" to listOf("alexa", "dot", "show", "pop", "bocina"),
            "alexa" to listOf("echo", "dot", "show", "pop", "bocina"),
            "bocina" to listOf("echo", "alexa", "altavoz", "speaker")
        )

        val words = cleanTarget.split(Regex("\\s+")).filter { it.length >= 2 }
        val nonGenericWords = words.filterNot { it in setOf("alexa", "echo", "bocina", "altavoz", "reproductor") }
        val effectiveWords = nonGenericWords.ifEmpty { words }
        val targetMentionsTv = words.any { it in setOf("tv", "tele", "television", "televisor", "pantalla") }

        var bestCandidate: MediaPlayerCandidate? = null
        var bestScore = 0

        for (cand in candidates) {
            val eidClean = normalizeAsciiLower(cand.entityId.substringAfter(".").replace("_", " "))
            val fnClean = normalizeAsciiLower(cand.friendlyName.replace("_", " "))

            if (cand.entityId.equals(targetDevice.trim(), ignoreCase = true) ||
                eidClean == cleanTarget ||
                fnClean == cleanTarget
            ) {
                return if (cand.state.lowercase() !in listOf("unavailable", "unknown")) {
                    MediaTargetResolution.Found(cand)
                } else {
                    MediaTargetResolution.Unreachable(cand, available)
                }
            }

            var matchScore = 0
            if (eidClean.contains(cleanTarget) || fnClean.contains(cleanTarget)) {
                matchScore += 60
            }
            for (w in effectiveWords) {
                if (eidClean.contains(w) || fnClean.contains(w)) {
                    matchScore += 45
                }
                val syns = roomSynonyms[w].orEmpty()
                for (syn in syns) {
                    if (eidClean.contains(syn) || fnClean.contains(syn)) {
                        matchScore += 35
                    }
                }
            }

            if (matchScore == 0) continue

            var totalScore = matchScore
            if (cand.state.lowercase() !in listOf("unavailable", "unknown")) {
                totalScore += 100
            }
            if (!targetMentionsTv && cand.isAlexa) {
                totalScore += 25
            } else if (!targetMentionsTv && cand.deviceClass.equals("tv", ignoreCase = true)) {
                totalScore -= 15
            }

            if (totalScore > bestScore) {
                bestScore = totalScore
                bestCandidate = cand
            }
        }

        if (bestCandidate == null) {
            return MediaTargetResolution.NotFound(targetDevice, available)
        }
        return if (bestCandidate.state.lowercase() !in listOf("unavailable", "unknown")) {
            MediaTargetResolution.Found(bestCandidate)
        } else {
            MediaTargetResolution.Unreachable(bestCandidate, available)
        }
    }

    internal fun detectMediaIntent(query: String, mediaType: String, provider: String): MediaIntent {
        val normType = mediaType.trim().lowercase()
        val normProvider = provider.trim().lowercase()
        if (normType == "routine" || normType == "rutina") return MediaIntent.ROUTINE
        if (normType == "radio" || normProvider == "tunein") return MediaIntent.RADIO
        if (normType in setOf("music", "artist", "song", "playlist", "genre")) return MediaIntent.MUSIC

        val normQuery = normalizeAsciiLower(query)
        val isRadioKeyword = Regex("""\b(radio|estacion|emisora|fm|am|tunein)\b""").containsMatchIn(normQuery) ||
            Regex("""\b\d{2,3}\.\d\b""").containsMatchIn(normQuery)
        return if (isRadioKeyword) MediaIntent.RADIO else MediaIntent.MUSIC
    }

    internal fun buildAlexaMediaDrafts(
        query: String,
        intent: MediaIntent,
        provider: String,
        resolvedStation: RadioStation?
    ): List<PlayMediaCommandDraft> {
        val clean = cleanMediaCommandQuery(query).ifBlank { query.trim() }
        val normProvider = provider.trim().lowercase().replace(" ", "_")

        if (intent == MediaIntent.ROUTINE) {
            return listOf(PlayMediaCommandDraft(clean, "routine"))
        }

        if (intent == MediaIntent.RADIO) {
            val isGenericOrGenre = radioBrowserClient.isGenericQuery(query) || isRadioGenreQuery(query)
            val stationCleaned = radioBrowserClient.cleanRadioQuery(query).ifBlank { clean }
            val drafts = mutableListOf<PlayMediaCommandDraft>()
            if (!isGenericOrGenre && stationCleaned.isNotBlank()) {
                drafts.add(PlayMediaCommandDraft(stationCleaned, "TUNEIN"))
                drafts.add(PlayMediaCommandDraft("pon $stationCleaned en TuneIn", "custom"))
            } else {
                val stationName = resolvedStation?.name ?: stationCleaned.ifBlank { "Exa FM 104.9" }
                drafts.add(PlayMediaCommandDraft("pon $stationName en TuneIn", "custom"))
                drafts.add(PlayMediaCommandDraft(stationName, "TUNEIN"))
            }
            if (resolvedStation != null && resolvedStation.streamUrl.isNotBlank()) {
                drafts.add(PlayMediaCommandDraft(resolvedStation.streamUrl, "music"))
            }
            return drafts.distinct()
        }

        return when (normProvider) {
            "spotify" -> listOf(
                PlayMediaCommandDraft(clean, "SPOTIFY"),
                PlayMediaCommandDraft("reproduce $clean en spotify", "custom")
            )
            "amazon_music", "amazon" -> listOf(
                PlayMediaCommandDraft(clean, "AMAZON_MUSIC"),
                PlayMediaCommandDraft("reproduce $clean en amazon music", "custom")
            )
            else -> {
                val providerLabel = when (normProvider) {
                    "", "auto" -> ""
                    "apple_music" -> "Apple Music"
                    "youtube_music" -> "YouTube Music"
                    else -> provider.trim()
                }
                val customCmd = if (providerLabel.isNotBlank()) {
                    "reproduce $clean en $providerLabel"
                } else {
                    "reproduce $clean"
                }
                listOf(
                    PlayMediaCommandDraft(customCmd, "custom"),
                    PlayMediaCommandDraft(clean, "AMAZON_MUSIC")
                )
            }
        }
    }

    private fun formatAvailablePlayers(players: List<MediaPlayerCandidate>): String {
        if (players.isEmpty()) return "ninguno disponible"
        return players.take(8).joinToString(", ") { "${it.friendlyName} (${it.entityId})" }
    }

    private fun cleanMediaCommandQuery(raw: String): String {
        return raw.trim()
            .replace(Regex("""^(?i)(pon|ponme|reproduce|toca|escuchar|play|ejecuta)\s+"""), "")
            .trim()
    }

    private fun isRadioGenreQuery(query: String): Boolean {
        val norm = normalizeAsciiLower(radioBrowserClient.cleanRadioQuery(query))
        if (norm.isBlank()) return true
        val genreWords = setOf(
            "regional", "mexicana", "mexicano", "banda", "mariachi", "nortena", "norteno",
            "rock", "clasico", "classic", "pop", "noticias", "news", "lofi", "lo-fi",
            "jazz", "chill", "ambient", "cumbia", "salsa", "electronica", "baladas", "de"
        )
        val tokens = norm.split(Regex("\\s+")).filter { it.isNotBlank() }
        return tokens.isNotEmpty() && tokens.all { tok -> genreWords.any { tok.startsWith(it) || it == tok } }
    }

    private fun stripCameraEntitySuffix(eidBody: String): String {
        var result = eidBody.trim()
        val suffixes = listOf("_vista_en_vivo", "_snapshot", "_en_vivo", "_stream", "_live")
        for (suffix in suffixes) {
            if (result.endsWith(suffix, ignoreCase = true) && result.length > suffix.length) {
                result = result.dropLast(suffix.length)
                break
            }
        }
        return result.trimEnd('_')
    }

    private fun stripCameraFriendlySuffix(name: String): String {
        var result = name.trim()
        val pattern = Regex("""(?i)\s*(?:[-:|•]\s*)?(?:\(\s*)?(vista\s+en\s+vivo|en\s+vivo|snapshot|live\s+view|live|stream)(?:\s*\))?\s*$""")
        while (true) {
            val next = result.replace(pattern, "").trim()
            if (next == result) break
            result = next
        }
        return result
    }

    private fun slugifyText(input: String): String {
        return normalizeAsciiLower(input)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    private fun normalizeAsciiLower(input: String): String {
        val decomposed = Normalizer.normalize(input.lowercase().trim(), Normalizer.Form.NFD)
        return decomposed.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun translateWeatherConditionSpanish(state: String): String {
        return when (state.lowercase()) {
            "clear-night" -> "cielo despejado nocturno"
            "cloudy" -> "nublado"
            "exceptional" -> "condición excepcional"
            "fog" -> "con niebla"
            "hail" -> "granizo"
            "lightning" -> "tormenta eléctrica"
            "lightning-rainy" -> "tormenta eléctrica con lluvia"
            "partlycloudy" -> "parcialmente nublado"
            "pouring" -> "lluvia intensa"
            "rainy" -> "lluvioso"
            "snowy" -> "nevando"
            "snowy-rainy" -> "aguanieve"
            "sunny" -> "soleado y despejado"
            "windy" -> "ventoso"
            "windy-variant" -> "ventoso variable"
            else -> state
        }
    }
}
