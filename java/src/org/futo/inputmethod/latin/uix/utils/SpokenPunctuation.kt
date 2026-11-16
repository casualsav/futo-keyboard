package org.futo.inputmethod.latin.uix.utils

/**
 * Spoken-punctuation mode for voice input: the text carries only the punctuation the user
 * says aloud. Whisper punctuates on its own; [apply] removes every mark it wrote, then turns
 * spoken words ("comma", "period", "question mark", …) into marks.
 *
 * Pure Kotlin, no Android types, so it is unit-tested on the JVM.
 */
object SpokenPunctuation {
    /** Spoken words and the mark each becomes; longer phrases are matched first. */
    private val SPOKEN = listOf(
        "exclamation point" to "!",
        "exclamation mark" to "!",
        "question mark" to "?",
        "full stop" to ".",
        "semicolon" to ";",
        "period" to ".",
        "comma" to ",",
        // Whisper often hears "comma" as "Kama"; "coma" and "karma" are real words, so they stay words.
        "kama" to ",",
        "kamma" to ",",
        "colon" to ":",
    )

    private val SPOKEN_RE = Regex(
        SPOKEN.joinToString("|", prefix = """\s*(?<![\p{L}\p{N}'])(""", postfix = """)(?![\p{L}\p{N}'])""") { (words, _) ->
            words.split(' ').joinToString("""\s+""")
        },
        RegexOption.IGNORE_CASE,
    )
    private val MARK_BY_WORDS = SPOKEN.associate { (words, mark) -> words to mark }

    /** A sentence end the model wrote, and the word after it. */
    private val MODEL_SENTENCE_END_RE = Regex("""[.!?…]+["”»]*\s+(\S+)""")

    /** Marks the model writes; `.`, `,` and `:` between two digits are kept (3.5, 6,000, 6:30). */
    private val MODEL_PUNCTUATION_RE =
        Regex("""(?<!\d)[.,:]|[.,:](?!\d)|[;!?¿¡…"“”„«»]|(?<=\s)[-–—]+(?=\s|$)|^[-–—]+(?=\s)""")

    private val MULTI_SPACE_RE = Regex("""\s{2,}""")
    private val SPACE_BEFORE_MARK_RE = Regex("""\s+([.,;:!?])""")
    private val SENTENCE_START_RE = Regex("""([.!?]\s+)(\p{Ll})""")
    private val PRONOUN_I_RE = Regex("""^I(?:'(?:m|ll|ve|d))?$""")

    fun apply(text: String): String {
        // A capital the model wrote only because it ended a sentence there goes with that mark.
        val unCapitalised = MODEL_SENTENCE_END_RE.replace(text) { m ->
            val word = m.groupValues[1]
            m.value.dropLast(word.length) + lowerUnlessKept(word)
        }
        val stripped = MODEL_PUNCTUATION_RE.replace(unCapitalised, "").replace(MULTI_SPACE_RE, " ").trim()
        val marked = SPOKEN_RE.replace(stripped) { m ->
            " " + MARK_BY_WORDS.getValue(m.groupValues[1].lowercase().split(Regex("""\s+""")).joinToString(" "))
        }
        return marked
            .replace(SPACE_BEFORE_MARK_RE, "$1")
            .replace(MULTI_SPACE_RE, " ")
            .trim()
            .replace(SENTENCE_START_RE) { it.groupValues[1] + it.groupValues[2].uppercase() }
    }

    /** [word] with its first letter lowercased, unless it is "I", a contraction of it, or all caps. */
    private fun lowerUnlessKept(word: String): String {
        val core = word.trimEnd { !it.isLetterOrDigit() }
        if (core.isEmpty() || !core.first().isUpperCase()) return word
        if (PRONOUN_I_RE.matches(core)) return word
        if (core.length > 1 && core.none { it.isLowerCase() }) return word
        return word.replaceFirstChar { it.lowercaseChar() }
    }
}
