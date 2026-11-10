package com.treko.core.kws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream

class KwsTokenizerTest {

    private lateinit var tokenizer: KwsTokenizer

    @Before
    fun setUp() {
        val tokensFile = File("src/main/assets/models/en/tokens.txt")
        assertTrue("tokens.txt must exist at ${tokensFile.absolutePath}", tokensFile.exists())
        tokenizer = FileInputStream(tokensFile).use { KwsTokenizer.loadFromStream(it) }
    }

    @Test
    fun testTrekoTokenization() {
        val tokens = tokenizer.tokenizePhrase("Treko")
        // "Treko" -> ▁T RE K O
        assertEquals(listOf("\u2581T", "RE", "K", "O"), tokens)

        val formatted = tokenizer.formatKeywordLine("Treko", 3.0f, 0.05f)
        assertEquals("\u2581T RE K O :3.0 #0.05 @TREKO", formatted)
    }

    @Test
    fun testHeyTrekoTokenization() {
        val tokens = tokenizer.tokenizePhrase("Hey Treko")
        // "Hey Treko" -> ▁HE Y ▁T RE K O
        assertEquals(listOf("\u2581HE", "Y", "\u2581T", "RE", "K", "O"), tokens)

        val formatted = tokenizer.formatKeywordLine("Hey Treko", 3.5f, 0.08f)
        assertEquals("\u2581HE Y \u2581T RE K O :3.5 #0.08 @HEY_TREKO", formatted)
    }

    @Test
    fun testFallbackForMissingSpacePrefixLetters() {
        // Letters L, M, N, U, V, X, Y, Z without space prefix in tokens.txt
        // Luna: ▁ LU N A
        val lunaTokens = tokenizer.tokenizePhrase("Luna")
        assertEquals(listOf("\u2581", "LU", "N", "A"), lunaTokens)

        // Lucy: ▁ LU C Y
        val lucyTokens = tokenizer.tokenizePhrase("Lucy")
        assertEquals(listOf("\u2581", "LU", "C", "Y"), lucyTokens)

        // Jarvis: ▁JA R VI S (JA has \u2581JA)
        val jarvisTokens = tokenizer.tokenizePhrase("Jarvis")
        assertEquals(listOf("\u2581JA", "R", "VI", "S"), jarvisTokens)

        // Alexa: ▁A LE X A
        val alexaTokens = tokenizer.tokenizePhrase("Alexa")
        assertEquals(listOf("\u2581A", "LE", "X", "A"), alexaTokens)

        // Max: ▁MA X (\u2581MA exists, X is separate)
        val maxTokens = tokenizer.tokenizePhrase("Max")
        assertEquals(listOf("\u2581MA", "X"), maxTokens)

        // Nova: ▁NO V A (▁NO exists in vocabulary)
        val novaTokens = tokenizer.tokenizePhrase("Nova")
        assertEquals(listOf("\u2581NO", "V", "A"), novaTokens)

        // Nico: ▁ N IC O (No ▁NI or ▁N exists, falls back to independent space token ▁)
        val nicoTokens = tokenizer.tokenizePhrase("Nico")
        assertEquals(listOf("\u2581", "N", "IC", "O"), nicoTokens)

        // Victor: ▁VI C T OR (\u2581VI exists)
        val victorTokens = tokenizer.tokenizePhrase("Victor")
        assertEquals(listOf("\u2581VI", "C", "T", "OR"), victorTokens)

        // Zara: ▁ Z AR A (Z has no \u2581Z, so emits independent space token \u2581, Z, AR, A)
        val zaraTokens = tokenizer.tokenizePhrase("Zara")
        assertEquals(listOf("\u2581", "Z", "AR", "A"), zaraTokens)
    }

    @Test
    fun testEmptyAndWhitespaceInput() {
        assertTrue(tokenizer.tokenizePhrase("").isEmpty())
        assertTrue(tokenizer.tokenizePhrase("   ").isEmpty())
        assertEquals("", tokenizer.formatKeywordLine("", 3.0f, 0.05f))
        assertEquals("", tokenizer.formatKeywordLine("   ", 3.0f, 0.05f))
    }

    @Test
    fun testPunctuationAndSpecialCharacters() {
        val tokens = tokenizer.tokenizePhrase("Hey, Jarvis!")
        assertEquals(listOf("\u2581HE", "Y", "\u2581JA", "R", "VI", "S"), tokens)

        val formatted = tokenizer.formatKeywordLine("Hey, Jarvis!", 2.5f, 0.03f)
        assertEquals("\u2581HE Y \u2581JA R VI S :2.5 #0.03 @HEY_JARVIS", formatted)
    }

    @Test
    fun testFormatKeywordLineCustomLabel() {
        val line = tokenizer.formatKeywordLine("Jarvis", 4.0f, 0.10f, "MY_JARVIS")
        assertEquals("\u2581JA R VI S :4.0 #0.10 @MY_JARVIS", line)
    }

    @Test
    fun testBoostAndThresholdRanges() {
        val minBoostMinThresh = tokenizer.formatKeywordLine("Treko", 1.0f, 0.01f)
        assertEquals("\u2581T RE K O :1.0 #0.01 @TREKO", minBoostMinThresh)

        val maxBoostMaxThresh = tokenizer.formatKeywordLine("Treko", 5.0f, 0.20f)
        assertEquals("\u2581T RE K O :5.0 #0.20 @TREKO", maxBoostMaxThresh)
    }

    @Test
    fun testDiacriticsAndAccentedNames() {
        // "María" -> "MARIA" -> ▁MAR IA
        val mariaTokens = tokenizer.tokenizePhrase("María")
        assertEquals(listOf("\u2581MAR", "IA"), mariaTokens)
        val formattedMaria = tokenizer.formatKeywordLine("María", 3.0f, 0.05f)
        assertEquals("\u2581MAR IA :3.0 #0.05 @MARIA", formattedMaria)

        // "Álex" -> "ALEX" -> ▁A LE X
        val alexTokens = tokenizer.tokenizePhrase("Álex")
        assertEquals(listOf("\u2581A", "LE", "X"), alexTokens)
        val formattedAlex = tokenizer.formatKeywordLine("Álex", 3.2f, 0.04f)
        assertEquals("\u2581A LE X :3.2 #0.04 @ALEX", formattedAlex)

        // "Mónica" -> "MONICA" -> ▁MO N IC A
        val monicaTokens = tokenizer.tokenizePhrase("Mónica")
        assertEquals(listOf("\u2581MO", "N", "IC", "A"), monicaTokens)

        // "José" -> "JOSE" -> ▁JO SE
        val joseTokens = tokenizer.tokenizePhrase("José")
        assertEquals(listOf("\u2581JO", "SE"), joseTokens)

        // "Niño" -> "NINO" -> ▁ N IN O
        val ninoTokens = tokenizer.tokenizePhrase("Niño")
        assertEquals(listOf("\u2581", "N", "IN", "O"), ninoTokens)
    }

    @Test
    fun testDigitExpansion() {
        // "Treko 2" -> "TREKO TWO" -> ▁T RE K O ▁TWO
        val tokens = tokenizer.tokenizePhrase("Treko 2")
        assertEquals(listOf("\u2581T", "RE", "K", "O", "\u2581TWO"), tokens)

        val formatted = tokenizer.formatKeywordLine("Treko 2", 3.0f, 0.05f)
        assertEquals("\u2581T RE K O \u2581TWO :3.0 #0.05 @TREKO_2", formatted)
    }

    @Test
    fun testSpanishPhrases() {
        // "Oye Treko" -> ▁O Y E ▁T RE K O
        val tokens = tokenizer.tokenizePhrase("Oye Treko")
        assertEquals(listOf("\u2581O", "Y", "E", "\u2581T", "RE", "K", "O"), tokens)

        val formatted = tokenizer.formatKeywordLine("Oye Treko", 3.0f, 0.05f)
        assertEquals("\u2581O Y E \u2581T RE K O :3.0 #0.05 @OYE_TREKO", formatted)
    }
}
