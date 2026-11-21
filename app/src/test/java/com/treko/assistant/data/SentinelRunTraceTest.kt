package com.treko.assistant.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentinelRunTraceTest {

    private fun trace(clock: () -> Long = { 1_000L }) = SentinelRunTrace(
        sensorId = "binary_sensor.patio_person_occupancy",
        cameraEntityId = "camera.patio",
        area = "Patio",
        label = "person",
        clockMs = clock
    )

    @Test
    fun formatsOneLineWithCamerasStagesAndDecision() {
        var now = 1_000L
        val t = trace { now }
        t.stage("home", 120)
        t.stage("scan", 8_000)
        t.look("Patio", "camera.patio_snapshot", 7_900, "Una persona\ncamina por el patio. " + "x".repeat(300), anomaly = false)
        t.note("frigate_busy=camera.patio")
        t.decide("normal", "no anomaly in the verdict")
        now = 9_500L

        val line = t.format()
        assertFalse(line.contains('\n'))
        assertTrue(line.startsWith("Sentinel run: trigger=binary_sensor.patio_person_occupancy label=person camera=camera.patio"))
        assertTrue(line.contains("\"Patio\" image=camera.patio_snapshot 7900ms anomaly=false verdict=\"Una persona camina"))
        assertTrue(line.contains("frigate_busy=camera.patio"))
        assertTrue(line.contains("stages: home=120ms scan=8000ms total=8500ms"))
        assertTrue(line.endsWith("decision=normal reason=\"no anomaly in the verdict\""))
        // Verdict cut to 160 chars plus the ellipsis.
        val verdict = line.substringAfter("verdict=\"").substringBefore('"')
        assertEquals(SentinelRunTrace.VERDICT_PREVIEW_CHARS + 1, verdict.length)
    }

    @Test
    fun firstDecisionSticksUnlessOverridden() {
        val t = trace()
        t.decide("alert")
        t.decide("normal")
        assertTrue(t.format().endsWith("decision=alert"))
        t.decide("alert_notified", reason = "quiet hours", override = true)
        assertTrue(t.format().endsWith("decision=alert_notified reason=\"quiet hours\""))
    }

    @Test
    fun emptyRunSaysSo() {
        val t = trace()
        val line = t.format()
        assertTrue(line.contains("looked=none"))
        assertTrue(line.endsWith("decision=unknown"))
        assertFalse(t.hasDecision)
    }

    @Test
    fun blankVerdictIsVisible() {
        val t = trace()
        t.look("Patio", "camera.patio", 3_000, "", anomaly = null)
        assertTrue(t.format().contains("verdict=\"<empty>\""))
    }

    @Test
    fun travelsInTheCoroutineContext() = runBlocking {
        val t = trace()
        assertNull(kotlin.coroutines.coroutineContext[SentinelRunTrace])
        withContext(t) {
            val found = kotlin.coroutines.coroutineContext[SentinelRunTrace]
            assertNotNull(found)
            found!!.decide("skipped", "test")
        }
        assertTrue(t.format().contains("decision=skipped"))
    }

    @Test
    fun managerRecordsTheSentinelOutcome() = runBlocking {
        val sources = object : ProactiveDataSources {
            override suspend fun getHomeStatusSummary(): String = ""
            override suspend fun scanPriorityCameraForAnomalies(): CameraAnomalyCheckResult =
                CameraAnomalyCheckResult("Patio", "camera.patio", "Todo tranquilo, sin novedad.", hasAnomaly = false)
            override suspend fun getHabitsSummary(): String = ""
            override suspend fun getTasksSummary(): String = ""
            override suspend fun getVaultMemories(): List<Pair<String, String>> = emptyList()
            override fun getActiveTimersCount(): Int = 0
        }
        val manager = TrekoProactiveManager(sources)
        val t = trace()
        val insight = withContext(t) {
            manager.evaluateNow(includeCameraScan = true, hourOfDay = 15, camerasOnly = true)
        }
        assertNull(insight)
        assertTrue(t.format().contains("decision=normal"))
        assertTrue(t.format().contains("home="))

        val off = trace()
        withContext(off) { manager.evaluateNow(includeCameraScan = false, hourOfDay = 15, camerasOnly = true) }
        assertTrue(off.format().contains("decision=skipped reason=\"camera scan is off\""))
    }

    @Test
    fun exposesOutcomeAndLooksForTheAlertsLog() {
        val t = trace()
        assertNull(t.decision)
        t.look("Patio", "camera.patio_snapshot", 10, "Todo tranquilo", anomaly = false)
        t.decide("normal", "no anomaly in the verdict")
        t.decide("alert") // the first decision sticks
        assertEquals("normal", t.decision)
        assertEquals("no anomaly in the verdict", t.reason)
        assertEquals(listOf("Todo tranquilo"), t.looks().map { it.verdict })
        t.decide("alert_spoken", override = true)
        assertEquals("alert_spoken", t.decision)
        assertNull(t.reason)
    }

    @Test
    fun cameraAlertInsightCarriesTheCamerasAndReport() = runBlocking {
        val sources = object : ProactiveDataSources {
            override suspend fun getHomeStatusSummary(): String = ""
            override suspend fun scanPriorityCamerasForAnomalies(homeStatusSummary: String) = listOf(
                CameraAnomalyCheckResult(
                    cameraName = "Patio",
                    entityId = "camera.patio_snapshot",
                    visionReport = "[ANOMALIA] Una persona trepa la barda",
                    hasAnomaly = true,
                    cameraEntityId = "camera.patio"
                )
            )
            override suspend fun getHabitsSummary(): String = ""
            override suspend fun getTasksSummary(): String = ""
            override suspend fun getVaultMemories(): List<Pair<String, String>> = emptyList()
            override fun getActiveTimersCount(): Int = 0
        }
        val insight = TrekoProactiveManager(sources).evaluateNow(includeCameraScan = true, hourOfDay = 15, camerasOnly = true)
        assertNotNull(insight)
        assertEquals(listOf("camera.patio"), insight!!.cameraEntityIds)
        assertEquals(listOf("Patio"), insight.cameraNames)
        assertTrue(insight.visionReport!!.contains("trepa la barda"))
        assertNull(insight.alertId)
    }
}
