package org.futo.inputmethod.latin.uix.utils

/**
 * Picks the words sent to Whisper as its glossary hint.
 *
 * Whisper reads at most the last 224 tokens of the hint, and whisper.cpp drops the hint
 * altogether when it tokenizes to more than 1024 tokens. Sending the whole personal dictionary
 * either cut off the words at its start or, past about 1024 tokens, removed the hint without a
 * word. This keeps the hint inside the budget, most-used words first, and puts the words that
 * must always be there (such as spoken punctuation words) at the end, nearest the audio.
 *
 * Pure Kotlin, no Android types, so it is unit-tested on the JVM.
 */
object VoiceGlossary {
    /** Tokens for the words; 224 minus room for "(Glossary: " and ")" and some estimation slack. */
    const val TOKEN_BUDGET = 190

    /** Words Whisper should hear as words in spoken-punctuation mode, so "comma" isn't heard as "Kama". */
    val SPOKEN_PUNCTUATION_WORDS = listOf("exclamation point", "question mark", "colon", "period", "comma")

    /**
     * A conservative token count for [word] plus its ", " separator. Whisper's tokenizer averages
     * about four characters a token on English and fewer on names; this assumes three.
     */
    fun estimateTokens(word: String): Int = 1 + (word.length + 2) / 3

    /**
     * [dictionaryWords], most used first, cut to what fits in [budget] alongside [alwaysInclude].
     * The result runs from the least used kept word to the most used, then [alwaysInclude].
     */
    fun select(
        dictionaryWords: List<String>,
        alwaysInclude: List<String> = emptyList(),
        budget: Int = TOKEN_BUDGET,
    ): List<String> {
        val seen = HashSet<String>()
        val required = alwaysInclude.filter { it.isNotBlank() && seen.add(it.lowercase()) }
        var remaining = budget - required.sumOf { estimateTokens(it) }

        val kept = ArrayList<String>()
        for (word in dictionaryWords) {
            val w = word.trim()
            if (w.isEmpty() || w.lowercase() in seen) continue
            val cost = estimateTokens(w)
            if (cost > remaining) continue
            seen.add(w.lowercase())
            kept.add(w)
            remaining -= cost
        }
        return kept.asReversed() + required
    }
}
