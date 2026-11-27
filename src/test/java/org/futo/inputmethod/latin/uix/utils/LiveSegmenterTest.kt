package org.futo.inputmethod.latin.uix.utils

import org.futo.voiceinput.shared.live.LiveSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSegmenterTest {
    private fun spoken() = LiveSegmenter { SpokenPunctuation.apply(it) }

    @Test
    fun pieceEndingOnASpokenMarkIsKeptAndTheAudioMovesOn() {
        val s = spoken()
        val r = s.onPiece("I went to the store, period.")
        assertTrue(r.advanceAudioStart)
        assertEquals("I went to the store.", s.committed)
        assertEquals("", s.pending)
    }

    @Test
    fun pieceWithoutAMarkIsOnlyAPreview() {
        val s = spoken()
        val r = s.onPiece("I went to the store")
        assertFalse(r.advanceAudioStart)
        assertEquals("", s.committed)
        assertEquals("I went to the store", s.pending)
        assertEquals("I went to the store", s.display())
    }

    @Test
    fun wordsAfterTheLastMarkWaitAndAreNotTypedTwice() {
        val s = spoken()
        // First pause: the user said one sentence and started the next.
        assertFalse(s.onPiece("Hello there period. How are").advanceAudioStart)
        assertEquals("Hello there.", s.committed)
        assertEquals("How are", s.pending)
        assertEquals("Hello there. How are", s.display())

        // Second pause: the same audio again plus the rest. The kept mark is skipped.
        assertTrue(s.onPiece("Hello there period. How are you question mark?").advanceAudioStart)
        assertEquals("Hello there. How are you?", s.committed)
        assertEquals("", s.pending)
    }

    @Test
    fun aPieceAfterACommaContinuesTheSentenceInLowercase() {
        val s = spoken()
        s.onPiece("I went to the store comma")
        s.onPiece("And bought milk period")
        assertEquals("I went to the store, and bought milk.", s.committed)
    }

    @Test
    fun aPieceAfterASentenceEndStartsWithACapital() {
        val s = spoken()
        s.onPiece("Done period")
        s.onPiece("next thing period")
        assertEquals("Done. Next thing.", s.committed)
    }

    @Test
    fun iAndAcronymsKeepTheirCapitals() {
        val s = spoken()
        s.onPiece("Well comma")
        s.onPiece("I think so comma")
        s.onPiece("NASA said period")
        assertEquals("Well, I think so, NASA said.", s.committed)
    }

    @Test
    fun aNameAfterACommaKeepsItsCapital() {
        val s = spoken()
        s.onPiece("Thanks comma")
        s.onPiece("Sal will send it period")
        assertEquals("Thanks, Sal will send it.", s.committed)
    }

    @Test
    fun commonWordsAfterACommaAreLowercased() {
        assertEquals("Yes, then we left", LiveSegmenter.join("Yes,", "Then we left"))
        assertEquals("Yes, it's fine", LiveSegmenter.join("Yes,", "It's fine"))
        assertEquals("Yes, Moto said", LiveSegmenter.join("Yes,", "Moto said"))
    }

    @Test
    fun finalPieceKeepsEverything() {
        val s = spoken()
        s.onPiece("First part period. Second")
        s.onPiece("First part period. Second part without a mark", final = true)
        assertEquals("First part. Second part without a mark", s.committed)
    }

    @Test
    fun keepPendingUsesTheLatestTranscriptWithoutRunningAgain() {
        val s = spoken()
        s.onPiece("Thanks period. See you soon")
        s.keepPending()
        assertEquals("Thanks. See you soon", s.committed)
        assertEquals("", s.pending)
    }

    @Test
    fun numbersAreNotMarks() {
        assertEquals(-1, LiveSegmenter.lastMarkEnd("it costs 3.50 at 6:30"))
        assertEquals(0, LiveSegmenter.countMarks("6,000 people"))
    }

    @Test
    fun skipKeptUsesMarksThenWords() {
        assertEquals("b c", LiveSegmenter.skipKept("a, b c", "a,"))
        // The rerun heard the spoken comma as a word: skip by word count instead. The misheard
        // word comes through, but nothing said after it is lost.
        assertEquals("come and bought milk.", LiveSegmenter.skipKept("I went to the store come and bought milk.", "I went to the store,"))
        assertEquals("", LiveSegmenter.skipKept("one two", "one, two, three,"))
    }

    @Test
    fun aRerunThatLosesAMarkStillTypesTheRest() {
        val s = spoken()
        s.onPiece("I went to the store comma and")
        assertEquals("I went to the store,", s.committed)
        assertTrue(s.onPiece("I went to the store come and bought milk period").advanceAudioStart)
        assertEquals("I went to the store, come and bought milk.", s.committed)
    }

    @Test
    fun anEmptyPieceKeepsThePreview() {
        val s = spoken()
        s.onPiece("Hello there period. How are")
        assertFalse(s.onPiece("").advanceAudioStart)
        assertEquals("Hello there. How are", s.display())
    }

    @Test
    fun withoutSpokenPunctuationWhispersOwnMarksAreUsed() {
        val s = LiveSegmenter { it }
        assertTrue(s.onPiece("Hello there.").advanceAudioStart)
        assertFalse(s.onPiece("How are you").advanceAudioStart)
        assertEquals("Hello there. How are you", s.display())
    }

    @Test
    fun aMarkAtTheStartOfAPieceJoinsWithoutASpace() {
        assertEquals("hello,", LiveSegmenter.join("hello", ","))
    }
}
