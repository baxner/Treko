package com.treko.assistant.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrekoProactiveManagerTest {

    private class FakeProactiveDataSources(
        var homeSummary: String = "",
        var cameraResult: CameraAnomalyCheckResult? = null,
        var habitsSummary: String = "",
        var tasksSummary: String = "",
        var memories: List<Pair<String, String>> = emptyList(),
        var timersCount: Int = 0
    ) : ProactiveDataSources {
        override suspend fun getHomeStatusSummary(): String = homeSummary
        override suspend fun scanPriorityCameraForAnomalies(): CameraAnomalyCheckResult? = cameraResult
        override suspend fun getHabitsSummary(): String = habitsSummary
        override suspend fun getTasksSummary(): String = tasksSummary
        override suspend fun getVaultMemories(): List<Pair<String, String>> = memories
        override fun getActiveTimersCount(): Int = timersCount
    }

    @Test
    fun cameraAnomaly_takesHighestPriority() = runBlocking {
        val sources = FakeProactiveDataSources(
            homeSummary = "Luces: todas apagadas (4 en total).",
            cameraResult = CameraAnomalyCheckResult(
                cameraName = "Puerta Principal",
                entityId = "camera.puerta_principal_snapshot",
                visionReport = "Se observa una persona desconocida con un paquete en la entrada",
                hasAnomaly = true
            ),
            tasksSummary = "Tareas registradas (1):\n• Revisar sensores [Pendiente]"
        )
        val manager = TrekoProactiveManager(sources)

        val insight = manager.evaluateNow(includeCameraScan = true, force = false, hourOfDay = 15)

        assertNotNull(insight)
        assertEquals(ProactiveCategory.CAMERA_ANOMALY, insight!!.category)
        assertTrue(insight.title.contains("Puerta Principal"))
        assertTrue(insight.summaryText.contains("paquete"))
        assertTrue(insight.spokenPrompt.contains("Puerta Principal"))
    }

    @Test
    fun detectCameraAnomaly_ignoresNormalScenesAndDetectsRealEvents() {
        assertFalse(
            TrekoProactiveManager.detectCameraAnomaly(
                "No se observan anomalías ni personas, todo tranquilo en el jardín."
            )
        )
        assertFalse(
            TrekoProactiveManager.detectCameraAnomaly(
                "La entrada luce despejada, sin movimiento ni novedades."
            )
        )
        assertFalse(
            TrekoProactiveManager.detectCameraAnomaly(
                "La escena muestra un entorno tranquilo con vehículos estacionados y ninguna persona, paquete ni puerta abierta; no se observan anomalías."
            )
        )
        assertTrue(
            TrekoProactiveManager.detectCameraAnomaly(
                "Hay una persona parada frente a la puerta principal con un paquete."
            )
        )
    }

    @Test
    fun homeHabitsTasksAndMemory_synthesizesCombinedInsight() = runBlocking {
        val sources = FakeProactiveDataSources(
            cameraResult = CameraAnomalyCheckResult(
                cameraName = "Jardín",
                entityId = "camera.jardin_snapshot",
                visionReport = "No se observan anomalías ni personas, todo tranquilo.",
                hasAnomaly = false
            ),
            homeSummary = "Sensores y accesos activos (1): Puerta Jardín (abierto).",
            tasksSummary = "Tareas registradas (2):\n• Revisar sensores [Pendiente]\n• Pomodoro [Hecha]",
            habitsSummary = "Hábitos registrados (2):\n• 🧘 Meditación [Completado]\n• 🚶 Caminar 30 min [Pendiente]\n• 💧 Hidratación: 3 de 8 vasos",
            memories = listOf("recordatorio_cafe" to "Comprar café en grano por la tarde")
        )
        val manager = TrekoProactiveManager(sources)

        val insight = manager.evaluateNow(includeCameraScan = true, force = false, hourOfDay = 16)

        assertNotNull(insight)
        assertEquals(ProactiveCategory.HOME_STATUS, insight!!.category)
        assertTrue(insight.summaryText.contains("Puerta Jardín"))
        assertTrue(insight.summaryText.contains("Revisar sensores"))
        assertTrue(insight.summaryText.contains("Caminar 30 min"))
        assertTrue(insight.spokenPrompt.contains("Hidratación") || insight.spokenPrompt.contains("hidratación"))
        assertTrue(insight.spokenPrompt.contains("Comprar café en grano"))
    }

    @Test
    fun deduplicationCooldown_suppressesDuplicateUntilForcedOrExpired() = runBlocking {
        var nowMs = 1_000_000L
        val sources = FakeProactiveDataSources(
            tasksSummary = "Tareas registradas (1):\n• Sacar basura [Pendiente]"
        )
        val manager = TrekoProactiveManager(sources, clockMs = { nowMs })

        val first = manager.evaluateNow(includeCameraScan = false, force = false, hourOfDay = 12)
        assertNotNull(first)

        nowMs += 10L * 60_000L
        val second = manager.evaluateNow(includeCameraScan = false, force = false, hourOfDay = 12)
        assertNull(second)

        val forced = manager.evaluateNow(includeCameraScan = false, force = true, hourOfDay = 12)
        assertNotNull(forced)

        nowMs += 46L * 60_000L
        val afterCooldown = manager.evaluateNow(includeCameraScan = false, force = false, hourOfDay = 12)
        assertNotNull(afterCooldown)
    }

    @Test
    fun isWithinQuietHours_respectsScheduleAndToggle() {
        assertTrue(TrekoProactiveManager.isWithinQuietHours(23, quietHoursEnabled = true))
        assertTrue(TrekoProactiveManager.isWithinQuietHours(3, quietHoursEnabled = true))
        assertFalse(TrekoProactiveManager.isWithinQuietHours(14, quietHoursEnabled = true))
        assertFalse(TrekoProactiveManager.isWithinQuietHours(2, quietHoursEnabled = false))
    }
}
