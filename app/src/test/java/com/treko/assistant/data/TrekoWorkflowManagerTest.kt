package com.treko.assistant.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrekoWorkflowManagerTest {

    private class FakeWorkflowActions(
        var failOnHaControl: Boolean = false
    ) : WorkflowActions {
        val calls = mutableListOf<String>()

        override suspend fun haControl(
            target: String,
            action: String,
            brightness: Int,
            color: String,
            source: String
        ): String {
            calls.add("ha_control:$target:$action:b=$brightness:c=$color:s=$source")
            if (failOnHaControl) {
                throw IllegalStateException("HA timeout")
            }
            return "HA $action -> $target"
        }

        override suspend fun playMedia(
            query: String,
            device: String,
            mediaType: String,
            provider: String
        ): String {
            calls.add("play_media:$query:$device:$mediaType:$provider")
            return "Playing '$query' on '${device.ifBlank { "default" }}'"
        }

        override suspend fun broadcast(message: String, target: String): String {
            calls.add("broadcast:$message:$target")
            return "Broadcast '$message'"
        }

        override suspend fun triggerScene(scene: String): String {
            calls.add("scene:$scene")
            return "Scene '$scene' activated"
        }

        override suspend fun openCameraWall(live: Boolean): String {
            calls.add("camera_wall:live=$live")
            return "Camera wall opened (live=$live)"
        }

        override suspend fun setCinemaMode(enabled: Boolean): String {
            calls.add("cinema_mode:$enabled")
            return "Cinema mode=$enabled"
        }

        override suspend fun startTimer(minutes: Int, seconds: Int, label: String): String {
            calls.add("timer:${minutes}m${seconds}s:$label")
            return "Timer $minutes min '$label'"
        }

        override suspend fun getWeather(): String {
            calls.add("weather")
            return "Sunny 24C"
        }

        override suspend fun getHomeSummary(): String {
            calls.add("home_summary")
            return "2 lights on, 4 cameras idle"
        }

        override suspend fun getTasksSummary(): String {
            calls.add("tasks_summary")
            return "3 tasks pending"
        }

        override suspend fun getHabitsSummary(): String {
            calls.add("habits_summary")
            return "5/8 water glasses"
        }
    }

    @Test
    fun builtInWorkflowsPresentAndSummarized() {
        val manager = TrekoWorkflowManager(InMemoryWorkflowStorage())
        val workflows = manager.listWorkflows()
        val ids = workflows.map { it.id }

        assertEquals(5, workflows.size)
        assertTrue(ids.containsAll(listOf("buenos_dias", "buenas_noches", "modo_cine", "vigilancia_total", "concentracion")))
        assertTrue(workflows.all { it.isBuiltIn && it.steps.isNotEmpty() })

        val summary = manager.listWorkflowsSummary()
        assertTrue(summary.contains("Buenos días"))
        assertTrue(summary.contains("Vigilancia total"))
    }

    @Test
    fun fuzzySpanishNameMatchingResolvesAccentsAndAliases() {
        val manager = TrekoWorkflowManager(InMemoryWorkflowStorage())

        assertEquals("buenos_dias", manager.findWorkflow("buenos dias")?.id)
        assertEquals("buenos_dias", manager.findWorkflow("Buenos días")?.id)
        assertEquals("buenos_dias", manager.findWorkflow("rutina matutina")?.id)
        assertEquals("buenas_noches", manager.findWorkflow("a dormir")?.id)
        assertEquals("modo_cine", manager.findWorkflow("modo cine")?.id)
        assertEquals("modo_cine", manager.findWorkflow("ver película")?.id)
        assertEquals("vigilancia_total", manager.findWorkflow("seguridad")?.id)
        assertEquals("vigilancia_total", manager.findWorkflow("todas las cámaras")?.id)
        assertEquals("concentracion", manager.findWorkflow("concentración")?.id)
        assertEquals("concentracion", manager.findWorkflow("pomodoro")?.id)
        assertNull(manager.findWorkflow("rutina totalmente inexistente xyz"))
    }

    @Test
    fun runBuiltInWorkflowsExecutesExpectedStepsInOrder() = runBlocking {
        val manager = TrekoWorkflowManager(InMemoryWorkflowStorage())

        val morningActions = FakeWorkflowActions()
        val morningOut = manager.runWorkflow("buenos dias", morningActions)
        assertTrue(morningOut.contains("Buenos días"))
        assertEquals(
            listOf(
                "cinema_mode:false",
                "weather",
                "tasks_summary",
                "habits_summary",
                "home_summary"
            ),
            morningActions.calls.take(5)
        )
        assertTrue(morningActions.calls[5].startsWith("play_media:"))

        val securityActions = FakeWorkflowActions()
        val securityOut = manager.runWorkflow("seguridad", securityActions)
        assertTrue(securityOut.contains("Vigilancia total"))
        assertEquals(
            listOf("camera_wall:live=true", "home_summary"),
            securityActions.calls
        )

        val focusActions = FakeWorkflowActions()
        manager.runWorkflow("concentracion", focusActions)
        assertEquals("cinema_mode:true", focusActions.calls[0])
        assertEquals("timer:25m0s:Concentración Pomodoro", focusActions.calls[1])
        assertTrue(focusActions.calls[2].startsWith("play_media:"))
        assertEquals("tasks_summary", focusActions.calls[3])
    }

    @Test
    fun createPersistRunUpdateAndDeleteCustomWorkflow() = runBlocking {
        val storage = InMemoryWorkflowStorage()
        val manager1 = TrekoWorkflowManager(storage)

        val stepsJson = """
            [
              {"type":"cinema_mode","enabled":true},
              {"type":"ha_control","target":"luz sala","action":"turn_on","brightness":35,"color":"azul"},
              {"type":"play_media","query":"Alfa 91.3","device":"sala","media_type":"radio","provider":"tunein"},
              {"type":"broadcast","message":"Iniciando \"Modo Fiesta\"!","target":"cocina"},
              {"type":"scene","scene":"fiesta"},
              {"type":"camera_wall","live":false},
              {"type":"timer","minutes":15,"seconds":30,"label":"Snack"},
              {"type":"weather"},
              {"type":"home_summary"},
              {"type":"tasks_summary"},
              {"type":"habits_summary"}
            ]
        """.trimIndent()

        val createMsg = manager1.createCustomWorkflow(
            name = "Modo Fiesta",
            description = "Luces azules y radio en la sala",
            stepsJson = stepsJson
        )
        assertTrue(createMsg.contains("creada"))
        assertEquals(6, manager1.listWorkflows().size)

        // Reload in a brand-new manager backed by same storage to verify JSON serialization/deserialization
        val manager2 = TrekoWorkflowManager(storage)
        val loaded = manager2.findWorkflow("modo fiesta")
        assertNotNull(loaded)
        assertEquals(11, loaded!!.steps.size)
        assertEquals(false, loaded.isBuiltIn)

        val actions = FakeWorkflowActions()
        val runResult = manager2.runWorkflow("Modo Fiesta", actions)
        assertTrue(runResult.contains("11 pasos"))
        assertEquals(11, actions.calls.size)
        assertEquals("cinema_mode:true", actions.calls[0])
        assertEquals("ha_control:luz sala:turn_on:b=35:c=azul:s=", actions.calls[1])
        assertEquals("play_media:Alfa 91.3:sala:radio:tunein", actions.calls[2])
        assertEquals("broadcast:Iniciando \"Modo Fiesta\"!:cocina", actions.calls[3])
        assertEquals("scene:fiesta", actions.calls[4])
        assertEquals("camera_wall:live=false", actions.calls[5])
        assertEquals("timer:15m30s:Snack", actions.calls[6])
        assertEquals("weather", actions.calls[7])
        assertEquals("home_summary", actions.calls[8])
        assertEquals("tasks_summary", actions.calls[9])
        assertEquals("habits_summary", actions.calls[10])

        // Update existing custom workflow
        val updateMsg = manager2.createCustomWorkflow(
            name = "Modo Fiesta",
            description = "Actualizada",
            stepsJson = """{"type":"broadcast","message":"Solo aviso"}"""
        )
        assertTrue(updateMsg.contains("actualizada"))
        assertEquals(1, manager2.findWorkflow("modo_fiesta")?.steps?.size)

        // Delete custom workflow and verify persistence
        val deleteMsg = manager2.deleteWorkflow("modo fiesta")
        assertTrue(deleteMsg.contains("eliminada"))
        val manager3 = TrekoWorkflowManager(storage)
        assertEquals(5, manager3.listWorkflows().size)
        assertNull(manager3.findWorkflow("modo fiesta"))
    }

    @Test
    fun validationProtectsBuiltInsAndRejectsInvalidSteps() = runBlocking {
        val storage = InMemoryWorkflowStorage()
        val manager = TrekoWorkflowManager(storage)

        val emptyName = manager.createCustomWorkflow("", "desc", """[{"type":"weather"}]""")
        assertTrue(emptyName.startsWith("Error"))

        val builtInOverride = manager.createCustomWorkflow("Modo cine", "desc", """[{"type":"weather"}]""")
        assertTrue(builtInOverride.startsWith("Error"))

        val badJson = manager.createCustomWorkflow("Rota", "desc", "not valid json")
        assertTrue(badJson.startsWith("Error"))

        val unknownType = manager.createCustomWorkflow("Desconocida", "desc", """[{"type":"unknown_magic"}]""")
        assertTrue(unknownType.startsWith("Error"))

        val deleteBuiltIn = manager.deleteWorkflow("buenos dias")
        assertTrue(deleteBuiltIn.contains("no se puede eliminar"))

        // Step failure in runWorkflow should not abort subsequent steps
        val actions = FakeWorkflowActions(failOnHaControl = true)
        val out = manager.runWorkflow("buenas noches", actions)
        assertTrue(out.contains("Error en paso 1"))
        assertTrue(actions.calls.contains("home_summary"))
    }
}
