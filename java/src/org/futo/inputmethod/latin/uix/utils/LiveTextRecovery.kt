package org.futo.inputmethod.latin.uix.utils

/**
 * Live voice typing writes into the text field as the user talks. Some apps restart the text
 * field while that happens, which ends the keyboard's hold on it: the words shown so far stay in
 * the field as plain text, and later writes would go nowhere. Voice typing then takes the field
 * again and writes only what isn't there yet, so nothing is lost and nothing is typed twice.
 *
 * Pure Kotlin, no Android types, so it is unit-tested on the JVM.
 */
object LiveTextRecovery {
    private val WORD = Regex("""[\p{L}\p{N}'’]+""")

    /**
     * What of [shown] is still in the field after the hold on it ended: [shown] itself when the
     * text before the cursor ends with it, else nothing (the app cleared it, so all of it is
     * written again).
     */
    fun leftInField(beforeCursor: String, shown: String): String {
        val s = shown.trimEnd()
        if (s.isBlank()) return ""
        return if (beforeCursor.trimEnd().endsWith(s)) s else ""
    }

    /**
     * The part of [target] still to write when [written] is already in the field. When [target]
     * starts with [written] that's the rest; otherwise (the final pass reworded a word) it skips
     * as many words as [written] has. Starts with a space where one is needed.
     */
    fun remainder(written: String, target: String): String {
        if (written.isBlank()) return target
        val cleanCut = target.startsWith(written) && (target.length == written.length ||
            !target[written.length].isLetterOrDigit() || !written.last().isLetterOrDigit())
        val rest = if (cleanCut) {
            target.substring(written.length)
        } else {
            val n = WORD.findAll(written).count()
            val match = WORD.findAll(target).drop(n - 1).firstOrNull()
            if (n == 0 || match == null) "" else target.substring(match.range.last + 1)
        }
        val trimmed = rest.trimStart()
        if (trimmed.isEmpty()) return ""
        val needsSpace = trimmed.first() !in ".,;:!?)]}\"”'’" && !written.last().isWhitespace()
        return if (needsSpace) " $trimmed" else trimmed
    }
}
