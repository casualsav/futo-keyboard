package org.futo.inputmethod.latin.uix.actions

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.futo.inputmethod.latin.ATLCompat
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.ANIMATE_BUBBLE
import org.futo.inputmethod.latin.uix.AUDIO_FOCUS
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CAN_EXPAND_SPACE
import org.futo.inputmethod.latin.uix.CloseResult
import org.futo.inputmethod.latin.uix.DISALLOW_SYMBOLS
import org.futo.inputmethod.latin.uix.SPOKEN_PUNCTUATION_ONLY
import org.futo.inputmethod.latin.uix.LIVE_VOICE_TYPING
import org.futo.inputmethod.latin.uix.BUZZ_WHEN_LISTENING
import org.futo.inputmethod.latin.uix.SUPPRESS_MIC_NOISE
import org.futo.inputmethod.latin.uix.utils.SpokenPunctuation
import org.futo.inputmethod.latin.uix.utils.LiveTextRecovery
import org.futo.voiceinput.shared.LiveTranscriptionSettings
import org.futo.inputmethod.latin.uix.VoiceEditTracker
import org.futo.inputmethod.latin.uix.ENABLE_SOUND
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.PREFER_BLUETOOTH
import org.futo.inputmethod.latin.uix.PersistentActionState
import org.futo.inputmethod.latin.uix.ResourceHelper
import org.futo.inputmethod.latin.uix.USE_PERSONAL_DICT
import org.futo.inputmethod.latin.uix.USE_VAD_AUTOSTOP
import org.futo.inputmethod.latin.uix.VERBOSE_PROGRESS
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.settings.SettingsActivity
import org.futo.inputmethod.latin.uix.utils.ModelOutputSanitizer
import org.futo.inputmethod.latin.uix.utils.VoiceGlossary
import org.futo.inputmethod.latin.xlm.UserDictionaryObserver
import org.futo.inputmethod.updates.openURI
import org.futo.voiceinput.shared.ModelDoesNotExistException
import org.futo.voiceinput.shared.RecognizerView
import org.futo.voiceinput.shared.RecognizerViewListener
import org.futo.voiceinput.shared.RecognizerViewSettings
import org.futo.voiceinput.shared.RecordingSettings
import org.futo.voiceinput.shared.SoundPlayer
import org.futo.voiceinput.shared.types.Language
import org.futo.voiceinput.shared.types.ModelLoader
import org.futo.voiceinput.shared.types.getLanguageFromWhisperString
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState
import org.futo.voiceinput.shared.whisper.DecodingConfiguration
import org.futo.voiceinput.shared.whisper.ModelManager
import org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration
import java.util.Locale

val SystemVoiceInputAction = Action(
    icon = R.drawable.mic_fill,
    name = R.string.action_system_voice_input_title,
    simplePressImpl = { it, _ ->
        it.triggerSystemVoiceInput()
    },
    persistentState = null,
    windowImpl = null,
    shownInEditor = false
)


@Composable
fun NoModelInstalled(locale: Locale) {
    val context = LocalContext.current
    Box(modifier = Modifier
        .fillMaxSize()
        .clickable(
            enabled = true,
            onClickLabel = null,
            onClick = {
                context.openURI("https://keyboard.futo.tech/voice-input-models", true)
            },
            role = null,
            indication = null,
            interactionSource = remember { MutableInteractionSource() })) {
        Text(
            stringResource(
                R.string.action_voice_input_no_model_for_language_x_installed,
                locale.getDisplayName(locale)
            ), modifier = Modifier
                .align(Alignment.Center)
                .padding(8.dp), textAlign = TextAlign.Center)
    }
}

class VoiceInputPersistentState(val manager: KeyboardManagerForAction) : PersistentActionState {
    val modelManager = ModelManager(manager.getContext())
    val soundPlayer: SoundPlayer? = if(ATLCompat.IsATL) null else SoundPlayer(manager.getContext())
    val userDictionaryObserver = UserDictionaryObserver(manager.getContext())

    override suspend fun cleanUp() {
        modelManager.cleanUp()
    }

    override fun close() {
        runBlocking { modelManager.cleanUp() }
        userDictionaryObserver.unregister()
    }
}

private class VoiceInputActionWindow(
    val manager: KeyboardManagerForAction, val state: VoiceInputPersistentState,
    val model: ModelLoader, val locales: List<Locale>
) : ActionWindow(), RecognizerViewListener {
    val context = manager.getContext()

    private var shouldPlaySounds: Boolean = false
    private val spokenPunctuationOnly: Boolean = context.getSetting(SPOKEN_PUNCTUATION_ONLY)
    private val liveTyping: Boolean = context.getSetting(LIVE_VOICE_TYPING)
    private val buzzWhenListening: Boolean = context.getSetting(BUZZ_WHEN_LISTENING)

    // Live voice typing: the sentences already final, kept if voice input is cancelled.
    private var liveCommitted = ""
    private var liveDisplay = ""

    private fun sanitizeForInput(text: String): String =
        VoiceEditTracker.applyLearned(context,
            ModelOutputSanitizer.sanitize(text, startContext, spokenPunctuationOnly,
                alreadyConverted = liveTyping))
    private fun loadSettings(): RecognizerViewSettings {
        val enableSound = context.getSetting(ENABLE_SOUND)
        val verboseFeedback = false//context.getSetting(VERBOSE_PROGRESS)
        val disallowSymbols = context.getSetting(DISALLOW_SYMBOLS)
        val useBluetoothAudio = context.getSetting(PREFER_BLUETOOTH)
        val requestAudioFocus = context.getSetting(AUDIO_FOCUS)
        val canExpandSpace = context.getSetting(CAN_EXPAND_SPACE)
        val useVAD = context.getSetting(USE_VAD_AUTOSTOP)
        val animateBubble = context.getSetting(ANIMATE_BUBBLE)

        val primaryModel = model
        val languageSpecificModels = mutableMapOf<Language, ModelLoader>()
        val allowedLanguages = locales.mapNotNull { getLanguageFromWhisperString(it.language) }.toSet()

        shouldPlaySounds = enableSound

        return RecognizerViewSettings(
            shouldShowInlinePartialResult = false,
            shouldShowVerboseFeedback = verboseFeedback,
            shouldAnimateBubble = animateBubble,
            modelRunConfiguration = MultiModelRunConfiguration(
                primaryModel = primaryModel,
                languageSpecificModels = languageSpecificModels
            ),
            decodingConfiguration = DecodingConfiguration(
                // Built after the mic is open (see buildGlossary), so it doesn't delay recording.
                glossary = emptyList(),
                languages = allowedLanguages,
                suppressSymbols = disallowSymbols
            ),
            recordingConfiguration = RecordingSettings(
                preferBluetoothMic = useBluetoothAudio,
                requestAudioFocus = requestAudioFocus,
                canExpandSpace = canExpandSpace,
                useVADAutoStop = useVAD,
                suppressNoise = context.getSetting(SUPPRESS_MIC_NOISE)
            ),
            liveTranscription = if (liveTyping) LiveTranscriptionSettings(
                convert = { if (spokenPunctuationOnly) SpokenPunctuation.apply(it) else it }
            ) else null
        )
    }

    private fun buildGlossary(): List<String> {
        val dictionaryWords = if(context.getSetting(USE_PERSONAL_DICT)) {
            state.userDictionaryObserver.getWords(locales).filter { it.shortcut.isNullOrEmpty() }.map { it.word }
        } else {
            emptyList()
        }
        // Words from fixes learned from the user's own edits come first: they're the words
        // Whisper has been getting wrong.
        val learnedWords = VoiceEditTracker.learnedWords(context)
        return VoiceGlossary.select(
            dictionaryWords = learnedWords + dictionaryWords,
            alwaysInclude = if(spokenPunctuationOnly) VoiceGlossary.SPOKEN_PUNCTUATION_WORDS else emptyList()
        )
    }

    private var recognizerView: MutableState<RecognizerView?> = mutableStateOf(null)
    private var modelException: MutableState<ModelDoesNotExistException?> = mutableStateOf(null)

    private val initJob = manager.getLifecycleScope().launch(Dispatchers.Default) {
        yield()
        val settings = loadSettings()

        yield()
        val recognizerView = try {
            RecognizerView(
                context = manager.getContext(),
                listener = this@VoiceInputActionWindow,
                settings = settings,
                lifecycleScope = manager.getLifecycleScope(),
                modelManager = state.modelManager
            )
        } catch(e: ModelDoesNotExistException) {
            modelException.value = e
            return@launch
        }

        this@VoiceInputActionWindow.recognizerView.value = recognizerView

        //yield()
        recognizerView.reset()

        //yield()
        recognizerView.start()

        recognizerView.setGlossary(buildGlossary())
    }

    private var inputTransaction = manager.createInputTransaction()

    // The text around the cursor when voice input opened; kept if the field is taken back.
    private val startContext = inputTransaction.textContext

    // Live voice typing: the text last put in the field, and how much of it was left there as
    // plain text when an app restarted the field mid-dictation (written again only past that).
    private var liveShown = ""
    private var liveLeftInField = ""

    /**
     * Writes live text, taking the field back first if the app ended the keyboard's hold on it
     * while the user was talking, so nothing said is lost or typed twice.
     */
    private fun writeLive(fullText: String, final: Boolean) {
        if (!inputTransaction.isActive) {
            val newTransaction = try { manager.createInputTransaction() } catch (e: Exception) { null }
            if (newTransaction != null) {
                inputTransaction = newTransaction
                liveLeftInField = LiveTextRecovery.leftInField(
                    newTransaction.textContext.beforeCursor?.toString() ?: "", liveShown)
            }
        }
        val toWrite = LiveTextRecovery.remainder(liveLeftInField, fullText)
        if (final) inputTransaction.commit(toWrite) else inputTransaction.updatePartial(toWrite)
        liveShown = fullText
    }

    @Composable
    private fun ModelDownloader(modelException: ModelDoesNotExistException) {
        NoModelInstalled(locales.firstOrNull() ?: Locale.ROOT)
    }

    @Composable
    override fun windowName(): String {
        return stringResource(R.string.action_voice_input_title)
    }

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        Box(modifier = Modifier
            .fillMaxSize()
            .clickable(
                enabled = true,
                onClickLabel = null,
                onClick = { recognizerView.value?.finish() },
                role = null,
                indication = null,
                interactionSource = remember { MutableInteractionSource() })
            .semantics(mergeDescendants = true) {
                traversalIndex = -1.0f
            }) {
            Box(modifier = Modifier.align(Alignment.Center)) {
                when {
                    modelException.value != null -> ModelDownloader(modelException.value!!)
                    recognizerView.value != null -> recognizerView.value!!.Content()
                }
            }
        }
    }

    override fun close(): CloseResult {
        inputTransaction.cancel()
        runBlocking { initJob.cancelAndJoin() }
        recognizerView.value?.cancel()
        state.modelManager.cancelAll()
        return CloseResult.Default
    }

    private var wasFinished = false
    private var cancelPlayed = false
    override fun cancelled() {
        if (!wasFinished) {
            if (shouldPlaySounds && !cancelPlayed) {
                state.soundPlayer?.playCancelSound()
                cancelPlayed = true
            }
            // Live voice typing: what was shown stays, so nothing said is lost.
            inputTransaction.cancel()
        }
    }

    override fun recordingStarted(device: MicrophoneDeviceState) {
        // With the buzz on, it is the only cue: the start sound plays before the mic is ready.
        if (shouldPlaySounds && !buzzWhenListening) {
            state.soundPlayer?.playStartSound()
        }

        // Only set the setting if bluetooth is available, else it would reset the setting
        // every time it's used without a bluetooth device connected.
        if(device.bluetoothAvailable) {
            manager.getLifecycleScope().launch {
                context.setSetting(PREFER_BLUETOOTH, device.bluetoothActive)
            }
        }
    }

    override fun finished(result: String) {
        wasFinished = true

        manager.getLifecycleScope().launch(Dispatchers.Main) {
            // Never end up with less than was shown while talking.
            val text = if (liveTyping && result.isBlank() && liveDisplay.isNotBlank()) liveDisplay else result
            val sanitized = sanitizeForInput(text)
            if (liveTyping) writeLive(sanitized, final = true) else inputTransaction.commit(sanitized)
            inputTransaction.learningContext?.let {
                VoiceEditTracker.onVoiceTyped(context, manager.getLifecycleScope(), sanitized,
                    before = it.beforeCursor?.toString() ?: "", after = it.afterCursor?.toString() ?: "")
            }
            manager.announce(result)
            manager.closeActionWindow()
        }
    }

    override fun partialResult(result: String) {
        if (liveTyping) return
        manager.getLifecycleScope().launch(Dispatchers.Main) {
            inputTransaction.updatePartial(sanitizeForInput(result))
        }
    }

    override fun liveText(committed: String, display: String) {
        manager.getLifecycleScope().launch(Dispatchers.Main) {
            if (wasFinished) return@launch
            liveCommitted = committed
            liveDisplay = display
            writeLive(sanitizeForInput(display), final = false)
        }
    }

    private fun vibrator(): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }?.takeIf { it.hasVibrator() }
    } catch (_: Exception) {
        null
    }

    private var buzzed = false
    override fun micTaken() {
        // Buzz again once the mic is back, so the user knows to carry on.
        buzzed = false
    }

    override fun listening() {
        if (!buzzWhenListening || buzzed) return
        buzzed = true
        try {
            val vibrator = vibrator() ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(40)
            }
        } catch (_: Exception) {
            // No buzz is better than a crash.
        }
    }

    // When the mic stops on its own: two light taps, unlike the single start buzz, so a stop
    // while you are still talking doesn't go unnoticed.
    override fun stoppedListening() {
        if (!buzzWhenListening) return
        try {
            val vibrator = vibrator() ?: return
            val timings = longArrayOf(0, 20, 90, 20)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (vibrator.hasAmplitudeControl()) {
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, intArrayOf(0, 90, 0, 90), -1))
                } else {
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, -1)
            }
        } catch (_: Exception) {
            // No buzz is better than a crash.
        }
    }

    override fun requestPermission(onGranted: () -> Unit, onRejected: () -> Unit): Boolean {
        return false
    }

    override fun openSettings() {
        SettingsActivity.openToNavDest(context, "languages")
    }
}

private class VoiceInputNoModelWindow(val locale: Locale) : ActionWindow() {
    @Composable
    override fun windowName(): String {
        return stringResource(R.string.action_voice_input_title)
    }

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        NoModelInstalled(locale)
    }
}

val VoiceInputAction = Action(icon = R.drawable.mic_fill,
    name = R.string.action_voice_input_title,
    simplePressImpl = null,
    keepScreenAwake = true,
    persistentState = { VoiceInputPersistentState(it) },
    windowImpl = { manager, persistentState ->
        val locales = manager.getActiveLocales()

        val model = ResourceHelper.tryFindingVoiceInputModelForLocale(manager.getContext(), locales.firstOrNull() ?: Locale.ROOT)

        if(model == null) {
            VoiceInputNoModelWindow(locales.firstOrNull() ?: Locale.ROOT)
        } else {
            VoiceInputActionWindow(
                manager = manager, state = persistentState as VoiceInputPersistentState,
                locales = locales, model = model
            )
        }
    }
)
/**
 * With "Buzz when listening" on, the mic gives one buzz: the one when it is listening. The usual
 * key-press buzz on the mic is skipped so there aren't two.
 */
object VoiceInputPressFeedback {
    @JvmStatic
    fun skipPressBuzz(context: Context, action: Action): Boolean =
        action == VoiceInputAction &&
                ActionRegistry.getActionOverride(context, action) == VoiceInputAction &&
                context.getSetting(BUZZ_WHEN_LISTENING)

    @JvmStatic
    fun skipPressBuzzForCode(context: Context, code: Int): Boolean =
        code == VoiceInputAction.keyCode && skipPressBuzz(context, VoiceInputAction)
}
