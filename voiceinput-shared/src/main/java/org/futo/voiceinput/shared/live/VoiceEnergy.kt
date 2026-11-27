package org.futo.voiceinput.shared.live

import kotlin.math.sqrt

/**
 * A second opinion on whether a 30 ms frame holds the user's voice, from its loudness against
 * the background. The WebRTC VAD in its most aggressive mode can call soft or noise-filtered
 * speech silence, and auto-stop then ends the recording while the user is still talking. A frame
 * counts as voice here when it is clearly louder than the background level, which follows the
 * quietest recent frames: it drops at once to a quieter frame and rises only slowly, so steady
 * noise like a fan or a café becomes the background and speech stands out above it.
 */
class VoiceEnergy(
    private val ratio: Float = 3.0f,
    private val minVoiceRms: Float = 0.004f,
    private val riseRate: Float = 0.01f
) {
    var background: Float = -1f
        private set

    fun isVoice(frame: ShortArray, n: Int = frame.size): Boolean = isVoiceRms(rms(frame, n))

    fun isVoiceRms(rms: Float): Boolean {
        if (background < 0f) {
            background = rms
            return false
        }
        val voice = rms > minVoiceRms && rms > background * ratio
        background = if (rms < background) rms else background + (rms - background) * riseRate
        return voice
    }

    companion object {
        fun rms(frame: ShortArray, n: Int = frame.size): Float {
            if (n <= 0) return 0f
            var sum = 0.0
            for (i in 0 until n) {
                val v = frame[i] / 32768.0
                sum += v * v
            }
            return sqrt(sum / n).toFloat()
        }
    }
}
