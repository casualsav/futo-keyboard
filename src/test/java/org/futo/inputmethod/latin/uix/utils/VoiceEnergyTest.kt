package org.futo.inputmethod.latin.uix.utils

import org.futo.voiceinput.shared.live.VoiceEnergy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceEnergyTest {
    private fun feed(e: VoiceEnergy, rms: Float, frames: Int): Int =
        (0 until frames).count { e.isVoiceRms(rms) }

    @Test
    fun quietRoomThenSoftSpeechCountsAsVoice() {
        val e = VoiceEnergy()
        assertEquals(0, feed(e, 0.001f, 30))
        // Soft speech, well under the 0.01 level the recognizer uses for "has talked".
        assertEquals(20, feed(e, 0.006f, 20))
    }

    @Test
    fun steadyNoiseBecomesTheBackground() {
        val e = VoiceEnergy()
        feed(e, 0.001f, 10)
        // A fan switches on: loud at first, then it is just the background.
        feed(e, 0.02f, 600)
        assertFalse(e.isVoiceRms(0.02f))
        // Speech over the fan still stands out.
        assertTrue(e.isVoiceRms(0.09f))
    }

    @Test
    fun aQuietFrameLowersTheBackgroundAtOnce() {
        val e = VoiceEnergy()
        feed(e, 0.02f, 5)
        e.isVoiceRms(0.002f)
        assertEquals(0.002f, e.background, 1e-6f)
        assertTrue(e.isVoiceRms(0.01f))
    }

    @Test
    fun digitalSilenceIsNeverVoice() {
        val e = VoiceEnergy()
        assertEquals(0, feed(e, 0f, 50))
        assertFalse(e.isVoice(ShortArray(480)))
    }

    @Test
    fun rmsOfAFullScaleSquareWaveIsAboutOne() {
        val frame = ShortArray(480) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        assertEquals(1.0f, VoiceEnergy.rms(frame), 0.01f)
    }
}
