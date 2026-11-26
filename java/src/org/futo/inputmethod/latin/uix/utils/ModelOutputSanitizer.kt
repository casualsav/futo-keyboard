package org.futo.inputmethod.latin.uix.utils

import org.futo.inputmethod.latin.RichInputMethodManager

data class TextContext(
    val beforeCursor: CharSequence?,
    val afterCursor: CharSequence?
)

object ModelOutputSanitizer {
    private fun Char.isClosingBracket(): Boolean {
        return this in setOf(')', ']', '}', '>')
    }

    private fun Char.isOpeningBracket(): Boolean {
        return this in setOf('(', '[', '{', '<')
    }

    private fun Char.isPunctuation(): Boolean {
        return this in setOf('.', ',', '!', '?', ':', ';')
    }

    private fun String.endsWithWhitespaceOrNewline(): Boolean {
        return this.isNotEmpty() && this.last().isWhitespace()
    }

    private fun String.startsWithWhitespaceOrNewline(): Boolean {
        return this.isNotEmpty() && this.first().isWhitespace()
    }

    private val englishIRegex = Regex("^I\\s.*")
    private val beforeEndsPunctRegex = Regex(".*[.:?!]$")

    @JvmStatic
    @JvmOverloads
    fun sanitize(
        result: String,
        textContext: TextContext?,
        spokenPunctuationOnly: Boolean = false,
        // Live voice typing converts each piece as it comes; converting again would remove the marks.
        alreadyConverted: Boolean = false
    ): String {
        val text = if (spokenPunctuationOnly && !alreadyConverted) SpokenPunctuation.apply(result) else result
        if (textContext == null) {
            return text
        }

        val locale = RichInputMethodManager.getInstance().getCurrentSubtypeLocale()

        var trimmed = text.trim()
        if (trimmed.isEmpty()) return ""

        val before = (textContext.beforeCursor?.toString() ?: "")
            .split("\n")
            .lastOrNull() ?: ""
        val after = (textContext.afterCursor?.toString() ?: "")
            .split("\n")
            .firstOrNull() ?: ""

        // In spoken-punctuation mode every mark left was said aloud, so it stays even when
        // dictating in front of existing text. The trims below are for marks the model wrote.
        if (!spokenPunctuationOnly) {
            // Whisper tends to generate ellipsis at the end of phrases/sentences more often than appropriate. Which isn't so bad if it's at the very end but frustrating when inserting something inside of a sentence.
            if (trimmed.endsWith("...") && !after.isEmpty()) {
                trimmed = trimmed.dropLast(3)
            }

            if (trimmed.isEmpty()) return ""

            // Punctuation
            if (trimmed.last().isPunctuation() && !after.isEmpty()) {
                trimmed = trimmed.dropLast(1)
            }
        }

        if (trimmed.isEmpty()) return ""

        // Capitalization - whisper always capitalizes first character
        val beforeTrimmed = before.trimEnd()
        val isAcronym = trimmed.length >= 2 &&
                trimmed[0].isUpperCase() &&
                trimmed[1].isUpperCase()
        val isEnglishI = locale.language == "en" && ((trimmed.length == 1 && trimmed.first() == 'I') || englishIRegex.matches(trimmed))
        val needsLowercase = !beforeTrimmed.isEmpty() && !beforeEndsPunctRegex.matches(beforeTrimmed) && !isAcronym && !isEnglishI
        if (needsLowercase) {
            trimmed = trimmed.replaceFirstChar { it.lowercase(locale) }
        }

        // Leading and trailing spaces
        val needsLeadingSpace = before.isNotEmpty() && !before.endsWithWhitespaceOrNewline() &&
                !before.last().isOpeningBracket() &&
                before.last() != '—' &&
                before.last() != '"' &&
                before.last() != '*'
        val needsTrailingSpace = after.isNotEmpty() &&
                !after.startsWithWhitespaceOrNewline() &&
                !after.first().isPunctuation() &&
                !after.first().isClosingBracket() &&
                after.first() != '—' &&
                after.first() != '"' &&
                after.first() != '*'

        val prefix = if (needsLeadingSpace) " " else ""
        val suffix = if (needsTrailingSpace) " " else ""

        return prefix + trimmed + suffix
    }
}