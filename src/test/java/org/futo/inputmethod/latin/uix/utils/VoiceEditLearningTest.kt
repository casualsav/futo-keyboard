package org.futo.inputmethod.latin.uix.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceEditLearningTest {

    // Finding the voice text again

    @Test
    fun findsTheEditedTextBetweenItsNeighbours() {
        val commit = VoiceCommit("meet kamala at noon", before = "Hi team, ", after = " Thanks")
        val snapshot = "Hi team, meet Camilla at noon tomorrow. Thanks"
        assertEquals("meet Camilla at noon tomorrow.", VoiceEditDiff.locate(commit, snapshot))
    }

    @Test
    fun emptyFieldUsesTheWholeText() {
        val commit = VoiceCommit("hello there", before = "", after = "")
        assertEquals("hello there sal", VoiceEditDiff.locate(commit, "hello there sal"))
    }

    @Test
    fun givesUpWhenTheNeighboursAreGone() {
        val commit = VoiceCommit("hello there", before = "Dear Sam, ", after = "")
        assertNull(VoiceEditDiff.locate(commit, ""))
        assertNull(VoiceEditDiff.locate(commit, "something else entirely"))
    }

    // Comparing typed and edited words

    @Test
    fun aReplacedWordIsAFix() {
        val o = VoiceEditDiff.compare("meet kamala at noon", "meet Camilla at noon")!!
        assertEquals(listOf("kamala" to "Camilla"), o.fixes)
        assertEquals(4, o.words)
        assertEquals(1, o.changedWords)
    }

    @Test
    fun aCaseChangeIsAFix() {
        val o = VoiceEditDiff.compare("thanks sal", "thanks Sal")!!
        assertEquals(listOf("sal" to "Sal"), o.fixes)
    }

    @Test
    fun aSplitWordIsOneFix() {
        val o = VoiceEditDiff.compare("open get hub now", "open GitHub now")!!
        assertEquals(listOf("get hub" to "GitHub"), o.fixes)
    }

    @Test
    fun punctuationChangesAreNotFixes() {
        val o = VoiceEditDiff.compare("see you soon", "See you soon!")!!
        assertEquals(listOf("see" to "See"), o.fixes)
        val p = VoiceEditDiff.compare("see you soon", "see you, soon.")!!
        assertTrue(p.fixes.isEmpty())
        assertEquals(0, p.changedWords)
    }

    @Test
    fun typingOnAfterTheVoiceTextIsNotAFix() {
        val o = VoiceEditDiff.compare("call me later", "call me later tonight if you can")!!
        assertTrue(o.fixes.isEmpty())
        assertEquals(0, o.changedWords)
    }

    @Test
    fun aRemovedWordIsCountedButNotLearned() {
        val o = VoiceEditDiff.compare("so um the plan works", "so the plan works")!!
        assertTrue(o.fixes.isEmpty())
        assertEquals(1, o.changedWords)
    }

    @Test
    fun aMissedWordAddedInsideCounts() {
        val o = VoiceEditDiff.compare("send the file", "send me the file")!!
        assertTrue(o.fixes.isEmpty())
        assertEquals(1, o.changedWords)
    }

    @Test
    fun aRewriteIsCountedButNotLearned() {
        val o = VoiceEditDiff.compare("the quick brown fox jumps", "a slow red fox sat")!!
        assertTrue(o.fixes.isEmpty())
        assertEquals(4, o.changedWords)
    }

    @Test
    fun nothingInCommonIsIgnored() {
        assertNull(VoiceEditDiff.compare("hello there", "goodbye"))
    }

    // Learning and applying fixes

    private fun outcome(typed: String, edited: String) = VoiceEditDiff.compare(typed, edited)!!

    @Test
    fun aFixIsAppliedOnlyAfterItIsMadeTwice() {
        val book = CorrectionBook()
        book.learn(outcome("meet kamala at noon", "meet Camilla at noon"))
        assertEquals("ask kamala", book.apply("ask kamala"))
        book.learn(outcome("thanks kamala", "thanks Camilla"))
        assertEquals("ask Camilla", book.apply("ask kamala"))
        assertEquals(listOf("Camilla"), book.learnedWords())
    }

    @Test
    fun wordsLeftAloneMoreOftenThanFixedAreNotChanged() {
        val book = CorrectionBook()
        repeat(2) { book.learn(outcome("the plan", "The plan")) }
        repeat(3) { book.learn(outcome("read the plan", "read the plan")) }
        assertEquals("the plan", book.apply("the plan"))
    }

    @Test
    fun undoingALearnedFixForgetsIt() {
        val book = CorrectionBook()
        repeat(2) { book.learn(outcome("hi kamala", "hi Camilla")) }
        assertEquals("hi Camilla", book.apply("hi kamala"))
        book.learn(outcome("hi Camilla", "hi kamala"))
        assertEquals("hi kamala", book.apply("hi kamala"))
    }

    @Test
    fun appliesWholeWordsOnlyAndKeepsSentenceCase() {
        val book = CorrectionBook()
        repeat(2) { book.learn(outcome("get hub link", "github link")) }
        assertEquals("Github is up, see github.", book.apply("Get hub is up, see get hub."))
        assertEquals("forget hubris", book.apply("forget hubris"))
    }

    @Test
    fun editRateAndSavedFormRoundTrip() {
        val book = CorrectionBook()
        repeat(2) { book.learn(outcome("meet kamala at noon", "meet Camilla at noon")) }
        book.learn(outcome("all good here", "all good here"))
        assertEquals(2 to 11, book.editRate())

        val copy = CorrectionBook.parse(book.serialize())
        assertEquals(book.editRate(), copy.editRate())
        assertEquals("meet Camilla", copy.apply("meet kamala"))
        assertEquals(1, copy.learnedFixCount)
    }

    @Test
    fun brokenSavedLinesAreSkipped() {
        val book = CorrectionBook.parse("F\tonly two\nH\tx\t1\nK\ta\t2\nnonsense\n")
        assertEquals(0, book.learnedFixCount)
        assertEquals(0 to 0, book.editRate())
    }
}
