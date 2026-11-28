package org.futo.voiceinput.shared.types

import org.futo.voiceinput.shared.ui.MicrophoneDeviceState

enum class MagnitudeState {
    NOT_TALKED_YET, MIC_MAY_BE_BLOCKED, MIC_IN_USE, TALKING
}

interface AudioRecognizerListener {
    fun cancelled()
    fun finished(result: String)
    fun languageDetected(language: Language)
    fun partialResult(result: String)
    fun decodingStatus(status: InferenceState)
    fun modelLoadingFailed()

    fun loading()
    fun needPermission(onResult: (Boolean) -> Unit)

    fun recordingStarted(device: MicrophoneDeviceState)
    fun updateMagnitude(magnitude: Float, state: MagnitudeState)

    fun processing()

    /**
     * Live voice typing: [committed] is final (sentences up to the last spoken mark), [display]
     * is that plus the sentence still being said, for showing in the text field.
     */
    fun liveText(committed: String, display: String) {}

    /** Called once per recording, when the mic first gives real (not silent) audio. */
    fun listening() {}

    /** Another app took the mic; [listening] comes again once it is back. */
    fun micTaken() {}

    /** Called when the recording ends on its own, after a pause or when out of space. */
    fun stoppedListening() {}
}