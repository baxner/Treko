package com.treko.assistant.meditation

import com.treko.core.mcp.RadioStation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.util.Locale

/**
 * Finds a calm internet stream for a session (Radio Browser tags, the same in every
 * language). Only a station whose tags or name really say meditation, ambient, sleep or
 * nature counts: a generic pop or news pick is never played under a meditation. The result
 * is kept for the session, so the room and the phone share one lookup.
 */
class MeditationStreams(
    private val searchByTag: suspend (tag: String) -> List<RadioStation>,
    private val timeoutMs: Long = 15_000L
) {
    private val mutex = Mutex()
    private var looked = false
    private var found: RadioStation? = null

    /** A calm stream for [type], or null when none could be found. */
    suspend fun resolve(type: MeditationType): RadioStation? = mutex.withLock {
        if (looked) return found
        looked = true
        found = withTimeoutOrNull(timeoutMs) {
            tagsFor(type).firstNotNullOfOrNull { tag ->
                val stations = try {
                    searchByTag(tag)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
                pick(stations)
            }
        }
        found
    }

    companion object {
        /** Radio Browser tags tried in order. */
        fun tagsFor(type: MeditationType): List<String> = when (type) {
            MeditationType.SLEEP -> listOf("sleep", "nature sounds", "meditation", "ambient")
            else -> listOf("meditation", "ambient", "relaxation", "nature sounds")
        }

        private val CALM_WORDS = listOf(
            "meditat", "meditac", "ambient", "sleep", "relax", "nature", "naturaleza", "rain", "lluvia",
            "zen", "calm", "spa", "yoga", "mindful", "new age", "dormir"
        )

        /** Never under a meditation, even when a calm word also appears. */
        private val NOISY_WORDS = listOf(
            "news", "noticia", "talk", "sport", "deporte", "hits", "top 40", "pop", "rock", "reggaeton",
            "banda", "metal", "dance", "techno", "house", "regional", "comedy"
        )

        private fun normalize(text: String): String =
            Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()

        /** True when [station]'s tags or name say it is calm, and nothing says it is not. */
        fun isCalm(station: RadioStation): Boolean {
            val text = " " + normalize(station.tags + " " + station.name) + " "
            return CALM_WORDS.any { text.contains(it) } && NOISY_WORDS.none { text.contains(" $it") }
        }

        /** The best calm station of [stations]: a direct https stream with a good bitrate first. */
        fun pick(stations: List<RadioStation>): RadioStation? =
            stations.filter { isCalm(it) && it.streamUrl.startsWith("http", ignoreCase = true) }
                .maxByOrNull { s ->
                    val url = s.streamUrl.lowercase(Locale.ROOT)
                    var score = 0
                    if (url.startsWith("https://")) score += 20
                    if (!url.endsWith(".pls") && !url.endsWith(".m3u") && !url.endsWith(".m3u8")) score += 15
                    score + (s.bitrate / 32).coerceIn(0, 10)
                }
    }
}
