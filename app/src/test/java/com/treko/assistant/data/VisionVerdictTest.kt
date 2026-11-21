package com.treko.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionVerdictTest {

    @Test
    fun parsesACleanVerdict() {
        val v = parseVisionVerdict(
            """{"confirmado": true, "personas": 1, "vehiculos": 0, "paquete": false, "donde": "frente a la puerta",
              "actividad": "parada en la entrada", "nivel": "atencion", "motivo": "Persona parada junto a la puerta",
              "aviso": "Hay una persona parada frente a la puerta de Entrada."}"""
        )
        assertNotNull(v)
        v!!
        assertEquals(true, v.confirmed)
        assertEquals(1, v.people)
        assertEquals(0, v.vehicles)
        assertFalse(v.packageSeen)
        assertEquals("frente a la puerta", v.where)
        assertEquals("parada en la entrada", v.activity)
        assertEquals(VisionLevel.ATTENTION, v.level)
        assertEquals("Hay una persona parada frente a la puerta de Entrada.", v.spokenAlert)
        assertEquals("person", v.mainObject)
    }

    @Test
    fun toleratesFencesPreambleAccentsAndTrailingCommas() {
        val v = parseVisionVerdict(
            "Claro, aquí está:\n```json\n{\n \"confirmado\": verdadero,\n \"vehículos\": 2,\n \"nivel\": \"Atención\",\n" +
                " \"aviso\": \"Hay dos autos \\\"estacionados\\\" en la cochera.\",\n}\n```\nEspero que sirva."
        )
        assertNotNull(v)
        assertEquals(true, v!!.confirmed)
        assertEquals(2, v.vehicles)
        assertEquals(VisionLevel.ATTENTION, v.level)
        assertEquals("Hay dos autos \"estacionados\" en la cochera.", v.spokenAlert)
    }

    @Test
    fun acceptsBareLevelsEnglishWordsAndBracesInsideStrings() {
        val bare = parseVisionVerdict("{'nivel': urgente, 'motivo': 'trepa {la barda}'}")
        assertEquals(VisionLevel.URGENT, bare!!.level)
        assertEquals("trepa {la barda}", bare.reason)
        assertEquals(VisionLevel.NONE, parseVisionVerdict("""{"nivel": "none"}""")!!.level)
        assertEquals(VisionLevel.INFO, parseVisionVerdict("""{"nivel": "informativo"}""")!!.level)
        assertEquals(VisionLevel.ATTENTION, parseVisionVerdict("""{"nivel": "attention"}""")!!.level)
    }

    @Test
    fun derivesACautiousLevelWhenItIsMissing() {
        assertEquals(VisionLevel.NONE, parseVisionVerdict("""{"confirmado": false, "personas": 1}""")!!.level)
        assertEquals(VisionLevel.INFO, parseVisionVerdict("""{"confirmado": true, "personas": 1}""")!!.level)
        assertEquals(VisionLevel.ATTENTION, parseVisionVerdict("""{"paquete": true}""")!!.level)
    }

    @Test
    fun noVerdictWithoutJsonOrKnownKeys() {
        assertNull(parseVisionVerdict(null))
        assertNull(parseVisionVerdict(""))
        assertNull(parseVisionVerdict("[NORMAL]"))
        assertNull(parseVisionVerdict("Se ve una persona en la entrada."))
        assertNull(parseVisionVerdict("""{"foo": "bar"}"""))
    }

    @Test
    fun cutOffAnswerStillReadsTheFieldsItHas() {
        val v = parseVisionVerdict("""{"confirmado": true, "personas": 1, "nivel": "info", "aviso": "Una persona pasa por la ban""")
        assertNotNull(v)
        assertEquals(VisionLevel.INFO, v!!.level)
        assertEquals("", v.spokenAlert)
    }

    @Test
    fun levelDecidesTheAlertAndInfoOnlyCountsWhileWatching() {
        fun verdict(level: VisionLevel) = VisionVerdict(true, 1, 0, false, "", "", level, "", "")
        assertTrue(verdict(VisionLevel.URGENT).raisesAlert(watchRequested = false))
        assertTrue(verdict(VisionLevel.ATTENTION).raisesAlert(watchRequested = false))
        assertFalse(verdict(VisionLevel.INFO).raisesAlert(watchRequested = false))
        assertTrue(verdict(VisionLevel.INFO).raisesAlert(watchRequested = true))
        assertFalse(verdict(VisionLevel.NONE).raisesAlert(watchRequested = true))
    }

    @Test
    fun describeIsReadableNeverRawJson() {
        val v = VisionVerdict(true, 1, 0, false, "en la banqueta", "caminando de paso", VisionLevel.INFO, "persona caminando de paso", "")
        assertEquals("Caminando de paso en la banqueta. Persona caminando de paso.", v.describe())
        val onlyAviso = VisionVerdict(null, 0, 0, false, "", "", VisionLevel.NONE, "", "Todo tranquilo en el patio")
        assertEquals("Todo tranquilo en el patio.", onlyAviso.describe())
    }

    @Test
    fun limitWordsCutsAtWordBoundaries() {
        assertEquals("Hay una persona.", SpokenText.limitWords("Hay una persona", 15))
        assertEquals("uno dos tres.", SpokenText.limitWords("uno dos tres, cuatro cinco", 3))
        assertEquals("Ya.", SpokenText.limitWords("  Ya.  ", 5))
        assertEquals("", SpokenText.limitWords("   ", 5))
    }

    @Test
    fun stripEmojiRemovesHabitIconsAndSymbols() {
        assertEquals("Meditación, Caminar 30 min.", SpokenText.stripEmoji("🧘 Meditación, 🚶 Caminar 30 min."))
        assertEquals("Agua: 3 de 8 vasos", SpokenText.stripEmoji("💧 Agua: 3 de 8 vasos ✅"))
        assertEquals("Sin emoji, ¿listo?", SpokenText.stripEmoji("Sin emoji, ¿listo?"))
    }

    @Test
    fun fallbackLineNeverReturnsRawText() {
        val empty = parseVisionVerdict("""{"confirmado": false, "personas": 0, "donde": "", "actividad": "", "nivel": "ninguno", "motivo": "", "aviso": ""}""")!!
        assertEquals("", empty.describe())
        assertEquals("No se ve nada fuera de lo normal.", empty.fallbackLine())

        val people = parseVisionVerdict("""{"personas": 2, "vehiculos": 1, "nivel": "info", "aviso": ""}""")!!
        assertEquals("Hay 2 personas, hay un vehículo.", people.fallbackLine())

        val unknown = parseVisionVerdict("""{"confirmado": true, "nivel": "urgente", "aviso": ""}""")!!
        assertEquals("Hay algo que conviene revisar.", unknown.fallbackLine())

        val described = parseVisionVerdict("""{"personas": 1, "donde": "en la banqueta", "actividad": "caminando", "nivel": "info", "aviso": ""}""")!!
        assertEquals(described.describe(), described.fallbackLine())
    }

    @Test
    fun answerCutOffBeforeAvisoStillParses() {
        val cut = parseVisionVerdict("""{"confirmado": true, "personas": 1, "vehiculos": 0, "paquete": false, "nivel": "atencion", "motivo": "una persona en la entr""")!!
        assertEquals(VisionLevel.ATTENTION, cut.level)
        assertEquals("", cut.spokenAlert)
        assertFalse(cut.fallbackLine().contains('{'))
    }
}
