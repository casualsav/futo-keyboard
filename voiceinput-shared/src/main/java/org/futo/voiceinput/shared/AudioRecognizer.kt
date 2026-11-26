package org.futo.voiceinput.shared

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorPrivacyManager
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MicrophoneDirection
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import com.konovalov.vad.Vad
import com.konovalov.vad.config.FrameSize
import com.konovalov.vad.config.Mode
import com.konovalov.vad.config.Model
import com.konovalov.vad.config.SampleRate
import com.konovalov.vad.models.VadModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.futo.voiceinput.shared.ggml.InferenceCancelledException
import org.futo.voiceinput.shared.ggml.InvalidModelException
import org.futo.voiceinput.shared.live.LiveSegmenter
import org.futo.voiceinput.shared.types.AudioRecognizerListener
import org.futo.voiceinput.shared.types.InferenceState
import org.futo.voiceinput.shared.types.Language
import org.futo.voiceinput.shared.types.MagnitudeState
import org.futo.voiceinput.shared.types.ModelInferenceCallback
import org.futo.voiceinput.shared.types.ModelLoader
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState
import org.futo.voiceinput.shared.whisper.DecodingConfiguration
import org.futo.voiceinput.shared.whisper.ModelManager
import org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration
import org.futo.voiceinput.shared.whisper.MultiModelRunner
import org.futo.voiceinput.shared.whisper.isBlankResult
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

private fun getRecordingDeviceKind(type: Int): String {
    return when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_DOCK -> "DOCK"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM_TUNER"
        AudioDeviceInfo.TYPE_TV_TUNER -> "TV_TUNER"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
        AudioDeviceInfo.TYPE_IP -> "IP"
        AudioDeviceInfo.TYPE_BUS -> "BUS"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI_EARC"
        AudioDeviceInfo.TYPE_DOCK_ANALOG -> "DOCK_ANALOG"
        else -> "unknown@${type}"
    }
}

// Auto-stop ends the recording after this many 30 ms VAD frames of silence once speech was heard:
// 50 frames is about one and a half seconds (upstream waits 66, about two).
private const val AUTO_STOP_SILENCE_FRAMES = 50

// When auto-stop ends the recording, the silence that triggered it is cut off before
// transcribing, keeping this much after the last speech. Whisper's encoder time grows with the
// audio's length, so this saves about a second of work on every dictation, and trailing silence
// is where Whisper tends to invent words like "you" or "thank you".
private const val SILENCE_KEPT_AFTER_SPEECH_SAMPLES = 16000 / 2

// Live voice typing (see LiveSegmenter): a piece of the recording is transcribed at each pause
// of about a third of a second (12 VAD frames) while the user keeps talking. A piece needs at
// least a second of audio. One that has grown to 15 seconds without a spoken mark is kept whole,
// so no audio is transcribed over and over. The final piece after the user stops is skipped
// when it would be shorter than 0.3 seconds.
private const val LIVE_PAUSE_FRAMES = 12
private const val LIVE_MIN_PIECE_SAMPLES = 16000
private const val LIVE_MAX_PIECE_SAMPLES = 16000 * 15
private const val LIVE_MIN_FINAL_SAMPLES = 16000 * 3 / 10

/**
 * Live voice typing. [convert] turns a raw transcript into the text to type (spoken punctuation);
 * the result passed to [AudioRecognizerListener.finished] is then already converted.
 */
data class LiveTranscriptionSettings(
    val convert: (String) -> String
)

data class RecordingSettings(
    val preferBluetoothMic: Boolean,
    val requestAudioFocus: Boolean,
    val canExpandSpace: Boolean,
    val useVADAutoStop: Boolean,
    // Android's own noise suppression on the mic (often done by the phone's audio chip, so it
    // costs little battery). Used only where the phone offers it.
    val suppressNoise: Boolean = false
)

data class AudioRecognizerSettings(
    val modelRunConfiguration: MultiModelRunConfiguration,
    val decodingConfiguration: DecodingConfiguration,
    val recordingConfiguration: RecordingSettings,
    val liveTranscription: LiveTranscriptionSettings? = null
)

class ModelDoesNotExistException(val models: List<ModelLoader>) : Throwable()

class AudioRecognizer(
    private val context: Context,
    private val lifecycleScope: LifecycleCoroutineScope,
    modelManager: ModelManager,
    private val listener: AudioRecognizerListener,
    private val settings: AudioRecognizerSettings
) {
    private var isRecording = false
    private var recorder: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    private val modelRunner = MultiModelRunner(modelManager)

    private val canExpandSpace = settings.recordingConfiguration.canExpandSpace
    private val useVAD = settings.recordingConfiguration.useVADAutoStop

    private var floatSamples: FloatBuffer = FloatBuffer.allocate(16000 * 30)

    // End of the last 100 ms chunk the VAD heard speech in (or that the VAD did not check), and
    // whether the recording ended on auto-stop. Used to cut the trailing silence; see runModel.
    private var lastSpeechEndSample = 0
    private var stoppedOnSilence = false
    private var recorderJob: Job? = null
    private var modelJob: Job? = null
    private var loadModelJob: Job? = null

    private var focusRequest: AudioFocusRequest? = null

    // Live voice typing state. The audio start is where the next piece begins; the last run end
    // is where the newest transcribed piece ended. Both are written only by the live pieces and
    // the final run, which never overlap; the recording loop reads them.
    private val liveSettings = settings.liveTranscription
    private var segmenter: LiveSegmenter? = liveSettings?.let { LiveSegmenter(it.convert) }
    @Volatile private var liveAudioStart = 0
    @Volatile private var lastLiveRunEnd = -1
    private var liveJob: Job? = null
    private var livePauseHandled = false

    private var communicationDevice = "unknown"

    private fun focusAudio() {
        unfocusAudio()

        if(!settings.recordingConfiguration.requestAudioFocus) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                focusRequest =
                    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .build()
                audioManager.requestAudioFocus(focusRequest!!)
            }
        }catch(e: Exception) {
            e.printStackTrace()
        }
    }

    private fun unfocusAudio() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (focusRequest != null) {
                    audioManager.abandonAudioFocusRequest(focusRequest!!)
                }
                focusRequest = null
            }
        }catch(e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isBluetoothAvailable(): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val devices = audioManager.availableCommunicationDevices

                return devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                } != null
            }
        } catch(_: Exception) {}

        return false
    }

    private fun setCommunicationDevice(preferBluetoothMic: Boolean): Pair<Boolean, String> {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val devices = audioManager.availableCommunicationDevices
                val tgtDevice =
                    devices.firstOrNull { preferBluetoothMic && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ?:
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC } ?:
                    devices.firstOrNull { it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO  } ?:
                    devices.first()

                if (!audioManager.setCommunicationDevice(tgtDevice)) {
                    audioManager.clearCommunicationDevice()
                    return Pair(false, "")
                } else {
                    return Pair(tgtDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO, tgtDevice.productName.toString())
                }
            }
        } catch(_: Exception) {}
        return Pair(false, "")
    }

    private fun clearCommunicationDevice() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
    }

    @Throws(ModelDoesNotExistException::class)
    private fun verifyModelsExist() {
        val modelsThatDoNotExist = mutableListOf<ModelLoader>()

        if (!settings.modelRunConfiguration.primaryModel.exists(context)) {
            modelsThatDoNotExist.add(settings.modelRunConfiguration.primaryModel)
        }

        for (model in settings.modelRunConfiguration.languageSpecificModels.values) {
            if (!model.exists(context)) {
                modelsThatDoNotExist.add(model)
            }
        }

        if (modelsThatDoNotExist.isNotEmpty()) {
            throw ModelDoesNotExistException(modelsThatDoNotExist)
        }
    }

    init {
        verifyModelsExist()
    }

    fun reset() {
        recorder?.stop()
        recorderJob?.cancel()

        recorder?.release()
        recorder = null
        releaseNoiseSuppressor()

        modelJob?.cancel()
        liveJob?.cancel()
        isRecording = false

        modelRunner.cancelAll()

        unfocusAudio()

        clearCommunicationDevice()
    }

    fun finish() {
        if(!isRecording) return
        onFinishRecording()
    }

    fun cancel() {
        reset()
        listener.cancelled()
    }

    fun openPermissionSettings() {
        val packageName = context.packageName
        val micPermissionRequester = Intent()
        micPermissionRequester.setClassName(context, "org.futo.inputmethod.latin.MicPermissionActivity")
        micPermissionRequester.setFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        context.startActivity(micPermissionRequester)

        cancel()
    }

    fun start() {
        listener.loading()

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermission()
        } else {
            startRecording()
        }
    }

    private fun requestPermission() {
        listener.needPermission { wasGranted ->
            if(wasGranted) {
                startRecording()
            }
        }
    }


    @Throws(SecurityException::class)
    private fun createAudioRecorder(): AudioRecord {
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            16000 * 2 * 5
        )

        this.recorder = recorder

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            recorder.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER)
        }

        attachNoiseSuppressor(recorder)

        return recorder
    }

    private fun attachNoiseSuppressor(recorder: AudioRecord) {
        releaseNoiseSuppressor()
        if (!settings.recordingConfiguration.suppressNoise) return
        try {
            if (!NoiseSuppressor.isAvailable()) return
            noiseSuppressor = NoiseSuppressor.create(recorder.audioSessionId)?.also {
                it.enabled = true
            }
        } catch (e: Exception) {
            // Some phones list the effect but refuse it; record without it.
            Log.w("AudioRecognizer", "Noise suppressor not available: $e")
            noiseSuppressor = null
        }
    }

    private fun releaseNoiseSuppressor() {
        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {}
        noiseSuppressor = null
    }

    private suspend fun preloadModels() {
        modelRunner.preload(settings.modelRunConfiguration)
    }

    private fun expandSpaceIfAllowed(): Boolean {
        if(canExpandSpace) {
            // Allocate an extra 30 seconds
            val newSampleBuffer = FloatBuffer.allocate(floatSamples.capacity() + 16000 * 30)
            //Log.d("AudioRecognizer", "Allocating extra space: ${floatSamples.capacity() / 16000} -> ${newSampleBuffer.capacity() / 16000}")
            newSampleBuffer.put(floatSamples.array(), 0, floatSamples.capacity() - floatSamples.remaining())
            floatSamples = newSampleBuffer
            return true
        }
        return false
    }

    private suspend fun recordingJob(recorder: AudioRecord, vad: VadModel?) {
        var hasTalked = false
        var anyNoiseAtAll = false

        val canMicBeBlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(SensorPrivacyManager::class.java) as SensorPrivacyManager).supportsSensorToggle(
                SensorPrivacyManager.Sensors.MICROPHONE
            )
        } else {
            false
        }
        var isMicBlocked = false

        val vadSampleBuffer = ShortBuffer.allocate(480)
        var numConsecutiveNonSpeech = 0
        var numConsecutiveSpeech = 0

        val samples = ShortArray(1600)

        while (isRecording) {
            yield()
            val nRead = recorder.read(samples, 0, 1600, AudioRecord.READ_BLOCKING)
            if (nRead <= 0) break
            yield()

            var isRunningOutOfSpace = (floatSamples.remaining() < nRead.coerceAtLeast(1600)) && !expandSpaceIfAllowed()

            val hasNotTalkedRecently = hasTalked && (numConsecutiveNonSpeech > AUTO_STOP_SILENCE_FRAMES) && useVAD
            if (isRunningOutOfSpace || hasNotTalkedRecently) {
                stoppedOnSilence = hasNotTalkedRecently && !isRunningOutOfSpace
                yield()
                withContext(Dispatchers.Main) {
                    finish()
                }
                return
            }

            // Run VAD (also when only live voice typing needs it, to find pauses)
            var chunkHadSpeech = false
            if(vad != null) {
                var remainingSamples = nRead
                var offset = 0
                while (remainingSamples > 0) {
                    if (!vadSampleBuffer.hasRemaining()) {
                        val isSpeech = vad.isSpeech(vadSampleBuffer.array())
                        vadSampleBuffer.clear()
                        vadSampleBuffer.rewind()

                        if (!isSpeech) {
                            numConsecutiveNonSpeech++
                            numConsecutiveSpeech = 0
                        } else {
                            numConsecutiveNonSpeech = 0
                            numConsecutiveSpeech++
                            chunkHadSpeech = true
                        }
                    }

                    val samplesToRead = min(min(remainingSamples, 480), vadSampleBuffer.remaining())
                    for (i in 0 until samplesToRead) {
                        vadSampleBuffer.put(
                            samples[offset]
                        )
                        offset += 1
                        remainingSamples -= 1
                    }
                }
            }

            floatSamples.put(samples.sliceArray(0 until nRead).map { it.toFloat() / Short.MAX_VALUE.toFloat() }.toFloatArray())
            if (chunkHadSpeech) lastSpeechEndSample = floatSamples.position()

            // Don't set hasTalked if the start sound may still be playing, otherwise on some
            // devices the rms just explodes and `hasTalked` is always true
            val startSoundPassed = (floatSamples.position() > 16000 * 0.6)
            if (!startSoundPassed) {
                numConsecutiveSpeech = 0
                numConsecutiveNonSpeech = 0
            }

            val rms = sqrt(samples.sumOf { (it.toFloat() / Short.MAX_VALUE.toFloat()).pow(2).toDouble() } / samples.size).toFloat()

            if (startSoundPassed && ((rms > 0.01) || (numConsecutiveSpeech > 8))) {
                hasTalked = true
            }

            if (segmenter != null && vad != null) {
                if (numConsecutiveNonSpeech == 0) livePauseHandled = false
                if (hasTalked && startSoundPassed && numConsecutiveNonSpeech >= LIVE_PAUSE_FRAMES) {
                    maybeStartLivePiece()
                }
            }

            if (rms > 0.0001) {
                anyNoiseAtAll = true
                isMicBlocked = false
            }

            // Check if mic is blocked
            val blockCheckTimePassed = (floatSamples.position() > 2 * 16000) // two seconds
            if (!anyNoiseAtAll && canMicBeBlocked && blockCheckTimePassed) {
                isMicBlocked = true
            }

            val magnitude = (1.0f - 0.1f.pow(24.0f * rms))

            val state = if (hasTalked) {
                MagnitudeState.TALKING
            } else if (isMicBlocked) {
                MagnitudeState.MIC_MAY_BE_BLOCKED
            } else {
                MagnitudeState.NOT_TALKED_YET
            }

            yield()
            withContext(Dispatchers.Main) {
                listener.updateMagnitude(magnitude, state)
            }

            // Skip ahead as much as possible, in case we are behind (taking more than
            // 100ms to process 100ms)
            while (true) {
                yield()
                val nRead2 = recorder.read(
                    samples, 0, 1600, AudioRecord.READ_NON_BLOCKING
                )
                if (nRead2 > 0) {
                    if (floatSamples.remaining() < nRead2 && !expandSpaceIfAllowed()) {
                        yield()
                        withContext(Dispatchers.Main) {
                            finish()
                        }
                        break
                    }
                    floatSamples.put(samples.sliceArray(0 until nRead2).map { it.toFloat() / Short.MAX_VALUE.toFloat() }.toFloatArray())
                    // The VAD doesn't see samples read while catching up, so keep them.
                    lastSpeechEndSample = floatSamples.position()
                } else {
                    break
                }
            }
        }
        println("isRecording loop exited")
    }

    private fun createVad(): VadModel {
        return Vad.builder().setModel(Model.WEB_RTC_GMM).setMode(Mode.VERY_AGGRESSIVE)
            .setFrameSize(FrameSize.FRAME_SIZE_480).setSampleRate(SampleRate.SAMPLE_RATE_16K)
            .setSpeechDurationMs(150).setSilenceDurationMs(300).build()
    }

    @Throws(SecurityException::class)
    private fun createRecorderAndJob(preferBluetoothMic: Boolean): MicrophoneDeviceState {
        isRecording = false
        recorder?.stop()

        val bluetoothInfo = setCommunicationDevice(preferBluetoothMic)

        val task = {
            recorder?.release()

            val recorder = createAudioRecorder()

            recorder.startRecording()
            this.recorder = recorder

            isRecording = true

            recorderJob = lifecycleScope.launch {
                withContext(Dispatchers.Default) {
                    if(useVAD || liveSettings != null) {
                        createVad().use { vad ->
                            recordingJob(recorder, vad)
                        }
                    } else {
                        recordingJob(recorder, null)
                    }
                }
            }
        }

        if(recorderJob != null) {
            lifecycleScope.launch {
                recorderJob?.cancelAndJoin()
                task()
            }
        } else {
            task()
        }


        return MicrophoneDeviceState(
            bluetoothAvailable = bluetoothInfo.first || isBluetoothAvailable(),
            bluetoothActive = bluetoothInfo.first,
            deviceName = bluetoothInfo.second,
            bluetoothPreferredByUser = settings.recordingConfiguration.preferBluetoothMic,
            setBluetooth = {
                listener.recordingStarted(createRecorderAndJob(it))
            }
        )
    }

    private fun startRecording() {
        segmenter = liveSettings?.let { LiveSegmenter(it.convert) }
        liveAudioStart = 0
        lastLiveRunEnd = -1
        livePauseHandled = false

        val device = try {
            createRecorderAndJob(settings.recordingConfiguration.preferBluetoothMic)
        } catch (e: SecurityException) {
            // It's possible we may have lost permission, so let's just ask for permission again
            clearCommunicationDevice()
            requestPermission()
            return
        }

        focusAudio()

        listener.recordingStarted(device)

        loadModelJob = lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                try {
                    preloadModels()
                } catch(_: InvalidModelException) {
                    withContext(Dispatchers.Main) {
                        reset()
                        listener.modelLoadingFailed()
                    }
                }
            }
        }
    }

    private val runnerCallback: ModelInferenceCallback = object : ModelInferenceCallback {
        override fun updateStatus(state: InferenceState) {
            listener.decodingStatus(state)
        }

        override fun languageDetected(language: Language) {
            listener.languageDetected(language)
        }

        override fun partialResult(string: String) {
            if(isBlankResult(string)) return
            listener.partialResult(string)
        }
    }

    // Pieces transcribed while recording keep the recording screen as it is: no status changes, and
    // Whisper's token-by-token partials are not shown (the piece's result is, through liveText).
    private val quietCallback: ModelInferenceCallback = object : ModelInferenceCallback {
        override fun updateStatus(state: InferenceState) {}
        override fun languageDetected(language: Language) {}
        override fun partialResult(string: String) {}
    }

    /** Called from the recording loop during a pause; starts transcribing a piece if one is due. */
    private fun maybeStartLivePiece() {
        if (livePauseHandled) return
        if (liveJob?.isActive == true) return
        val start = liveAudioStart
        val end = floatSamples.position()
        // Nothing new said since the last piece, or too little audio to be worth a run.
        if (lastSpeechEndSample <= max(start, lastLiveRunEnd)) return
        if (end - start < LIVE_MIN_PIECE_SAMPLES) return

        livePauseHandled = true
        val samples = floatSamples.array().sliceArray(start until end)
        val tooLong = end - start >= LIVE_MAX_PIECE_SAMPLES
        liveJob = lifecycleScope.launch(Dispatchers.Default) {
            runLivePiece(samples, end, tooLong)
        }
    }

    private suspend fun runLivePiece(samples: FloatArray, end: Int, keepAll: Boolean) {
        loadModelJob?.join()
        val raw = try {
            modelRunner.run(
                samples,
                settings.modelRunConfiguration,
                settings.decodingConfiguration,
                quietCallback
            ).trim()
        } catch (e: InferenceCancelledException) {
            return
        }
        val seg = segmenter ?: return
        val result = seg.onPiece(if (isBlankResult(raw)) "" else raw, final = keepAll)
        lastLiveRunEnd = end
        if (result.advanceAudioStart) liveAudioStart = end

        val committed = seg.committed
        val display = seg.display()
        withContext(Dispatchers.Main) {
            listener.liveText(committed, display)
        }
    }

    /** The end of a live recording: transcribe only what the pieces haven't settled yet. */
    private suspend fun runLiveFinal(seg: LiveSegmenter) {
        liveJob?.join()
        loadModelJob?.join()

        val start = liveAudioStart
        val recordedSamples = floatSamples.position()
        val endSample = if (stoppedOnSilence && lastSpeechEndSample > 0) {
            min(recordedSamples, lastSpeechEndSample + SILENCE_KEPT_AFTER_SPEECH_SAMPLES)
        } else {
            recordedSamples
        }
        val noNewSpeech = lastSpeechEndSample <= max(start, lastLiveRunEnd)

        if (stoppedOnSilence && lastLiveRunEnd >= start && noNewSpeech) {
            // Auto-stop after a pause the last piece already covered: its transcript stands.
            seg.keepPending()
        } else if (endSample - start < LIVE_MIN_FINAL_SAMPLES) {
            seg.keepPending()
        } else {
            val samples = floatSamples.array().sliceArray(start until endSample)
            val finalCallback = object : ModelInferenceCallback {
                override fun updateStatus(state: InferenceState) {
                    listener.decodingStatus(state)
                }
                override fun languageDetected(language: Language) {
                    listener.languageDetected(language)
                }
                override fun partialResult(string: String) {
                    if (isBlankResult(string)) return
                    val committed = seg.committed
                    val preview = seg.previewOf(string)
                    lifecycleScope.launch(Dispatchers.Main) {
                        listener.liveText(committed, preview)
                    }
                }
            }
            val raw = try {
                modelRunner.run(
                    samples,
                    settings.modelRunConfiguration,
                    settings.decodingConfiguration,
                    finalCallback
                ).trim()
            } catch (e: InferenceCancelledException) {
                yield()
                return
            }
            seg.onPiece(if (isBlankResult(raw)) "" else raw, final = true)
        }

        val text = seg.committed
        yield()
        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                yield()
                listener.finished(text)
            }
        }
    }

    private suspend fun runModel() {
        segmenter?.let {
            runLiveFinal(it)
            return
        }

        loadModelJob?.let {
            if (it.isActive) {
                println("Model was not finished loading...")
                it.join()
            }
        }

        // Only after auto-stop: the VAD then heard over a second of silence at the end, so cutting
        // it can't lose speech. A recording the user stopped by hand is sent whole.
        val recordedSamples = floatSamples.position()
        val endSample = if (stoppedOnSilence && lastSpeechEndSample > 0) {
            min(recordedSamples, lastSpeechEndSample + SILENCE_KEPT_AFTER_SPEECH_SAMPLES)
        } else {
            recordedSamples
        }
        val floatArray = floatSamples.array().sliceArray(0 until endSample)

        yield()
        val outputText = try {
             modelRunner.run(
                floatArray,
                settings.modelRunConfiguration,
                settings.decodingConfiguration,
                runnerCallback
            ).trim()
        }catch(e: InferenceCancelledException) {
            yield()
            return
        }

        val text = when {
            isBlankResult(outputText) -> ""
            else -> outputText
        }

        yield()
        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                yield()
                listener.finished(text)
            }
        }
    }

    private fun onFinishRecording() {
        recorderJob?.cancel()

        if (!isRecording) {
            throw IllegalStateException("Should not call onFinishRecording when not recording")
        }

        isRecording = false
        recorder?.stop()

        listener.processing()

        modelJob = lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                runModel()
            }
        }
    }
}