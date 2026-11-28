package org.futo.voiceinput.shared.live

/**
 * Live voice typing: decides which words are final while the user is still talking.
 *
 * The recording is transcribed one piece at a time, each piece starting where the last
 * finished sentence ended and running to the pause just heard. Each transcript goes through
 * [convert] first (spoken punctuation: "comma" becomes ","), then only the text up to its last
 * punctuation mark is kept for good. The words after that mark belong to a sentence the user is
 * still saying, so they are shown as a preview and transcribed again with the next piece.
 *
 * When a piece ends right on a mark, the audio start moves to the end of the piece, so that audio
 * is never transcribed again. When the mark is in the middle, the audio start stays put (it isn't
 * known where in the audio the mark was said), the next transcript covers those words again, and
 * the marks already kept are skipped in it, so nothing is typed twice.
 *
 * Pure Kotlin, no Android types, so it is unit-tested on the JVM.
 */
class LiveSegmenter(private val convert: (String) -> String) {
    /** Text kept for good, already converted, in the order it was said. */
    var committed: String = ""
        private set

    /** Words after the last mark of the latest transcript: shown, not yet final. */
    var pending: String = ""
        private set

    /** Text kept from the audio since the current audio start; skipped in the next transcript. */
    private var keptFromCurrentAudio = ""

    /** What [onPiece] decided. */
    data class Result(val advanceAudioStart: Boolean)

    /**
     * Takes the transcript of the audio from the current audio start to a pause.
     * [final]: the last piece, or a piece grown too long; everything in it is kept.
     */
    fun onPiece(rawText: String, final: Boolean = false): Result {
        val text = skipKept(convert(rawText).trim(), keptFromCurrentAudio)

        if (final) {
            // The last pass covers the words already shown as the preview. When it comes back
            // empty, or with far fewer words than that preview (Whisper sometimes drops most of a
            // long piece, or returns a stray "Thank you."), what was shown is kept instead, so a
            // dictation is never lost at the end.
            if (pending.isNotEmpty() && lostMostOf(pending, text)) {
                keepPending()
                return Result(advanceAudioStart = true)
            }
            committed = join(committed, text)
            pending = ""
            keptFromCurrentAudio = ""
            return Result(advanceAudioStart = true)
        }

        // A piece that came back empty (noise, or Whisper returning nothing) says nothing new:
        // the preview stays, rather than vanishing until the next piece brings it back.
        if (text.isEmpty()) return Result(advanceAudioStart = false)

        val lastMarkEnd = lastMarkEnd(text)
        if (lastMarkEnd < 0) {
            pending = text
            return Result(advanceAudioStart = false)
        }

        val kept = text.substring(0, lastMarkEnd).trim()
        val rest = text.substring(lastMarkEnd).trim()
        committed = join(committed, kept)
        pending = rest

        return if (rest.isEmpty()) {
            keptFromCurrentAudio = ""
            Result(advanceAudioStart = true)
        } else {
            keptFromCurrentAudio = join(keptFromCurrentAudio, kept)
            Result(advanceAudioStart = false)
        }
    }

    /**
     * The audio since the last piece holds no new speech: the latest transcript stands, so it is
     * kept whole without transcribing again.
     */
    fun keepPending() {
        committed = join(committed, pending)
        pending = ""
        keptFromCurrentAudio = ""
    }

    /** Committed text and preview together, as it should read on screen. */
    fun display(): String = join(committed, pending)

    companion object {
        private const val MARKS = ".,;:!?"
        private val SENTENCE_END = Regex("""[.!?]["”»)\]]*$""")

        /**
         * Common words that Whisper capitalises only because they start a piece. After a comma
         * they're lowercased; any other word keeps Whisper's capital, so a name ("Sal") or a
         * place stays capitalised.
         */
        private val COMMON_WORDS = setOf(
            "a", "about", "above", "actually", "after", "again", "against", "all", "almost",
            "already", "also", "although", "always", "am", "an", "and", "another", "any",
            "anybody", "anyone", "anything", "anyway", "are", "around", "as", "ask", "asked", "at",
            "away", "back", "be", "because", "been", "before", "being", "below", "better",
            "between", "both", "bought", "but", "buy", "by", "call", "called", "came", "can",
            "can't", "cause", "come", "could", "couldn't", "day", "did", "didn't", "do", "does",
            "doesn't", "doing", "don't", "done", "down", "during", "each", "either", "else",
            "even", "ever", "every", "everyone", "everything", "few", "find", "first", "for",
            "from", "get", "gets", "getting", "give", "go", "goes", "going", "gone", "good", "got",
            "great", "had", "has", "hasn't", "have", "haven't", "having", "he", "he's", "her",
            "here", "hers", "him", "his", "how", "however", "if", "in", "into", "is", "isn't",
            "it", "it's", "its", "just", "keep", "know", "last", "later", "least", "less", "let",
            "let's", "like", "little", "look", "lot", "made", "make", "many", "may", "maybe", "me",
            "might", "mine", "more", "most", "much", "must", "my", "need", "never", "new", "next",
            "no", "nobody", "none", "nor", "not", "nothing", "now", "of", "off", "often", "oh",
            "ok", "okay", "on", "once", "one", "only", "or", "other", "others", "our", "ours",
            "out", "over", "own", "please", "plus", "pretty", "put", "quite", "rather", "really",
            "right", "said", "same", "saw", "say", "says", "see", "seem", "she", "she's", "should",
            "shouldn't", "since", "so", "some", "someone", "something", "sometimes", "soon",
            "still", "such", "sure", "take", "tell", "than", "thank", "thanks", "that", "that's",
            "the", "their", "theirs", "them", "then", "there", "there's", "these", "they",
            "they're", "thing", "things", "think", "this", "those", "though", "thought", "through",
            "time", "to", "today", "together", "tomorrow", "too", "took", "two", "under", "until",
            "up", "us", "very", "was", "wasn't", "way", "we", "we're", "well", "went", "were",
            "weren't", "what", "what's", "when", "where", "whether", "which", "while", "who",
            "whom", "whose", "why", "will", "with", "without", "won't", "would", "wouldn't",
            "yeah", "yes", "yesterday", "yet", "you", "you're", "your", "yours"
        )
        private val FIRST_WORD = Regex("""^[\p{L}'’]+""")
        private val PRONOUN_I = Regex("""^I(?:'(?:m|ll|ve|d))?\b""")

        /** A mark the user said: not `.`, `,` or `:` between two digits (3.5, 6,000, 6:30). */
        private fun isMarkAt(text: String, i: Int): Boolean {
            val c = text[i]
            if (c !in MARKS) return false
            if (c == '.' || c == ',' || c == ':') {
                val digitBefore = i > 0 && text[i - 1].isDigit()
                val digitAfter = i + 1 < text.length && text[i + 1].isDigit()
                if (digitBefore && digitAfter) return false
            }
            return true
        }

        fun countMarks(text: String): Int = text.indices.count { isMarkAt(text, it) }

        /** Index just past the last mark (and any marks right after it), or -1 with none. */
        fun lastMarkEnd(text: String): Int {
            val i = text.indices.lastOrNull { isMarkAt(text, it) } ?: return -1
            return i + 1
        }

        private val WORD = Regex("""[\p{L}\p{N}'’]+""")

        fun wordCount(text: String): Int = WORD.findAll(text).count()

        /** Whether [replacement] has lost most of [shown]: none of its words, or under 60% of them. */
        fun lostMostOf(shown: String, replacement: String): Boolean {
            val shownWords = wordCount(shown)
            if (shownWords == 0) return false
            val newWords = wordCount(replacement)
            if (newWords == 0) return true
            return shownWords >= 3 && newWords * 10 < shownWords * 6
        }

        /**
         * [text] without the part that was already kept. Usually that's everything up to the
         * same number of marks as [kept] has. When the new transcript heard a spoken mark
         * differently, its marks no longer line up with the kept words (the cut would land
         * far from where the kept words end), so it skips as many words as [kept] has instead,
         * and the words after it still come through.
         */
        fun skipKept(text: String, kept: String): String {
            if (kept.isBlank()) return text
            val keptWords = WORD.findAll(kept).count()
            if (keptWords == 0) return text
            val n = countMarks(kept)
            val byMarks = markIndex(text, n)
            if (byMarks >= 0) {
                val wordsBefore = WORD.findAll(text.substring(0, byMarks)).count()
                if (kotlin.math.abs(wordsBefore - keptWords) <= 2) return text.substring(byMarks + 1).trim()
            }
            val match = WORD.findAll(text).drop(keptWords - 1).firstOrNull() ?: return ""
            var end = match.range.last + 1
            while (end < text.length && isMarkAt(text, end)) end++
            return text.substring(end).trim()
        }

        /** Index of the [n]th mark in [text], or -1. */
        private fun markIndex(text: String, n: Int): Int {
            if (n <= 0) return -1
            var seen = 0
            for (i in text.indices) {
                if (isMarkAt(text, i)) {
                    seen++
                    if (seen == n) return i
                }
            }
            return -1
        }

        /** [text] after its first [n] marks; all of it when [n] is 0, nothing with fewer. */
        fun skipMarks(text: String, n: Int): String {
            if (n <= 0) return text
            var seen = 0
            for (i in text.indices) {
                if (isMarkAt(text, i)) {
                    seen++
                    if (seen == n) return text.substring(i + 1).trim()
                }
            }
            return ""
        }

        /**
         * [next] after [previous] with one space, capitalised after a sentence end. Otherwise a
         * common word is lowercased (Whisper capitalises the start of every piece), and "I", its
         * contractions, words in all caps and any other word (likely a name) keep their capital.
         */
        private fun isCommonWord(text: String): Boolean {
            val word = FIRST_WORD.find(text)?.value?.lowercase()?.replace('’', '\'') ?: return false
            return word in COMMON_WORDS
        }

        fun join(previous: String, next: String): String {
            val a = previous.trim()
            val b = next.trim()
            if (a.isEmpty()) return b
            if (b.isEmpty()) return a
            val fixed = when {
                SENTENCE_END.containsMatchIn(a) -> b.replaceFirstChar { it.uppercaseChar() }
                PRONOUN_I.containsMatchIn(b) -> b
                b.length >= 2 && b[0].isUpperCase() && b[1].isUpperCase() -> b
                isCommonWord(b) -> b.replaceFirstChar { it.lowercaseChar() }
                else -> b
            }
            return if (fixed.first() in MARKS) a + fixed else "$a $fixed"
        }
    }
}
