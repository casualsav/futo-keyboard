package org.futo.inputmethod.latin.uix.utils

/**
 * Learning from the edits the user makes to voice-typed text, so the same mistake isn't typed
 * twice. Everything here stays on the phone: the edits and the fixes learned from them live only
 * in the app's private storage.
 *
 * The flow:
 * 1. When voice text is typed, [VoiceCommit] keeps the text and a little of what was around it.
 * 2. As the user moves around the field, [VoiceEditDiff.locate] finds what that text now reads.
 * 3. When the user leaves it (or starts the next voice input), [VoiceEditDiff.compare] lines up
 *    the typed and the final words and pulls out each small fix, such as "Kamala" -> "Camilla".
 * 4. [CorrectionBook] counts the fixes. A fix made at least twice, and more often than the same
 *    words were left alone, is applied to new voice text from then on, and its words join the
 *    glossary hint so Whisper itself starts to hear them.
 *
 * Pure Kotlin, no Android types, so it is unit-tested on the JVM.
 */

/** Voice text as it was typed, with the field's text just before and after it at that moment. */
data class VoiceCommit(val text: String, val before: String, val after: String)

/** What the user did to one piece of voice text. */
data class VoiceEditOutcome(
    /** Words in the voice text as typed. */
    val words: Int,
    /** Of those, words the user changed or removed, plus words they added inside it. */
    val changedWords: Int,
    /** Small fixes worth learning, as (typed, corrected) phrases of one to three words. */
    val fixes: List<Pair<String, String>>,
    /** The typed words the user left as they were, lowercased, for counting fixes not made. */
    val keptWords: List<String>,
)

object VoiceEditDiff {
    /** Characters of surrounding text used to find the voice text again. */
    const val ANCHOR_LENGTH = 24

    /** Longest phrase, in words, learned as one fix. */
    const val MAX_FIX_WORDS = 3

    /** Above this many words, a piece of voice text isn't compared (too slow, and rarely a fix). */
    const val MAX_WORDS = 300

    private val WORD = Regex("""[\p{L}\p{N}][\p{L}\p{N}'’\-]*""")

    data class Token(val raw: String, val key: String)

    fun tokens(text: String): List<Token> =
        WORD.findAll(text).map { Token(it.value, it.value.lowercase()) }.toList()

    /**
     * The part of [snapshot] (the field's text around the cursor now) that stands where
     * [commit]'s text was typed, or null when the surrounding text can't be found, for example
     * because the user scrolled far away or cleared the field.
     */
    fun locate(commit: VoiceCommit, snapshot: String): String? {
        val pre = commit.before.takeLast(ANCHOR_LENGTH)
        val start = if (pre.isEmpty()) {
            0
        } else {
            val i = snapshot.indexOf(pre)
            if (i < 0) return null
            i + pre.length
        }

        val post = commit.after.take(ANCHOR_LENGTH)
        val end = if (post.isEmpty()) {
            snapshot.length
        } else {
            val i = snapshot.indexOf(post, start)
            if (i < 0) return null
            i
        }

        val region = snapshot.substring(start, end)
        // Far longer than what was typed means the anchors matched the wrong place, or the user
        // wrote a lot more after it; either way it can't be compared word for word.
        if (region.length > commit.text.length * 3 + 200) return null
        return region
    }

    /**
     * Lines up the words of [typed] and [edited] and says what changed, or null when they have
     * too little in common to be the same text (the user rewrote it, or the match was wrong).
     */
    fun compare(typed: String, edited: String): VoiceEditOutcome? {
        val a = tokens(typed)
        val b = tokens(edited)
        if (a.isEmpty() || a.size > MAX_WORDS || b.size > MAX_WORDS * 2) return null

        val matches = lcs(a, b)
        if (matches.isEmpty()) return null

        val fixes = ArrayList<Pair<String, String>>()
        val kept = ArrayList<String>()
        var changed = 0

        fun gap(aFrom: Int, aTo: Int, bFrom: Int, bTo: Int, interior: Boolean, trailing: Boolean) {
            val aLen = aTo - aFrom
            var bLen = bTo - bFrom
            if (aLen == 0) {
                // Words before the voice text or after it are the user's own typing, not a fix;
                // words added inside it are words the model missed.
                if (interior) changed += bLen
                return
            }
            changed += aLen
            if (bLen == 0) return // A removed word isn't learned: it may have become punctuation.
            if (trailing && bLen > aLen) bLen = aLen // The rest is likely the user typing on.
            if (aLen <= MAX_FIX_WORDS && bLen <= MAX_FIX_WORDS) {
                val from = a.subList(aFrom, aTo).joinToString(" ") { it.raw }
                val to = b.subList(bFrom, bFrom + bLen).joinToString(" ") { it.raw }
                fixes.add(from to to)
            }
        }

        val (fa, fb) = matches.first()
        gap(0, fa, 0, fb, interior = false, trailing = false)
        for (k in matches.indices) {
            val (i, j) = matches[k]
            if (a[i].raw == b[j].raw) {
                kept.add(a[i].key)
            } else {
                // Same word, different case or apostrophe: "sal" -> "Sal".
                changed += 1
                fixes.add(a[i].raw to b[j].raw)
            }
            if (k + 1 < matches.size) {
                val (ni, nj) = matches[k + 1]
                gap(i + 1, ni, j + 1, nj, interior = true, trailing = false)
            }
        }
        val (la, lb) = matches.last()
        gap(la + 1, a.size, lb + 1, b.size, interior = false, trailing = true)

        // Most words changed means a rewrite: count it, but learn nothing from it. A short text
        // with one small fix (two words of three) is still a fix.
        val learnable = matches.size * 2 >= a.size || a.size - matches.size <= 2
        return VoiceEditOutcome(
            words = a.size,
            changedWords = changed.coerceAtMost(a.size),
            fixes = if (learnable) fixes else emptyList(),
            keptWords = kept,
        )
    }

    /** Index pairs of the longest common subsequence of the two word lists, by lowercase key. */
    private fun lcs(a: List<Token>, b: List<Token>): List<Pair<Int, Int>> {
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i].key == b[j].key) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i].key == b[j].key -> { out.add(i to j); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return out
    }
}

/**
 * The fixes learned so far, and how often voice text needed editing. Saved as plain text with
 * [serialize] and read back with [parse].
 */
class CorrectionBook {
    companion object {
        /** A fix must be made this many times before it's applied. */
        const val MIN_TIMES = 2

        /** Most phrases remembered; the least used are dropped past this. */
        const val MAX_PHRASES = 500

        /** Pieces of voice text kept for the edit rate. */
        const val HISTORY = 200

        /** Most learned words added to the glossary hint. */
        const val MAX_GLOSSARY_WORDS = 30

        fun parse(text: String): CorrectionBook {
            val book = CorrectionBook()
            for (line in text.lineSequence()) {
                val f = line.split('\t')
                when (f.firstOrNull()) {
                    "F" -> if (f.size == 4) {
                        val n = f[3].toIntOrNull() ?: continue
                        book.fixes.getOrPut(f[1]) { HashMap() }[f[2]] = n
                    }
                    "K" -> if (f.size == 3) {
                        book.kept[f[1]] = f[2].toIntOrNull() ?: continue
                    }
                    "H" -> if (f.size == 3) {
                        val w = f[1].toIntOrNull() ?: continue
                        val c = f[2].toIntOrNull() ?: continue
                        book.history.addLast(w to c)
                    }
                }
            }
            while (book.history.size > HISTORY) book.history.removeFirst()
            return book
        }

        private fun key(phrase: String) = phrase.lowercase()
        private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
    }

    /** Typed phrase (lowercase) -> corrected phrase -> times the user made that fix. */
    private val fixes = HashMap<String, HashMap<String, Int>>()

    /** Typed phrase (lowercase) -> times it was typed and left alone. */
    private val kept = HashMap<String, Int>()

    /** (words, changed words) for each recent piece of voice text. */
    private val history = ArrayDeque<Pair<Int, Int>>()

    /** Records what the user did to one piece of voice text. */
    fun learn(outcome: VoiceEditOutcome) {
        history.addLast(outcome.words to outcome.changedWords)
        while (history.size > HISTORY) history.removeFirst()

        for ((from, to) in outcome.fixes) {
            val f = clean(from)
            val t = clean(to)
            if (f.isEmpty() || t.isEmpty() || f == t || f.length > 60 || t.length > 60) continue
            // Turning a learned fix back means it was wrong: forget it before counting this one.
            fixes[key(t)]?.remove(f)
            fixes.getOrPut(key(f)) { HashMap() }.merge(t, 1, Int::plus)
        }

        // Words that have been fixed before but were left alone this time count against the fix.
        for (word in outcome.keptWords) {
            if (fixes.containsKey(word)) kept.merge(word, 1, Int::plus)
        }

        prune()
    }

    /** Typed phrase (lowercase) -> the correction applied to it. */
    fun activeFixes(): Map<String, String> {
        val out = HashMap<String, String>()
        for ((from, targets) in fixes) {
            val best = targets.maxByOrNull { it.value } ?: continue
            if (best.value >= MIN_TIMES && best.value > (kept[from] ?: 0)) out[from] = best.key
        }
        return out
    }

    /** Applies the learned fixes to new voice text, longest phrases first, whole words only. */
    fun apply(text: String): String {
        val active = activeFixes()
        if (active.isEmpty()) return text
        var out = text
        for ((from, to) in active.entries.sortedByDescending { it.key.length }) {
            val words = from.split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) continue
            val pattern = Regex(
                "(?<![\\p{L}\\p{N}'’\\-])" +
                    words.joinToString("\\s+") { Regex.escape(it) } +
                    "(?![\\p{L}\\p{N}'’\\-])",
                RegexOption.IGNORE_CASE,
            )
            out = pattern.replace(out) { m -> matchCase(m.value, to) }
        }
        return out
    }

    /** Words from the learned corrections, most used first, for the glossary hint. */
    fun learnedWords(): List<String> {
        val active = activeFixes()
        val counts = HashMap<String, Int>()
        for ((from, to) in active) {
            val n = fixes[from]?.get(to) ?: 0
            for (w in to.split(' ')) if (w.isNotBlank()) counts.merge(w, n, ::maxOf)
        }
        return counts.entries.sortedByDescending { it.value }.map { it.key }.take(MAX_GLOSSARY_WORDS)
    }

    val learnedFixCount: Int get() = activeFixes().size

    /** (changed words, words) over the recent pieces of voice text that were followed. */
    fun editRate(): Pair<Int, Int> = history.sumOf { it.second } to history.sumOf { it.first }

    val trackedCount: Int get() = history.size

    fun serialize(): String = buildString {
        for ((from, targets) in fixes) for ((to, n) in targets) append("F\t$from\t$to\t$n\n")
        for ((from, n) in kept) append("K\t$from\t$n\n")
        for ((w, c) in history) append("H\t$w\t$c\n")
    }

    private fun prune() {
        fixes.values.removeAll { targets -> targets.values.removeAll { it <= 0 }; targets.isEmpty() }
        kept.keys.retainAll(fixes.keys)
        if (fixes.size <= MAX_PHRASES) return
        val drop = fixes.entries.sortedBy { e -> e.value.values.sum() }
            .take(fixes.size - MAX_PHRASES).map { it.key }
        drop.forEach { fixes.remove(it); kept.remove(it) }
    }

    /** [to] as learned, but capitalized when the typed words were and [to] is all lowercase. */
    private fun matchCase(typed: String, to: String): String =
        if (typed.firstOrNull()?.isUpperCase() == true && to == to.lowercase()) {
            to.replaceFirstChar { it.uppercaseChar() }
        } else {
            to
        }
}
