package org.futo.inputmethod.latin.uix.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTextRecoveryTest {
    @Test
    fun shownTextStillInTheFieldIsFound() {
        assertEquals("Hello there. How are",
            LiveTextRecovery.leftInField("Dear Sam, Hello there. How are", "Hello there. How are"))
    }

    @Test
    fun shownTextTheAppClearedIsNotFound() {
        assertEquals("", LiveTextRecovery.leftInField("Dear Sam, ", "Hello there. How are"))
    }

    @Test
    fun onlyTheNewWordsAreWritten() {
        assertEquals(" you doing?",
            LiveTextRecovery.remainder("Hello there. How are", "Hello there. How are you doing?"))
    }

    @Test
    fun aRewordedWordStillSkipsTheWordsAlreadyThere() {
        assertEquals(" and back.",
            LiveTextRecovery.remainder("I went to the stor", "I went to the store and back."))
    }

    @Test
    fun aMarkRightAfterIsWrittenWithoutASpace() {
        assertEquals(".", LiveTextRecovery.remainder("I went home", "I went home."))
    }

    @Test
    fun nothingLeftToWrite() {
        assertEquals("", LiveTextRecovery.remainder("I went home.", "I went home."))
        assertEquals("", LiveTextRecovery.remainder("one two three", "one two"))
    }

    @Test
    fun nothingWrittenMeansAllOfIt() {
        assertEquals("Hello.", LiveTextRecovery.remainder("", "Hello."))
    }
}
