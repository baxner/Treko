package com.treko.assistant.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Camera alert speech, keyword fallback and briefing wording. */
class ProactiveSpeechTest {

    private class Sources(
        var home: String = "",
        var cameras: List<CameraAnomalyCheckResult> = emptyList(),
        var habits: String = "",
        var tasks: String = "",
        var memories: List<Pair<String, String>> = emptyList()
    ) : ProactiveDataSources {
        override suspend fun getHomeStatusSummary(): String = home
        override suspend fun scanPriorityCamerasForAnomalies(homeStatusSummary: String) = cameras
        override suspend fun getHabitsSummary(): String = habits
        override suspend fun getTasksSummary(): String = tasks
        override suspend fun getVaultMemories(): List<Pair<String, String>> = memories
        override fun getActiveTimersCount(): Int = 0
    }

    private fun verdict(level: VisionLevel, aviso: String = "Hay una persona parada frente a la puerta de Patio.") =
        VisionVerdict(true, 1, 0, false, "frente a la puerta", "parada", level, "persona parada frente a la puerta", aviso)

    private fun alertOn(camera: String, v: VisionVerdict?, report: String = "{}") = CameraAnomalyCheckResult(
        cameraName = camera,
        entityId = "image.${camera.lowercase()}_person",
        visionReport = report,
        hasAnomaly = true,
        cameraEntityId = "camera.${camera.lowercase()}",
        verdict = v,
        label = "person"
    )

    // --- keyword fallback (only when the model gave no JSON) ---

    @Test
    fun keywordFallbackIgnoresNegationsAndOrdinaryScenes() {
        val calm = listOf(
            "No hay anomalías; una persona camina por la acera.",
            "No se ve a alguien en la entrada.",
            "No hay nadie; se ve una caja de cartón vieja junto a la pared.",
            "[ANOMALIA] ninguna, todo bien",
            "[ANOMALIA]: no hay nada que reportar",
            "Se ve una persona caminando por la banqueta frente a la casa.",
            "Hay una persona en el jardín.",
            "Alguien pasa en bicicleta.",
            "Una caja junto al portón.",
            "Un repartidor hace una entrega en la casa vecina."
        )
        calm.forEach { assertFalse(it, TrekoProactiveManager.detectCameraAnomaly(it)) }
    }

    @Test
    fun keywordFallbackStillCatchesRealEvents() {
        val real = listOf(
            "[ANOMALIA] Una persona trepa la barda del patio",
            "Hay un paquete en la entrada.",
            "El portón abierto de par en par.",
            "Se ve humo saliendo de la cochera.",
            "Una persona está intentando abrir la puerta."
        )
        real.forEach { assertTrue(it, TrekoProactiveManager.detectCameraAnomaly(it)) }
    }

    // --- camera alert ---

    @Test
    fun cameraAlertSpeaksTheVerdictPhraseOnly() = runBlocking {
        val sources = Sources(
            home = "Sensores y accesos activos (1): Movimiento Patio (movimiento).",
            cameras = listOf(alertOn("Patio", verdict(VisionLevel.ATTENTION)))
        )
        val insight = TrekoProactiveManager(sources).evaluateNow(includeCameraScan = true, hourOfDay = 15, camerasOnly = true)
        assertNotNull(insight)
        insight!!
        assertEquals("Hay una persona parada frente a la puerta de Patio.", insight.spokenSpeechText)
        assertEquals("atencion", insight.visionLevel)
        assertEquals("Parada frente a la puerta. Persona parada frente a la puerta.", insight.visionReport)
        // The follow-up facts do not repeat the speech nor home sensors.
        assertFalse(insight.spokenPrompt.contains(insight.spokenSpeechText))
        assertFalse(insight.spokenPrompt.contains("movimiento", ignoreCase = true))
        assertTrue(insight.spokenPrompt.contains("atencion"))
    }

    @Test
    fun longPhraseIsCutAtAWordAndFallbackNamesTheCamera() = runBlocking {
        val long = (1..30).joinToString(" ") { "palabra$it" }
        val sources = Sources(cameras = listOf(alertOn("Patio", verdict(VisionLevel.URGENT, aviso = long))))
        val insight = TrekoProactiveManager(sources).evaluateNow(includeCameraScan = true, hourOfDay = 15, camerasOnly = true)!!
        assertEquals(VisionVerdict.MAX_SPOKEN_WORDS, insight.spokenSpeechText.removeSuffix(".").split(' ').size)
        assertTrue(insight.spokenSpeechText.endsWith("palabra${VisionVerdict.MAX_SPOKEN_WORDS}."))

        val noJson = Sources(cameras = listOf(alertOn("Entrada", null, report = "[ANOMALIA] Un paquete quedó en la entrada. Nadie lo recoge.")))
        val fallback = TrekoProactiveManager(noJson).evaluateNow(includeCameraScan = true, hourOfDay = 15, camerasOnly = true)!!
        assertEquals("En Entrada: un paquete quedó en la entrada.", fallback.spokenSpeechText)
    }

    @Test
    fun sameCameraLabelAndLevelIsNotRepeatedButUrgentIs() = runBlocking {
        var now = 1_000_000L
        val sources = Sources(cameras = listOf(alertOn("Patio", verdict(VisionLevel.ATTENTION, aviso = "Una persona en la puerta."))))
        val manager = TrekoProactiveManager(sources, clockMs = { now })
        assertNotNull(manager.evaluateNow(includeCameraScan = true, hourOfDay = 15, camerasOnly = true))
        // Different wording, same camera + label + level: suppressed.
        now += 11 * 60_000L
        sources.cameras = listOf(alertOn("Patio", verdict(VisionLevel.ATTENTION, aviso = "Alguien espera en la puerta.")))
        val trace = SentinelRunTrace("binary_sensor.patio_person_occupancy", "camera.patio", "Patio", "person")
        val repeat = kotlinx.coroutines.withContext(trace) {
            manager.evaluateNow(includeCameraScan = true, hourOfDay = 15, camerasOnly = true)
        }
        assertNull(repeat)
        assertEquals("suppressed", trace.decision)
        // An urgent verdict always gets through, even the same one again.
        sources.cameras = listOf(alertOn("Patio", verdict(VisionLevel.URGENT, aviso = "Alguien trepa la barda.")))
        assertNotNull(manager.evaluateNow(includeCameraScan = true, hourOfDay = 15, camerasOnly = true))
        now += 60_000L
        assertNotNull(manager.evaluateNow(includeCameraScan = true, hourOfDay = 15, camerasOnly = true))
        // After the repeat window the same alert is given again.
        now += 21 * 60_000L
        sources.cameras = listOf(alertOn("Patio", verdict(VisionLevel.ATTENTION)))
        assertNotNull(manager.evaluateNow(includeCameraScan = true, hourOfDay = 15, camerasOnly = true))
    }

    // --- briefings ---

    @Test
    fun motionSensorsAreNotAHomeTopic() = runBlocking {
        val sources = Sources(home = "Sensores y accesos activos (2): Patio Person occupancy (movimiento), Sala (movimiento).")
        val manager = TrekoProactiveManager(sources)
        assertNull(manager.evaluateNow(includeCameraScan = false, hourOfDay = 15))
        assertNull(TrekoProactiveManager.accessAlertsOnly("Sensores y accesos activos (1): Sala (movimiento)"))
        assertEquals(
            "Accesos abiertos (2): Puerta Patio (abierto), Chapa (unlocked)",
            TrekoProactiveManager.accessAlertsOnly(
                "Sensores y accesos activos (3): Puerta Patio (abierto), Sala (movimiento), Chapa (unlocked) y 2 más"
            )
        )
    }

    @Test
    fun motionTogglingDoesNotRepeatTheOpenDoor() = runBlocking {
        var now = 10_000_000L
        val sources = Sources(home = "Sensores y accesos activos (1): Puerta Patio (abierto).")
        val manager = TrekoProactiveManager(sources, clockMs = { now })
        val first = manager.evaluateNow(includeCameraScan = false, hourOfDay = 15)
        assertNotNull(first)
        assertEquals("En casa sigue abierto o sin seguro: Puerta Patio. ¿Quieres que ajuste algo en casa?", first!!.spokenSpeechText)
        now += 5 * 60_000L
        sources.home = "Sensores y accesos activos (2): Puerta Patio (abierto), Sala (movimiento)."
        assertNull(manager.evaluateNow(includeCameraScan = false, hourOfDay = 15))
    }

    @Test
    fun briefingHasNoGreetingEmojiOrCadenceAndAsksOnlyAboutWhatItSaid() = runBlocking {
        val sources = Sources(
            habits = "Hábitos registrados (2):\n• 🧘 Meditación [Pendiente]\n• 💧 Hidratación: 3 de 8 vasos"
        )
        val insight = TrekoProactiveManager(sources).evaluateNow(includeCameraScan = false, hourOfDay = 16)
        assertNotNull(insight)
        val speech = insight!!.spokenSpeechText
        assertFalse(speech, speech.startsWith("Hola"))
        assertFalse(speech, speech.contains("🧘") || speech.contains("💧"))
        assertFalse(speech, speech.contains("cada"))
        assertFalse(speech, speech.contains("casa"))
        assertFalse(speech, speech.contains("tarea"))
        assertTrue(speech, speech.endsWith("¿Quieres que marque algún hábito o registre un vaso de agua?"))
        assertTrue(speech, speech.contains("Te queda 1 hábito por completar: Meditación."))
        // The follow-up context does not repeat the speech.
        assertFalse(insight.spokenPrompt.contains(speech))
    }

    @Test
    fun closingQuestionFollowsTheSpokenTopics() {
        assertNull(TrekoProactiveManager.closingQuestion(emptySet()))
        assertEquals("¿Quieres que ajuste algo en casa?", TrekoProactiveManager.closingQuestion(setOf("home")))
        assertEquals(
            "¿Quieres que ajuste algo en casa o marque alguna tarea como hecha?",
            TrekoProactiveManager.closingQuestion(setOf("home", "tasks"))
        )
    }

    @Test
    fun hydrationPluralsAreRight() {
        val base = 1_700_000_000_000L
        val oneHour = TrekoProactiveManager.evaluateHydrationPacing(
            currentGlasses = 1, targetGlasses = 8, lastWaterIntakeMs = base - 65L * 60_000L,
            lastWaterReminderMs = 0L, isWaterSnoozed = false, nowMs = base, hourOfDay = 18, force = true
        )
        assertTrue(oneHour.spokenCoaching, oneHour.spokenCoaching.contains("pasó 1 hora desde"))
        assertFalse(oneHour.spokenCoaching.contains("1 horas"))
        val mixed = TrekoProactiveManager.evaluateHydrationPacing(
            currentGlasses = 7, targetGlasses = 8, lastWaterIntakeMs = base - 81L * 60_000L,
            lastWaterReminderMs = 0L, isWaterSnoozed = false, nowMs = base, hourOfDay = 18, force = true
        )
        assertTrue(mixed.spokenCoaching, mixed.spokenCoaching.contains("pasaron 1 hora y 21 minutos"))
        assertTrue(mixed.spokenCoaching, mixed.spokenCoaching.contains("te falta 1 antes de dormir"))
        val minute = TrekoProactiveManager.evaluateHydrationPacing(
            currentGlasses = 2, targetGlasses = 8, lastWaterIntakeMs = base - 60_000L,
            lastWaterReminderMs = 0L, isWaterSnoozed = false, nowMs = base, hourOfDay = 18, force = true
        )
        assertTrue(minute.spokenCoaching, minute.spokenCoaching.contains("hace 1 minuto;"))
    }

    @Test
    fun vaultNoteOnlyWhenItNamesToday() = runBlocking {
        val tuesday = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 24, 12, 0) }
        assertTrue(TrekoProactiveManager.isMemoryRelevantToday("basura", "Sacar la basura los martes", tuesday))
        assertTrue(TrekoProactiveManager.isMemoryRelevantToday("cita", "Dentista el 24/11 a las 5", tuesday))
        assertTrue(TrekoProactiveManager.isMemoryRelevantToday("cita", "Dentista el 24 de noviembre", tuesday))
        assertTrue(TrekoProactiveManager.isMemoryRelevantToday("trip", "Flight on 2026-11-24", tuesday))
        assertFalse(TrekoProactiveManager.isMemoryRelevantToday("cafe", "Comprar café en grano", tuesday))
        assertFalse(TrekoProactiveManager.isMemoryRelevantToday("cita", "Dentista el 4/11", tuesday))
        assertFalse(TrekoProactiveManager.isMemoryRelevantToday("cita", "Dentista el 24/12", tuesday))

        val now = Calendar.getInstance().timeInMillis
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val weekday = listOf("domingo", "lunes", "martes", "miercoles", "jueves", "viernes", "sabado")[today.get(Calendar.DAY_OF_WEEK) - 1]
        val sources = Sources(memories = listOf("cafe" to "Comprar café", "rutina" to "Los $weekday riego las plantas"))
        val manager = TrekoProactiveManager(sources, clockMs = { now })
        val first = manager.evaluateNow(includeCameraScan = false, hourOfDay = 12)
        assertNotNull(first)
        assertTrue(first!!.spokenSpeechText.contains("riego las plantas"))
        assertFalse(first.spokenSpeechText.contains("Comprar café"))
        // Once a day.
        assertNull(manager.evaluateNow(includeCameraScan = false, hourOfDay = 12))
    }
}
