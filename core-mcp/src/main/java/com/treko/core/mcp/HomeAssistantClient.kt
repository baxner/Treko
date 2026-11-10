package com.treko.core.mcp

import android.util.Log
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
    companion object {
        private const val TAG = "HomeAssistantClient"
    }

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
     * Incluye autocorrección tolerante a erratas de entity_id generadas por LLMs.
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

            val payload = serviceData ?: JSONObject()

            // Auto-corrección inteligente de entity_id con tolerancia a erratas de LLMs
            val requestedEid = payload.optString("entity_id", "").trim()
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
                    val trimmed = respBody.trim()
                    if (trimmed == "[]" && requestedEid.isNotBlank()) {
                        "Aviso: El servicio '$d.$s' se ejecutó pero Home Assistant reportó 0 cambios en '$correctedEid'. Verifica si el dispositivo ya estaba en ese estado o está desconectado."
                    } else {
                        val correctionNote = if (correctedEid.isNotBlank() && correctedEid != requestedEid) " (corregido a '$correctedEid')" else ""
                        "Servicio '$d.$s' ejecutado correctamente en Home Assistant$correctionNote."
                    }
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
            val req = Request.Builder()
                .url("${getBaseUrl()}/api/states")
                .addHeader("Authorization", "Bearer $haToken")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return rawEid
                val array = JSONArray(resp.body?.string().orEmpty())
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

                // 1. Si existe exactamente, devolver tal cual
                if (allEids.contains(rawEid)) {
                    return rawEid
                }

                // 2. Buscar coincidencia más cercana primero en el dominio especificado, luego en general
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
        colorOrTemp: String = ""
    ): String = withContext(Dispatchers.IO) {
        try {
            val entity = resolveEntity(deviceQuery) 
                ?: return@withContext "Dispositivo '$deviceQuery' no encontrado en Home Assistant. Usa 'home_assistant_list_entities' para ver los dispositivos disponibles."

            val domain = entity.domain
            val a = accion.trim().lowercase()

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
            .replace(Regex("^(el|la|los|las|de|mi|del|un|una|dispositivo|aparato|apaga|prende|enciende)\\s+"), "")
            .replace("_", " ")
            .trim()

        val tvKeywords = listOf("tele", "television", "televisión", "televisor", "pantalla", "tv")
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
            "altavoces" to listOf("bocinas", "speakers", "echo", "alexa")
        )

        val req = Request.Builder()
            .url("${getBaseUrl()}/api/states")
            .addHeader("Authorization", "Bearer $haToken")
            .get()
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val jsonArray = JSONArray(resp.body?.string().orEmpty())
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
}
