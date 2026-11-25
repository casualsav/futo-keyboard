package org.futo.inputmethod.latin.uix.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceGlossaryTest {

    @Test
    fun smallDictionaryIsKeptWholeWithMostUsedLast() {
        assertEquals(
            listOf("Moto", "Sal", "GitHub"),
            VoiceGlossary.select(listOf("GitHub", "Sal", "Moto")),
        )
    }

    @Test
    fun spokenPunctuationWordsComeLast() {
        assertEquals(
            listOf("GitHub") + VoiceGlossary.SPOKEN_PUNCTUATION_WORDS,
            VoiceGlossary.select(listOf("GitHub"), VoiceGlossary.SPOKEN_PUNCTUATION_WORDS),
        )
    }

    @Test
    fun largeDictionaryIsCutToTheBudgetKeepingTheMostUsed() {
        val words = (1..2000).map { "word$it" }
        val picked = VoiceGlossary.select(words, VoiceGlossary.SPOKEN_PUNCTUATION_WORDS)
        assertTrue(picked.sumOf { VoiceGlossary.estimateTokens(it) } <= VoiceGlossary.TOKEN_BUDGET)
        assertTrue("word1" in picked)
        assertTrue("word2000" !in picked)
        assertEquals(VoiceGlossary.SPOKEN_PUNCTUATION_WORDS, picked.takeLast(VoiceGlossary.SPOKEN_PUNCTUATION_WORDS.size))
    }

    @Test
    fun duplicatesAndBlanksAreDropped() {
        assertEquals(listOf("Sal", "comma"), VoiceGlossary.select(listOf("Comma", " ", "Sal", "sal"), listOf("comma")))
    }

    @Test
    fun aWordTooLongForWhatIsLeftIsSkippedNotTheRest() {
        val long = "x".repeat(600)
        assertEquals(listOf("Sal"), VoiceGlossary.select(listOf(long, "Sal")))
    }
}
