package org.futo.inputmethod.latin.uix.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenPunctuationTest {

    @Test
    fun spokenPunctuationBecomesMarks() {
        assertEquals("hi, how are you?", SpokenPunctuation.apply("hi comma how are you question mark"))
    }

    @Test
    fun modelPunctuationAroundSpokenWordsIsDropped() {
        assertEquals("Hi, how are you?", SpokenPunctuation.apply("Hi, comma, how are you? Question mark."))
    }

    @Test
    fun plainSpeechGetsNoPunctuation() {
        assertEquals(
            "So I was thinking we could meet up around 6 if you are not too tired",
            SpokenPunctuation.apply("So I was thinking we could meet up around 6, if you are not too tired."),
        )
    }

    @Test
    fun capitalLeftByARemovedFullStopIsLowered() {
        assertEquals(
            "Make sure the feature to remove it is a setting",
            SpokenPunctuation.apply("Make sure the. Feature to remove it is a setting."),
        )
    }

    @Test
    fun spokenPeriodStartsASentence() {
        assertEquals("Pick up milk. Also grab bread", SpokenPunctuation.apply("Pick up milk period. Also grab bread."))
    }

    @Test
    fun pronounIAndAcronymsKeepTheirCapitals() {
        assertEquals("It works I think NASA said so", SpokenPunctuation.apply("It works. I think. NASA said so."))
    }

    @Test
    fun numbersContractionsAndHyphensKeepTheirMarks() {
        assertEquals(
            "It's 3.5 km at 6:30 a well-known route",
            SpokenPunctuation.apply("It's 3.5 km, at 6:30 - a well-known route."),
        )
    }

    @Test
    fun commaHeardAsKamaBecomesAComma() {
        assertEquals(
            "it starts transcribing, and we shortened it, how long",
            SpokenPunctuation.apply("it starts transcribing Kama and we shortened it kama how long"),
        )
    }

    @Test
    fun comaAndKarmaStayWords() {
        assertEquals("good karma after the coma", SpokenPunctuation.apply("good karma after the coma"))
    }

    @Test
    fun punctuationWordsUsedAsNounsStayWords() {
        assertEquals("It was a period of time", SpokenPunctuation.apply("It was a period of time."))
        assertEquals("The free trial period ends Monday", SpokenPunctuation.apply("The free trial period ends Monday."))
        assertEquals("My colon is fine", SpokenPunctuation.apply("My colon is fine."))
        assertEquals("Is there a question mark at the end", SpokenPunctuation.apply("Is there a question mark at the end?"))
        assertEquals("A comma goes here", SpokenPunctuation.apply("A comma goes here."))
    }

    @Test
    fun punctuationWordsSaidAsCommandsStillBecomeMarks() {
        assertEquals("see you then. Bye", SpokenPunctuation.apply("see you then period bye"))
        assertEquals("first, second", SpokenPunctuation.apply("first comma second"))
        assertEquals("Are you coming?", SpokenPunctuation.apply("Are you coming question mark"))
    }
}
