package com.treko.core.network

import com.treko.core.common.model.TrekoSkills
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrekoToolCatalogTest {

    private val everySkill = setOf(
        TrekoSkills.OCTOPRINT,
        TrekoSkills.FRIGATE,
        TrekoSkills.MOTION_SENSORS,
        TrekoSkills.LLM_VISION,
        TrekoSkills.ALEXA_MEDIA
    )

    @Suppress("UNCHECKED_CAST")
    private fun tools(skills: Set<String>): List<Map<String, Any?>> =
        MiniJson(TrekoToolCatalog.toolsJson(skills)).parse() as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun properties(tool: Map<String, Any?>): Map<String, Map<String, Any?>> =
        (tool["inputSchema"] as Map<String, Any?>)["properties"] as Map<String, Map<String, Any?>>

    @Test
    fun `tools list is valid JSON and stays small`() {
        val full = TrekoToolCatalog.toolsJson(everySkill)
        val bare = TrekoToolCatalog.toolsJson(emptySet())
        // Seen in the test report: the size the model receives on every turn.
        println("tools/list size: ${full.length} chars with every skill (${tools(everySkill).size} tools), " +
            "${bare.length} chars with none (${tools(emptySet()).size} tools)")
        TrekoToolCatalog.tools(everySkill)
            .map { t -> t.name to t.description.length + t.params.sumOf { it.name.length + it.description.length + (it.enum?.sumOf { e -> e.length + 3 } ?: 0) + 40 } }
            .sortedByDescending { it.second }
            .forEach { println("  ~${it.second} ${it.first}") }
        assertTrue("tools/list is ${full.length} chars", full.length < 12_000)
        tools(everySkill).forEach { tool ->
            assertTrue(tool["name"] as String, (tool["description"] as String).isNotBlank())
            assertEquals("object", (tool["inputSchema"] as Map<*, *>)["type"])
        }
    }

    @Test
    fun `optional tools follow the detected skills`() {
        val bare = TrekoToolCatalog.toolNames(emptySet())
        assertFalse("analyze_camera" in bare)
        assertFalse("get_3d_printer_status" in bare)
        assertFalse("watch_cameras" in bare)
        val full = TrekoToolCatalog.toolNames(everySkill)
        assertTrue("analyze_camera" in full)
        assertTrue("get_3d_printer_status" in full)
        assertTrue("watch_cameras" in full)
        // Merged into view_camera; the app still answers the old name.
        assertFalse("view_all_cameras" in full)
        assertEquals(full.size, full.toSet().size)
    }

    @Test
    fun `descriptions degrade without LLM Vision`() {
        val withoutVision = tools(setOf(TrekoSkills.FRIGATE))
        val watch = withoutVision.first { it["name"] == "watch_cameras" }["description"] as String
        assertTrue(watch, watch.contains("LLM Vision"))
        val view = withoutVision.first { it["name"] == "view_camera" }["description"] as String
        assertFalse(view, view.contains("analyze_camera"))
        val withVision = tools(setOf(TrekoSkills.FRIGATE, TrekoSkills.LLM_VISION))
        assertFalse((withVision.first { it["name"] == "watch_cameras" }["description"] as String).contains("LLM Vision"))
    }

    @Test
    fun `no personal examples, brands or competing absolutes`() {
        val text = TrekoToolCatalog.toolsJson(everySkill) + TrekoToolCatalog.INITIALIZE_INSTRUCTIONS
        listOf(
            "alexa_media_en_todas_partes", "Exa FM", "Alfa 91", "Ángeles Azules", "samsung", "roku",
            "timbre", "recamara", "recámara", "SIEMPRE", "PRIORIDAD", "regañar", "vista_en_vivo"
        ).forEach { banned ->
            assertFalse(banned, text.contains(banned, ignoreCase = true))
        }
        assertFalse(Regex("\\bring\\b", RegexOption.IGNORE_CASE).containsMatchIn(text))
        // Alexa wording only for homes with Alexa Media.
        assertFalse(TrekoToolCatalog.toolsJson(emptySet()).contains("Alexa"))
    }

    @Test
    fun `every action parameter lists its values`() {
        tools(everySkill).forEach { tool ->
            properties(tool)["action"]?.let { action ->
                assertNotNull("${tool["name"]}.action has no enum", action["enum"])
            }
        }
        val vault = tools(everySkill).first { it["name"] == "manage_vault_memory" }
        assertTrue("delete" in (properties(vault)["action"]!!["enum"] as List<*>))
    }

    @Test
    fun `sensitive tools take a confirm flag and say why`() {
        listOf("home_assistant_call_service", "home_assistant_control").forEach { name ->
            val tool = tools(everySkill).first { it["name"] == name }
            assertEquals("boolean", properties(tool)["confirm"]!!["type"])
            assertTrue(name, (tool["description"] as String).contains("confirm"))
        }
        val callService = tools(everySkill).first { it["name"] == "home_assistant_call_service" }
        assertTrue((callService["description"] as String).startsWith("Último recurso"))
    }

    @Test
    fun `meditation tool is always offered and takes a place`() {
        val tool = tools(emptySet()).first { it["name"] == "meditation" }
        val props = properties(tool)
        assertEquals(listOf("start", "stop", "pause", "resume", "status"), props["action"]!!["enum"])
        assertTrue("dormir" in (props["type"]!!["enum"] as List<*>))
        assertTrue("478" in (props["type"]!!["enum"] as List<*>))
        assertEquals("string", props["place"]!!["type"])
        assertTrue((tool["description"] as String).contains("place"))
    }

    @Test
    fun `initialize instructions are short`() {
        val text = TrekoToolCatalog.INITIALIZE_INSTRUCTIONS
        println("initialize instructions: ${text.length} chars")
        assertTrue("${text.length} chars", text.length <= 600)
        assertTrue(text.contains("home_assistant_call_service"))
    }

    @Test
    fun `strings are escaped`() {
        val parsed = tools(everySkill).first { it["name"] == "home_assistant_call_service" }
        val data = properties(parsed)["service_data"]!!["description"] as String
        assertTrue(data, data.contains("{\"entity_id\":\"climate.x\""))
    }

    /** Small strict JSON reader, enough to check what the catalog writes. */
    private class MiniJson(private val text: String) {
        private var i = 0

        fun parse(): Any? {
            val value = value()
            skipSpaces()
            check(i == text.length) { "trailing data at $i" }
            return value
        }

        private fun skipSpaces() {
            while (i < text.length && text[i].isWhitespace()) i++
        }

        private fun value(): Any? {
            skipSpaces()
            return when (val c = text[i]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else error("unexpected '$c' at $i")
            }
        }

        private fun literal(word: String, result: Any?): Any? {
            check(text.startsWith(word, i)) { "bad literal at $i" }
            i += word.length
            return result
        }

        private fun number(): Double {
            val start = i
            while (i < text.length && (text[i].isDigit() || text[i] in "-+.eE")) i++
            return text.substring(start, i).toDouble()
        }

        private fun string(): String {
            check(text[i] == '"')
            i++
            val sb = StringBuilder()
            while (true) {
                val c = text[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        when (val e = text[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'u' -> {
                                sb.append(text.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> error("bad escape at $i")
                        }
                    }
                    c < ' ' -> error("raw control character at $i")
                    else -> sb.append(c)
                }
            }
        }

        private fun array(): List<Any?> {
            i++
            val out = mutableListOf<Any?>()
            skipSpaces()
            if (text[i] == ']') {
                i++
                return out
            }
            while (true) {
                out += value()
                skipSpaces()
                when (text[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> error("expected , or ] at $i")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            skipSpaces()
            if (text[i] == '}') {
                i++
                return out
            }
            while (true) {
                skipSpaces()
                val key = string()
                check(key !in out) { "duplicate key $key" }
                skipSpaces()
                check(text[i++] == ':') { "expected : at $i" }
                out[key] = value()
                skipSpaces()
                when (text[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> error("expected , or } at $i")
                }
            }
        }
    }
}
