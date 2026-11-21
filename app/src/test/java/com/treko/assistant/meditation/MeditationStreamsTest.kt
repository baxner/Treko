package com.treko.assistant.meditation

import com.treko.core.mcp.RadioStation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeditationStreamsTest {

    private val pop = RadioStation("Los 40", "https://example.org/pop.mp3", tags = "pop,hits,top 40")
    private val news = RadioStation("Radio Noticias Relax", "https://example.org/news.mp3", tags = "news,talk")
    private val calm = RadioStation("Calm Waves", "http://example.org/calm.mp3", tags = "ambient,meditation", bitrate = 128)
    private val calmHttps = RadioStation("Zen Garden", "https://example.org/zen.mp3", tags = "meditation", bitrate = 64)

    @Test
    fun onlyACalmStationIsEverPicked() {
        assertNull(MeditationStreams.pick(listOf(pop, news)))
        assertEquals(calmHttps, MeditationStreams.pick(listOf(pop, calm, calmHttps)))
    }

    @Test
    fun aGenericFallbackNeverPlaysAndTheLookupIsSharedBySession() = runBlocking {
        val asked = mutableListOf<String>()
        val streams = MeditationStreams(searchByTag = { tag ->
            asked += tag
            if (tag == "nature sounds") listOf(pop, calm) else listOf(pop)
        })
        assertEquals(calm, streams.resolve(MeditationType.SLEEP))
        assertEquals(listOf("sleep", "nature sounds"), asked)
        // Second caller (the phone after the room) reuses the result.
        assertEquals(calm, streams.resolve(MeditationType.SLEEP))
        assertEquals(2, asked.size)

        val nothing = MeditationStreams(searchByTag = { error("mirrors down") })
        assertNull(nothing.resolve(MeditationType.MINDFULNESS))
    }
}
