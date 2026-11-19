package com.treko.core.mcp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class HomeAssistantClientTest {

    @Test
    fun testSanitizeUrlAndConfigValidation() {
        val client = HomeAssistantClient("http://homeassistant.local:8123/api/", "  tok123  ")
        assertTrue(client.hasValidConfig())
        assertEquals("http://homeassistant.local:8123", client.sanitizeUrl("http://homeassistant.local:8123/api/"))

        val invalid = HomeAssistantClient("homeassistant.local:8123", "tok")
        assertFalse(invalid.hasValidConfig())

        val emptyToken = HomeAssistantClient("https://ha.example.com", "   ")
        assertFalse(emptyToken.hasValidConfig())
    }

    @Test
    fun testCallServiceRejectsInvalidConfigCleanly() = runBlocking {
        val client = HomeAssistantClient("", "")
        val res = client.callService("light", "turn_on")
        assertTrue(res.contains("Error"))
    }

    @Test
    fun testRankCameraCandidatesPreservesWordsContainingLaElDe() {
        val cams = listOf(
            HomeAssistantClient.CameraInfo("camera.cochera", "Cochera", "idle", "http://x/1"),
            HomeAssistantClient.CameraInfo("camera.sala", "Cámara de la Sala", "idle", "http://x/2"),
            HomeAssistantClient.CameraInfo("camera.delantera", "Puerta Delantera", "idle", "http://x/3")
        )

        val client = HomeAssistantClient("", "")
        val salaRanked = client.rankCameraCandidates(cams, "la cámara de la sala")
        assertEquals("camera.sala", salaRanked.first().entityId)

        val frontRanked = client.rankCameraCandidates(cams, "cámara delantera")
        assertEquals("camera.delantera", frontRanked.first().entityId)
    }

    @Test
    fun testRankCameraCandidatesReturnsEmptyWhenNothingMatches() {
        val cams = listOf(
            HomeAssistantClient.CameraInfo("camera.cochera", "Cochera", "idle", "http://x/1"),
            HomeAssistantClient.CameraInfo("camera.sala", "Sala", "idle", "http://x/2")
        )
        val client = HomeAssistantClient("", "")
        assertTrue(client.rankCameraCandidates(cams, "cámara de la cocina").isEmpty())
        assertEquals(2, client.rankCameraCandidates(cams, "").size)
    }

    @Test
    fun testRankCameraCandidatesIgnoresAccentsAndMatchesWords() {
        val cams = listOf(
            HomeAssistantClient.CameraInfo("camera.sotano", "Sótano", "idle", "http://x/1"),
            HomeAssistantClient.CameraInfo("camera.patio_trasero", "Patio Trasero", "idle", "http://x/2"),
            HomeAssistantClient.CameraInfo("camera.cuarto_bebe", "Cuarto Bebé", "idle", "http://x/3")
        )
        val client = HomeAssistantClient("", "")
        assertEquals("camera.sotano", client.rankCameraCandidates(cams, "cámara del sótano").single().entityId)
        assertEquals("camera.cuarto_bebe", client.rankCameraCandidates(cams, "cuarto del bebe").single().entityId)
        assertEquals("camera.patio_trasero", client.rankCameraCandidates(cams, "patio trasero").first().entityId)
    }

    @Test
    fun testCameraSynonymsDoNotContainPersonalAliases() {
        val client = HomeAssistantClient("", "")
        val gardenSynonyms = client.getSynonymsForQuery("jardín")
        assertTrue(gardenSynonyms.contains("jardin"))
        assertTrue(gardenSynonyms.contains("patio"))
        assertFalse(gardenSynonyms.contains("china"))
    }

    @Test
    fun testMcpDispatcherCallsRegisteredTool() = runBlocking {
        val dispatcher = McpDispatcher()
        dispatcher.registerTool(
            McpTool(
                name = "ping",
                description = "Ping test",
                parameters = emptyMap(),
                execute = { args -> "pong:${args["v"]}" }
            )
        )
        assertEquals("pong:42", dispatcher.callTool("ping", mapOf("v" to 42)))
        assertTrue(dispatcher.callTool("missing", emptyMap()).contains("no encontrada"))
    }

    @Test
    fun testGroupCameraCandidatesDeduplicatesSnapshotAndLivePairs() {
        val cams = listOf(
            HomeAssistantClient.CameraInfo(
                entityId = "camera.puerta_principal_snapshot",
                friendlyName = "Puerta principal Snapshot",
                state = "idle",
                snapshotUrl = "http://ha/api/camera_proxy/camera.puerta_principal_snapshot"
            ),
            HomeAssistantClient.CameraInfo(
                entityId = "camera.puerta_principal_vista_en_vivo",
                friendlyName = "Puerta principal Vista en vivo",
                state = "streaming",
                snapshotUrl = "http://ha/api/camera_proxy/camera.puerta_principal_vista_en_vivo"
            ),
            HomeAssistantClient.CameraInfo(
                entityId = "camera.jardin_snapshot",
                friendlyName = "Jardín Snapshot",
                state = "unavailable",
                snapshotUrl = "http://ha/api/camera_proxy/camera.jardin_snapshot"
            ),
            HomeAssistantClient.CameraInfo(
                entityId = "camera.jardin_live",
                friendlyName = "Jardín Live",
                state = "idle",
                snapshotUrl = "http://ha/api/camera_proxy/camera.jardin_live"
            ),
            HomeAssistantClient.CameraInfo(
                entityId = "camera.cochera_snapshot",
                friendlyName = "Cochera Snapshot",
                state = "idle",
                snapshotUrl = "http://ha/api/camera_proxy/camera.cochera_snapshot"
            ),
            HomeAssistantClient.CameraInfo(
                entityId = "camera.octoprint",
                friendlyName = "OctoPrint",
                state = "idle",
                snapshotUrl = "http://ha/api/camera_proxy/camera.octoprint"
            )
        )

        val groups = HomeAssistantClient("", "").groupCameraCandidates(cams)
        assertEquals(4, groups.size)

        val door = groups[0]
        assertEquals("puerta_principal", door.key)
        assertEquals("Puerta principal", door.friendlyName)
        assertEquals("camera.puerta_principal_snapshot", door.primaryEntityId)
        assertEquals("camera.puerta_principal_snapshot", door.snapshotEntityId)
        assertEquals("camera.puerta_principal_vista_en_vivo", door.liveEntityId)
        assertEquals("idle", door.state)

        val garden = groups[1]
        assertEquals("jardin", garden.key)
        assertEquals("Jardín", garden.friendlyName)
        assertEquals("camera.jardin_live", garden.liveEntityId)
        assertEquals("idle", garden.state)

        val garage = groups[2]
        assertEquals("cochera", garage.key)
        assertEquals("Cochera", garage.friendlyName)
        assertEquals("camera.cochera_snapshot", garage.snapshotEntityId)
        assertNull(garage.liveEntityId)

        val printer = groups[3]
        assertEquals("octoprint", printer.key)
        assertEquals("OctoPrint", printer.friendlyName)
        assertEquals("camera.octoprint", printer.snapshotEntityId)
        assertEquals("camera.octoprint", printer.liveEntityId)
    }

    @Test
    fun testRadioBrowserCuratedFallbackAndGenericQueries() = runBlocking {
        val radio = RadioBrowserClient()

        val genericBest = radio.findBestStation("una estación de radio")
        assertNotNull(genericBest)
        assertTrue(genericBest!!.streamUrl.startsWith("https://"))

        val regional = radio.resolveCuratedStations("música regional", limit = 3)
        assertTrue(regional.isNotEmpty())
        assertTrue(regional.first().tags.contains("regional"))

        val classicRock = radio.resolveCuratedStations("rock clásico", limit = 3)
        assertTrue(classicRock.isNotEmpty())
        assertTrue(classicRock.first().tags.contains("rock"))

        val pop = radio.resolveCuratedStations("pop", limit = 3)
        assertTrue(pop.isNotEmpty())
        assertTrue(pop.first().tags.contains("pop"))

        val news = radio.resolveCuratedStations("noticias", limit = 3)
        assertTrue(news.isNotEmpty())
        assertTrue(news.first().tags.contains("noticias"))

        val lofi = radio.resolveCuratedStations("lo-fi jazz", limit = 3)
        assertTrue(lofi.isNotEmpty())
        assertTrue(lofi.first().tags.contains("lofi") || lofi.first().tags.contains("jazz"))
    }

    private fun withLocalHttpServer(
        statusLine: String,
        contentType: String,
        body: String,
        onRequest: (String) -> Unit = {},
        block: suspend (baseUrl: String) -> Unit
    ) = runBlocking {
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 3000
            val t = thread {
                try {
                    while (!server.isClosed) {
                        server.accept().use { sock ->
                            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
                            val reqLine = reader.readLine().orEmpty()
                            if (reqLine.isNotBlank()) {
                                onRequest(reqLine)
                            }
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                            }
                            val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
                            val header = "HTTP/1.1 $statusLine\r\nContent-Type: $contentType\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
                            sock.getOutputStream().write(header.toByteArray(StandardCharsets.US_ASCII))
                            if (bodyBytes.isNotEmpty()) {
                                sock.getOutputStream().write(bodyBytes)
                            }
                            sock.getOutputStream().flush()
                        }
                    }
                } catch (_: Exception) {
                    // Server closed when test finished
                }
            }
            try {
                block("http://127.0.0.1:${server.localPort}/json/stations/search")
            } finally {
                server.close()
                t.join(1000)
            }
        }
    }

    @Test
    fun testRadioBrowserOnlineSearchAndHttpErrorFallback() {
        val capturedRequestLine = AtomicReference("")
        val payload = """
            [
              {
                "name": "Broken Station",
                "url": "http://broken.example/stream",
                "url_resolved": "http://broken.example/stream",
                "country": "Mexico",
                "tags": "rock",
                "bitrate": 128,
                "lastcheckok": 0
              },
              {
                "name": "Reactor 105.7",
                "url": "https://s2.mexside.net/8002/stream",
                "url_resolved": "https://s2.mexside.net/8002/stream",
                "country": "Mexico",
                "tags": "rock,alternative",
                "bitrate": 192,
                "lastcheckok": 1
              }
            ]
        """.trimIndent()

        withLocalHttpServer(
            statusLine = "200 OK",
            contentType = "application/json",
            body = payload,
            onRequest = { line ->
                if (capturedRequestLine.get().isBlank()) {
                    capturedRequestLine.set(line)
                }
            }
        ) { url ->
            val client = RadioBrowserClient(baseUrls = listOf(url))
            val found = client.searchStations("rock", limit = 4)

            assertEquals(1, found.size)
            assertEquals("Reactor 105.7", found.first().name)
            assertEquals("https://s2.mexside.net/8002/stream", found.first().streamUrl)
            assertEquals(192, found.first().bitrate)
            val req = capturedRequestLine.get()
            assertTrue(req.contains("hidebroken=true"))
            assertTrue(req.contains("order=clickcount"))
            assertTrue(req.contains("reverse=true"))
            assertTrue(req.contains("limit=4"))
        }

        // HTTP 500 fallback to curated Regional Mexicana
        withLocalHttpServer(
            statusLine = "500 Internal Server Error",
            contentType = "text/plain",
            body = ""
        ) { url ->
            val client = RadioBrowserClient(baseUrls = listOf(url))
            val fallback = client.findBestStation("música regional")
            assertNotNull(fallback)
            assertTrue(fallback!!.tags.contains("regional"))
        }
    }

    @Test
    fun testPlayMusicOrRadioAndHomeStatusRejectInvalidConfigCleanly() = runBlocking {
        val unconfigured = HomeAssistantClient("", "")
        assertTrue(unconfigured.playMusicOrRadio("rock").contains("Error"))
        assertTrue(unconfigured.getHomeStatusSummary().contains("Error"))

        val configuredNoQuery = HomeAssistantClient("http://homeassistant.local:8123", "tok")
        val blankRes = configuredNoQuery.playMusicOrRadio("")
        assertTrue(blankRes.contains("indica"))
    }

    @Test
    fun testResolveMediaPlayerTargetAndAlexaDetection() {
        val client = HomeAssistantClient("http://ha.local:8123", "tok")
        val alexaNotify = setOf("oficina")

        assertTrue(client.isAlexaMediaPlayer("media_player.echo_del_cuarto", "Echo del Cuarto", "", alexaNotify))
        assertTrue(client.isAlexaMediaPlayer("media_player.oficina", "Estudio", "", alexaNotify))
        assertTrue(client.isAlexaMediaPlayer("media_player.echo_galeria", "Echo Galería", "", alexaNotify))
        assertFalse(client.isAlexaMediaPlayer("media_player.tv_sala", "TV Sala", "tv", alexaNotify))
        assertFalse(client.isAlexaMediaPlayer("media_player.chromecast_living", "Chromecast Living", "", alexaNotify))

        val candidates = listOf(
            HomeAssistantClient.MediaPlayerCandidate("media_player.tv_sala", "TV Sala", "off", "tv", isAlexa = false),
            HomeAssistantClient.MediaPlayerCandidate("media_player.echo_del_cuarto", "Echo del Cuarto", "standby", "", isAlexa = true),
            HomeAssistantClient.MediaPlayerCandidate("media_player.echo_sala", "Echo Sala", "standby", "", isAlexa = true),
            HomeAssistantClient.MediaPlayerCandidate("media_player.en_todas_partes", "En todas partes", "standby", "", isAlexa = true),
            HomeAssistantClient.MediaPlayerCandidate("media_player.echo_patio", "Echo Patio", "unavailable", "", isAlexa = true)
        )

        val defaultTarget = client.resolveMediaPlayerTarget(candidates, "")
        assertTrue(defaultTarget is HomeAssistantClient.MediaTargetResolution.Found)
        assertEquals("media_player.echo_del_cuarto", (defaultTarget as HomeAssistantClient.MediaTargetResolution.Found).target.entityId)

        val bedroomTarget = client.resolveMediaPlayerTarget(candidates, "recámara")
        assertTrue(bedroomTarget is HomeAssistantClient.MediaTargetResolution.Found)
        assertEquals("media_player.echo_del_cuarto", (bedroomTarget as HomeAssistantClient.MediaTargetResolution.Found).target.entityId)

        val livingTarget = client.resolveMediaPlayerTarget(candidates, "living")
        assertTrue(livingTarget is HomeAssistantClient.MediaTargetResolution.Found)
        assertEquals("media_player.echo_sala", (livingTarget as HomeAssistantClient.MediaTargetResolution.Found).target.entityId)

        val allTarget = client.resolveMediaPlayerTarget(candidates, "todas")
        assertTrue(allTarget is HomeAssistantClient.MediaTargetResolution.Found)
        assertEquals("media_player.en_todas_partes", (allTarget as HomeAssistantClient.MediaTargetResolution.Found).target.entityId)

        val unavailableTarget = client.resolveMediaPlayerTarget(candidates, "patio")
        assertTrue(unavailableTarget is HomeAssistantClient.MediaTargetResolution.Unreachable)

        val missingTarget = client.resolveMediaPlayerTarget(candidates, "garaje")
        assertTrue(missingTarget is HomeAssistantClient.MediaTargetResolution.NotFound)
        assertTrue((missingTarget as HomeAssistantClient.MediaTargetResolution.NotFound).available.isNotEmpty())
    }

    @Test
    fun testBuildAlexaMediaDraftsAndIntentDetection() {
        val client = HomeAssistantClient("http://ha.local:8123", "tok")

        assertEquals(HomeAssistantClient.MediaIntent.RADIO, client.detectMediaIntent("pon una estación de radio", "auto", "auto"))
        assertEquals(HomeAssistantClient.MediaIntent.MUSIC, client.detectMediaIntent("Bohemian Rhapsody", "auto", "auto"))
        assertEquals(HomeAssistantClient.MediaIntent.ROUTINE, client.detectMediaIntent("buenos días", "routine", "auto"))

        val genericStation = RadioStation("Exa FM 104.9", "https://stream.example/exa", "Mexico", "pop", 128)
        val genericDrafts = client.buildAlexaMediaDrafts("una estación de radio", HomeAssistantClient.MediaIntent.RADIO, "auto", genericStation)
        assertEquals("custom", genericDrafts.first().mediaContentType)
        assertEquals("pon Exa FM 104.9 en TuneIn", genericDrafts.first().mediaContentId)
        assertTrue(genericDrafts.any { it.mediaContentType == "TUNEIN" && it.mediaContentId == "Exa FM 104.9" })
        assertTrue(genericDrafts.any { it.mediaContentType == "music" && it.mediaContentId == "https://stream.example/exa" })

        val explicitRadio = client.buildAlexaMediaDrafts("W Radio 96.9", HomeAssistantClient.MediaIntent.RADIO, "auto", null)
        assertEquals("TUNEIN", explicitRadio.first().mediaContentType)
        assertEquals("W Radio 96.9", explicitRadio.first().mediaContentId)

        val spotifyDrafts = client.buildAlexaMediaDrafts("reproduce Queen", HomeAssistantClient.MediaIntent.MUSIC, "spotify", null)
        assertEquals("SPOTIFY", spotifyDrafts.first().mediaContentType)
        assertEquals("Queen", spotifyDrafts.first().mediaContentId)

        val amazonDrafts = client.buildAlexaMediaDrafts("Jazz relajante", HomeAssistantClient.MediaIntent.MUSIC, "amazon_music", null)
        assertEquals("AMAZON_MUSIC", amazonDrafts.first().mediaContentType)
        assertEquals("Jazz relajante", amazonDrafts.first().mediaContentId)

        val autoMusicDrafts = client.buildAlexaMediaDrafts("Caifanes", HomeAssistantClient.MediaIntent.MUSIC, "auto", null)
        assertEquals("custom", autoMusicDrafts.first().mediaContentType)
        assertEquals("reproduce Caifanes", autoMusicDrafts.first().mediaContentId)
    }

    @Test
    fun testFormatHomeStatusSummaryCoversAllDomainsAndDeduplicatesCameras() {
        val client = HomeAssistantClient("http://ha.local:8123", "tok")
        val snapshots = listOf(
            HomeAssistantClient.HomeEntitySnapshot("light.sala", "Luz Sala", "on"),
            HomeAssistantClient.HomeEntitySnapshot("light.cocina", "Luz Cocina", "off"),
            HomeAssistantClient.HomeEntitySnapshot(
                entityId = "media_player.echo_sala",
                friendlyName = "Echo Sala",
                state = "playing",
                mediaTitle = "La Bikina",
                source = "Spotify",
                isAlexa = true
            ),
            HomeAssistantClient.HomeEntitySnapshot(
                entityId = "weather.casa",
                friendlyName = "Casa",
                state = "sunny",
                temperature = 24.0,
                temperatureUnit = "°C",
                humidity = 45.0
            ),
            HomeAssistantClient.HomeEntitySnapshot(
                entityId = "sensor.temp_sala",
                friendlyName = "Temperatura Sala",
                state = "22.5",
                deviceClass = "temperature",
                unitOfMeasurement = "°C"
            ),
            HomeAssistantClient.HomeEntitySnapshot(
                entityId = "binary_sensor.puerta_principal",
                friendlyName = "Puerta Principal",
                state = "on",
                deviceClass = "door"
            ),
            HomeAssistantClient.HomeEntitySnapshot("camera.puerta_snapshot", "Puerta Snapshot", "idle"),
            HomeAssistantClient.HomeEntitySnapshot("camera.puerta_vista_en_vivo", "Puerta Vista en vivo", "idle"),
            HomeAssistantClient.HomeEntitySnapshot("camera.octoprint", "OctoPrint", "idle")
        )

        val summary = client.formatHomeStatusSummary(snapshots)
        assertTrue(summary.contains("Luces encendidas (1/2): Luz Sala"))
        assertTrue(summary.contains("Echo Sala (La Bikina - Spotify)"))
        assertTrue(summary.contains("Alexas disponibles (1): Echo Sala"))
        assertTrue(summary.contains("soleado y despejado"))
        assertTrue(summary.contains("Temperatura Sala: 22.5°C"))
        assertTrue(summary.contains("Puerta Principal (abierto)"))
        assertTrue(summary.contains("Cámaras (2, 2 disponibles): Puerta (idle), OctoPrint (idle)"))
    }
}
