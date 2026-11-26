package org.futo.inputmethod.latin.uix

import android.content.Context
import android.os.SystemClock
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.uix.utils.CorrectionBook
import org.futo.inputmethod.latin.uix.utils.VoiceCommit
import org.futo.inputmethod.latin.uix.utils.VoiceEditDiff
import java.io.File

/**
 * Follows each piece of voice-typed text until the user leaves it, and learns from what they
 * changed (see VoiceEditLearning.kt). The edits and the fixes learned from them are kept only in
 * the app's private storage, in [FILE_NAME]; nothing is sent anywhere, and nothing is learned in
 * password fields, incognito mode or fields that ask not to be learned from.
 *
 * Called on the main thread.
 */
object VoiceEditTracker {
    const val FILE_NAME = "voice_edit_learning.tsv"

    /** Characters of the field read around the voice text, at the start and while following it. */
    const val CONTEXT_CHARS = 400

    /** Voice text is followed for at most this long after it's typed. */
    private const val FOLLOW_MS = 5 * 60 * 1000L

    /** Wait for the cursor to settle before reading the field. */
    private const val SETTLE_MS = 600L

    private class Pending(val commit: VoiceCommit, val typedAt: Long) {
        var latest: String? = null
    }

    /** Guards [book]: the glossary is built off the main thread. */
    private val lock = Any()
    private var book: CorrectionBook? = null
    private var pending: Pending? = null
    private var readJob: Job? = null

    fun isEnabled(context: Context): Boolean =
        context.isDirectBootUnlocked && context.getSetting(LEARN_FROM_VOICE_EDITS)

    private fun fieldAllowsLearning(): Boolean =
        Settings.getInstance().current?.mInputAttributes?.mNoLearning != true

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun book(context: Context): CorrectionBook = synchronized(lock) { book ?: run {
        val f = file(context)
        val loaded = try {
            if (f.exists()) CorrectionBook.parse(f.readText()) else CorrectionBook()
        } catch (e: Exception) {
            CorrectionBook()
        }
        book = loaded
        loaded
    } }

    private fun save(context: Context, scope: CoroutineScope) {
        val text = synchronized(lock) { book(context).serialize() }
        val f = file(context)
        scope.launch(Dispatchers.IO) {
            try {
                val tmp = File(f.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(text)
                tmp.renameTo(f)
            } catch (_: Exception) { }
        }
    }

    /** New voice text with the learned fixes applied. */
    fun applyLearned(context: Context, text: String): String =
        if (isEnabled(context)) synchronized(lock) { book(context).apply(text) } else text

    /** Words from the learned fixes, most used first, for the glossary hint. */
    fun learnedWords(context: Context): List<String> =
        if (isEnabled(context)) synchronized(lock) { book(context).learnedWords() } else emptyList()

    /**
     * Voice text [text] was just typed. [before] and [after] are the field's text around the
     * cursor when voice input started.
     */
    fun onVoiceTyped(context: Context, scope: CoroutineScope, text: String, before: String, after: String) {
        finish(context, scope)
        if (!isEnabled(context) || !fieldAllowsLearning() || text.isBlank()) return
        // Nothing is learned until the text is found in the field at least once, so a field
        // that can't be read never counts as "not edited".
        pending = Pending(VoiceCommit(text, before, after), SystemClock.uptimeMillis())
    }

    /** The cursor moved or the text changed; reads the field once things settle. */
    fun onSelectionChanged(context: Context, scope: CoroutineScope, ic: () -> InputConnection?) {
        val p = pending ?: return
        if (SystemClock.uptimeMillis() - p.typedAt > FOLLOW_MS) {
            finish(context, scope)
            return
        }
        readJob?.cancel()
        readJob = scope.launch {
            delay(SETTLE_MS)
            if (pending !== p) return@launch
            val conn = ic() ?: return@launch
            val before = conn.getTextBeforeCursor(CONTEXT_CHARS, 0)?.toString() ?: return@launch
            val after = conn.getTextAfterCursor(CONTEXT_CHARS, 0)?.toString() ?: ""
            VoiceEditDiff.locate(p.commit, before + after)?.let { p.latest = it }
        }
    }

    /** The user left the field, or new voice text replaces the one being followed. */
    fun finish(context: Context, scope: CoroutineScope) {
        readJob?.cancel()
        readJob = null
        val p = pending ?: return
        pending = null
        val latest = p.latest ?: return
        if (!isEnabled(context)) return
        val outcome = VoiceEditDiff.compare(p.commit.text, latest) ?: return
        synchronized(lock) { book(context).learn(outcome) }
        save(context, scope)
    }

    /** Forgets every learned fix and the edit history. */
    fun forget(context: Context) {
        pending = null
        readJob?.cancel()
        synchronized(lock) { book = CorrectionBook() }
        try { file(context).delete() } catch (_: Exception) { }
    }

    /** (learned fixes, changed words, words, pieces of voice text followed) for the settings page. */
    fun summary(context: Context): IntArray {
        synchronized(lock) {
            val b = book(context)
            val (changed, words) = b.editRate()
            return intArrayOf(b.learnedFixCount, changed, words, b.trackedCount)
        }
    }
}
