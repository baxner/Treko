package com.treko.core.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit

data class RadioStation(
    val name: String,
    val streamUrl: String,
    val country: String = "",
    val tags: String = "",
    val bitrate: Int = 0
)

/**
 * Cliente para buscar estaciones de radio por nombre o género usando Radio Browser API,
 * con catálogo curado offline/fallback para consultas genéricas o fallos de red.
 */
class RadioBrowserClient(
    private val baseUrls: List<String> = DEFAULT_BASE_URLS,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    /** ISO country of the user (the phone's locale); picks the stations for a generic "pon la radio". */
    private val countryCode: String = Locale.getDefault().country
) {
    companion object {
        val DEFAULT_BASE_URLS = listOf(
            "https://de1.api.radio-browser.info/json/stations/search",
            "https://all.api.radio-browser.info/json/stations/search"
        )

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .build()

        private val CURATED_STATIONS: List<RadioStation> = listOf(
            // Regional Mexicana
            RadioStation(
                name = "La Ke Buena 92.9 FM",
                streamUrl = "https://playerservices.streamtheworld.com/api/livestream-redirect/KEBUENA_SC",
                country = "Mexico",
                tags = "regional mexicana,banda,grupera,mexican",
                bitrate = 128
            ),
            RadioStation(
                name = "La Z 107.3 FM",
                streamUrl = "https://playerservices.streamtheworld.com/api/livestream-redirect/XEQR_FMAAC.aac",
                country = "Mexico",
                tags = "regional mexicana,banda,nortena,mariachi",
                bitrate = 96
            ),
            RadioStation(
                name = "El Fonógrafo 690 AM",
                streamUrl = "https://playerservices.streamtheworld.com/api/livestream-redirect/XEN_AMAAC.aac",
                country = "Mexico",
                tags = "regional,ranchera,clasicos,mexicana",
                bitrate = 96
            ),
            // Pop
            RadioStation(
                name = "Exa FM 104.9",
                streamUrl = "https://playerservices.streamtheworld.com/api/livestream-redirect/XHEXA_SC",
                country = "Mexico",
                tags = "pop,hits,top 40,espanol,latino",
                bitrate = 128
            ),
            RadioStation(
                name = "Los 40 México 101.7 FM",
                streamUrl = "https://playerservices.streamtheworld.com/api/livestream-redirect/LOS40_MEXICOAAC.aac",
                country = "Mexico",
                tags = "pop,top 40,latino,hits,urbano",
                bitrate = 96
            ),
            RadioStation(
                name = "Joya 93.7 FM",
                streamUrl = "https://playerservices.streamtheworld.com/api/livestream-redirect/XEJP_FMAAC.aac",
                country = "Mexico",
                tags = "pop,baladas,romantica,espanol",
                bitrate = 96
            ),
            // Rock Clásico
            RadioStation(
                name = "Universal 88.1 FM",
                streamUrl = "https://playerservices.streamtheworld.com/api/livestream-redirect/XHRED_FMAAC.aac",
                country = "Mexico",
                tags = "rock clasico,classic rock,oldies,rock",
                bitrate = 96
            ),
            RadioStation(
                name = "Alfa 91.3 FM",
                streamUrl = "https://playerservices.streamtheworld.com/api/livestream-redirect/XHFAJ_FMAAC.aac",
                country = "Mexico",
                tags = "rock,alternative,pop,electronica",
                bitrate = 96
            ),
            RadioStation(
                name = "Radio Paradise Rock Mix",
                streamUrl = "https://stream.radioparadise.com/rock-128",
                country = "United States",
                tags = "classic rock,rock,alternative,eclectic",
                bitrate = 128
            ),
            // Noticias
            RadioStation(
                name = "W Radio 96.9 FM",
                streamUrl = "https://playerservices.streamtheworld.com/api/livestream-redirect/WRADIOAAC.aac",
                country = "Mexico",
                tags = "noticias,news,talk,deportes",
                bitrate = 96
            ),
            RadioStation(
                name = "Radio Fórmula 103.3 FM",
                streamUrl = "https://mdstrm.com/audio/632c9d891e763408a2451fb9/icecast.audio",
                country = "Mexico",
                tags = "noticias,news,talk,analisis",
                bitrate = 128
            ),
            RadioStation(
                name = "MVS Noticias 102.5 FM",
                streamUrl = "https://playerservices.streamtheworld.com/api/livestream-redirect/XHMVSFM_SC",
                country = "Mexico",
                tags = "noticias,news,informacion",
                bitrate = 128
            ),
            // Lo-Fi / Jazz
            RadioStation(
                name = "SomaFM Groove Salad",
                streamUrl = "https://ice1.somafm.com/groovesalad-128-mp3",
                country = "United States",
                tags = "lofi,chillout,ambient,downtempo",
                bitrate = 128
            ),
            RadioStation(
                name = "Jazz24",
                streamUrl = "https://live.wostreaming.net/direct/ppm-jazz24aac-ibc1",
                country = "United States",
                tags = "jazz,instrumental,smooth jazz",
                bitrate = 128
            ),
            RadioStation(
                name = "Radio Paradise Mellow Mix",
                streamUrl = "https://stream.radioparadise.com/mellow-128",
                country = "United States",
                tags = "lofi,chill,mellow,acoustic,focus",
                bitrate = 128
            )
        )

        /** Generic picks for Mexico (es-MX), where the curated local stations are. */
        private val MEXICO_GENERIC_PICKS: List<String> = listOf(
            "Exa FM 104.9",
            "La Ke Buena 92.9 FM",
            "Universal 88.1 FM",
            "W Radio 96.9 FM",
            "SomaFM Groove Salad"
        )

        /** Anywhere else: international internet stations, not one country's FM. */
        private val INTERNATIONAL_GENERIC_PICKS: List<String> = listOf(
            "SomaFM Groove Salad",
            "Radio Paradise Rock Mix",
            "Jazz24",
            "Radio Paradise Mellow Mix"
        )

        internal fun genericPicksFor(countryCode: String): List<String> =
            if (countryCode.equals("MX", ignoreCase = true)) MEXICO_GENERIC_PICKS else INTERNATIONAL_GENERIC_PICKS

        private val GENERIC_PHRASES = setOf(
            "", "radio", "la radio", "una radio", "estacion", "una estacion",
            "estacion de radio", "una estacion de radio", "emisora", "una emisora",
            "cualquier radio", "cualquier estacion", "cualquier emisora",
            "musica", "algo de musica", "radio en vivo", "fm", "am"
        )

        private val GENRE_TAG_ALIASES: List<Pair<List<String>, String>> = listOf(
            listOf("regional", "banda", "mariachi", "nortena", "norteno", "ranchera", "grupera", "corrido") to "regional mexican",
            listOf("rock clasico", "classic rock", "oldies") to "classic rock",
            listOf("rock", "metal", "indie", "alternativ") to "rock",
            listOf("noticias", "news", "informativ", "periodismo") to "news",
            listOf("lofi", "lo-fi", "lo fi") to "lofi",
            listOf("jazz", "smooth jazz", "blues") to "jazz",
            listOf("chill", "ambient", "relax", "estudio", "concentracion", "focus") to "chillout",
            listOf("pop", "top 40", "hits", "exitos", "baladas", "romantica") to "pop",
            listOf("cumbia", "salsa", "tropical") to "latin",
            listOf("electronica", "electronic", "dance", "house", "techno") to "electronic",
            listOf("clasica", "classical", "orquesta") to "classical"
        )
    }

    private val genericPicks: List<String> = genericPicksFor(countryCode)

    /** Station to ask a smart speaker for when the user only said "pon la radio". */
    fun defaultStationName(): String = genericPicks.first()

    suspend fun searchStations(query: String, limit: Int = 5): List<RadioStation> = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceIn(1, 25)
        val cleaned = cleanRadioQuery(query)
        if (isGenericQuery(query, cleaned)) {
            return@withContext resolveCuratedStations(query, safeLimit)
        }

        val mappedTag = mapQueryToTag(cleaned)
        val searchAttempts = buildList {
            if (mappedTag != null && isPureGenreQuery(cleaned)) {
                add("tag" to mappedTag)
                add("name" to cleaned)
            } else {
                add("name" to cleaned)
                if (mappedTag != null) {
                    add("tag" to mappedTag)
                } else if (!cleaned.contains(" ")) {
                    add("tag" to cleaned)
                }
            }
        }.distinct()

        for ((paramKey, paramValue) in searchAttempts) {
            val online = queryMirrors(paramKey, paramValue, safeLimit)
            if (online.isNotEmpty()) {
                return@withContext online.take(safeLimit)
            }
        }

        resolveCuratedStations(query, safeLimit)
    }

    suspend fun findBestStation(query: String): RadioStation? {
        val stations = searchStations(query, limit = 5)
        if (stations.isEmpty()) return null

        val normQuery = normalizeForMatch(cleanRadioQuery(query))
        return stations.maxByOrNull { station ->
            var score = 0
            val urlLower = station.streamUrl.lowercase()
            if (urlLower.startsWith("https://")) score += 20
            if (!urlLower.endsWith(".pls") && !urlLower.endsWith(".m3u") && !urlLower.endsWith(".m3u8")) {
                score += 15
            }
            score += (station.bitrate / 32).coerceIn(0, 10)
            if (normQuery.isNotBlank()) {
                val stationNameNorm = normalizeForMatch(station.name)
                if (stationNameNorm.contains(normQuery) || normQuery.contains(stationNameNorm)) {
                    score += 40
                }
            }
            score
        }
    }

    internal fun isGenericQuery(rawQuery: String, cleanedQuery: String = cleanRadioQuery(rawQuery)): Boolean {
        val normRaw = normalizeForMatch(rawQuery)
            .replace(Regex("^(pon|ponme|reproduce|toca|escuchar|quiero escuchar|abre|enciende)\\s+"), "")
            .trim()
        val normCleaned = normalizeForMatch(cleanedQuery)
        return normRaw in GENERIC_PHRASES || normCleaned in GENERIC_PHRASES
    }

    internal fun resolveCuratedStations(query: String, limit: Int = 5): List<RadioStation> {
        val safeLimit = limit.coerceIn(1, CURATED_STATIONS.size)
        val norm = normalizeForMatch(cleanRadioQuery(query))
        if (norm.isBlank() || isGenericQuery(query)) {
            val prioritized = genericPicks.mapNotNull { pick ->
                CURATED_STATIONS.firstOrNull { it.name == pick }
            }
            return (prioritized + CURATED_STATIONS).distinctBy { it.name }.take(safeLimit)
        }

        val tokens = norm.split(Regex("\\s+")).filter { it.length >= 2 }
        val mappedTag = mapQueryToTag(norm)
        val scored = CURATED_STATIONS.map { station ->
            val nameNorm = normalizeForMatch(station.name)
            val tagsNorm = normalizeForMatch(station.tags)
            val countryNorm = normalizeForMatch(station.country)
            var score = 0
            if (nameNorm.contains(norm)) score += 100
            if (mappedTag != null && tagsNorm.contains(normalizeForMatch(mappedTag))) score += 50
            for (tok in tokens) {
                if (nameNorm.contains(tok)) score += 40
                if (tagsNorm.contains(tok)) score += 30
                if (countryNorm.contains(tok)) score += 10
            }
            score += matchGenreBonus(norm, tagsNorm)
            station to score
        }.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }

        if (scored.isNotEmpty()) {
            return scored.take(safeLimit)
        }

        val prioritized = genericPicks.mapNotNull { pick ->
            CURATED_STATIONS.firstOrNull { it.name == pick }
        }
        return (prioritized + CURATED_STATIONS).distinctBy { it.name }.take(safeLimit)
    }

    private fun matchGenreBonus(normQuery: String, tagsNorm: String): Int {
        var bonus = 0
        if ((normQuery.contains("regional") || normQuery.contains("banda") || normQuery.contains("mariachi") || normQuery.contains("ranchera")) &&
            tagsNorm.contains("regional")
        ) {
            bonus += 60
        }
        if (normQuery.contains("rock") && tagsNorm.contains("rock")) {
            bonus += 50
            if (normQuery.contains("clasic") && tagsNorm.contains("classic rock")) bonus += 30
        }
        if (normQuery.contains("pop") && tagsNorm.contains("pop")) {
            bonus += 50
        }
        if ((normQuery.contains("notici") || normQuery.contains("news") || normQuery.contains("informativ")) &&
            tagsNorm.contains("noticias")
        ) {
            bonus += 60
        }
        if ((normQuery.contains("lofi") || normQuery.contains("lo-fi") || normQuery.contains("jazz") || normQuery.contains("chill")) &&
            (tagsNorm.contains("lofi") || tagsNorm.contains("jazz") || tagsNorm.contains("chill"))
        ) {
            bonus += 60
        }
        return bonus
    }

    private fun queryMirrors(paramKey: String, paramValue: String, limit: Int): List<RadioStation> {
        val encoded = URLEncoder.encode(paramValue, "UTF-8").replace("+", "%20")
        for (baseUrl in baseUrls) {
            val cleanBase = baseUrl.trim().trimEnd('?', '&')
            if (cleanBase.isBlank()) continue
            val sep = if (cleanBase.contains("?")) "&" else "?"
            val url = "${cleanBase}${sep}${paramKey}=${encoded}&hidebroken=true&order=clickcount&reverse=true&limit=$limit"
            try {
                val req = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "TrekoAssistant/1.0")
                    .addHeader("Accept", "application/json")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string().orEmpty()
                    val parsed = parseStationsJson(body)
                    if (parsed.isNotEmpty()) {
                        return parsed
                    }
                }
            } catch (_: Exception) {
                // Probar siguiente mirror o usar fallback curado
            }
        }
        return emptyList()
    }

    internal fun parseStationsJson(rawJson: String): List<RadioStation> {
        val items = SimpleJsonParser(rawJson).parseArrayOfObjects() ?: return emptyList()
        val stations = mutableListOf<RadioStation>()
        for (obj in items) {
            val lastCheckOk = (obj["lastcheckok"] as? Number)?.toInt() ?: 1
            if (lastCheckOk == 0) continue

            val name = (obj["name"] as? String)?.trim().orEmpty()
            val resolvedUrl = (obj["url_resolved"] as? String)?.trim().orEmpty()
            val rawUrl = (obj["url"] as? String)?.trim().orEmpty()
            val streamUrl = resolvedUrl.ifBlank { rawUrl }

            if (name.isBlank() || !(streamUrl.startsWith("http://", ignoreCase = true) || streamUrl.startsWith("https://", ignoreCase = true))) {
                continue
            }

            val country = ((obj["country"] as? String) ?: (obj["countrycode"] as? String)).orEmpty().trim()
            val tags = (obj["tags"] as? String)?.trim().orEmpty()
            val bitrate = (obj["bitrate"] as? Number)?.toInt() ?: 0
            stations.add(
                RadioStation(
                    name = name,
                    streamUrl = streamUrl,
                    country = country,
                    tags = tags,
                    bitrate = bitrate
                )
            )
        }
        return stations.distinctBy { it.streamUrl }
    }

    internal fun cleanRadioQuery(query: String): String {
        var q = query.trim()
        val prefixes = listOf(
            Regex("""^(?i)(pon|ponme|reproduce|toca|escuchar|quiero\s+escuchar|abre|enciende)\s+"""),
            Regex("""^(?i)(una\s+estaci[oó]n\s+de\s+radio|estaci[oó]n\s+de\s+radio|la\s+estaci[oó]n|una\s+emisora\s+de|emisora\s+de|la\s+radio|radio\s+de|radio)\s+"""),
            Regex("""^(?i)(m[uú]sica\s+de|m[uú]sica)\s+""")
        )
        for (pattern in prefixes) {
            q = q.replace(pattern, "").trim()
        }
        q = q.replace(Regex("""(?i)\s+(en\s+tunein|en\s+la\s+radio|por\s+radio|en\s+alexa)$"""), "").trim()
        return q
    }

    private fun mapQueryToTag(cleanedQuery: String): String? {
        val norm = normalizeForMatch(cleanedQuery)
        for ((keywords, tag) in GENRE_TAG_ALIASES) {
            if (keywords.any { norm.contains(it) }) {
                return tag
            }
        }
        return null
    }

    private fun isPureGenreQuery(cleanedQuery: String): Boolean {
        val norm = normalizeForMatch(cleanedQuery)
        val genreWords = setOf(
            "regional", "mexicana", "mexicano", "banda", "mariachi", "nortena", "norteno",
            "ranchera", "rock", "clasico", "classic", "pop", "noticias", "news", "lofi",
            "lo-fi", "jazz", "chill", "chillout", "ambient", "electronica", "salsa", "cumbia",
            "clasica", "classical", "baladas", "hits", "de"
        )
        val parts = norm.split(Regex("\\s+")).filter { it.isNotBlank() }
        return parts.isNotEmpty() && parts.all { p -> genreWords.any { p.startsWith(it) || it == p } }
    }

    private fun normalizeForMatch(input: String): String {
        val decomposed = Normalizer.normalize(input.lowercase().trim(), Normalizer.Form.NFD)
        return decomposed.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Parser JSON puro en Kotlin para arrays de objetos (sin depender de org.json en JVM tests).
     */
    private class SimpleJsonParser(private val src: String) {
        private var pos = 0

        fun parseArrayOfObjects(): List<Map<String, Any?>>? {
            return try {
                skipWhitespace()
                if (pos >= src.length || src[pos] != '[') return null
                pos++
                val list = mutableListOf<Map<String, Any?>>()
                skipWhitespace()
                if (pos < src.length && src[pos] == ']') {
                    pos++
                    return list
                }
                while (pos < src.length) {
                    skipWhitespace()
                    if (pos < src.length && src[pos] == '{') {
                        val obj = parseObject() ?: return null
                        list.add(obj)
                    } else {
                        skipValue()
                    }
                    skipWhitespace()
                    if (pos < src.length && src[pos] == ',') {
                        pos++
                        continue
                    }
                    if (pos < src.length && src[pos] == ']') {
                        pos++
                        break
                    }
                    return null
                }
                list
            } catch (_: Exception) {
                null
            }
        }

        private fun parseObject(): Map<String, Any?>? {
            if (pos >= src.length || src[pos] != '{') return null
            pos++
            val map = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (pos < src.length && src[pos] == '}') {
                pos++
                return map
            }
            while (pos < src.length) {
                skipWhitespace()
                if (pos >= src.length || src[pos] != '"') return null
                val key = parseString() ?: return null
                skipWhitespace()
                if (pos >= src.length || src[pos] != ':') return null
                pos++
                skipWhitespace()
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                if (pos < src.length && src[pos] == ',') {
                    pos++
                    continue
                }
                if (pos < src.length && src[pos] == '}') {
                    pos++
                    break
                }
                return null
            }
            return map
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= src.length) return null
            return when (src[pos]) {
                '"' -> parseString()
                '{' -> parseObject()
                '[' -> parseGenericArray()
                't' -> if (src.startsWith("true", pos)) { pos += 4; true } else null
                'f' -> if (src.startsWith("false", pos)) { pos += 5; false } else null
                'n' -> if (src.startsWith("null", pos)) { pos += 4; null } else null
                '-', in '0'..'9' -> parseNumber()
                else -> null
            }
        }

        private fun parseGenericArray(): List<Any?> {
            pos++ // '['
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (pos < src.length && src[pos] == ']') {
                pos++
                return list
            }
            while (pos < src.length) {
                list.add(parseValue())
                skipWhitespace()
                if (pos < src.length && src[pos] == ',') {
                    pos++
                    continue
                }
                if (pos < src.length && src[pos] == ']') {
                    pos++
                    break
                }
                break
            }
            return list
        }

        private fun skipValue() {
            parseValue()
        }

        private fun parseString(): String? {
            if (pos >= src.length || src[pos] != '"') return null
            pos++
            val sb = StringBuilder()
            while (pos < src.length) {
                val ch = src[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) return null
                        when (val esc = src[pos++]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) return null
                                val hex = src.substring(pos, pos + 4)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(ch)
                }
            }
            return null
        }

        private fun parseNumber(): Number? {
            val start = pos
            if (src[pos] == '-') pos++
            while (pos < src.length && (src[pos].isDigit() || src[pos] in ".eE+-")) {
                pos++
            }
            val token = src.substring(start, pos)
            return token.toLongOrNull() ?: token.toDoubleOrNull()
        }

        private fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) {
                pos++
            }
        }
    }
}
