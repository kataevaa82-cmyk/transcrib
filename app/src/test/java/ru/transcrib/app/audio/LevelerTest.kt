package ru.transcrib.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelerTest {
    /** Speech-like frames: talk with short pauses at a constant background level. */
    private fun speech(level: Float, frames: Int, background: Float) = FloatArray(frames) { i ->
        if (i % 25 < 20) level else background
    }

    @Test
    fun quietSpeakerIsBoostedLoudIsKept() {
        val bg = 0.0003f // about -70 dBFS room noise
        val g = Leveler.gainCurve(speech(0.08f, 500, bg) + speech(0.004f, 500, bg))
        assertEquals(1f, g[250], 0.15f)
        assertTrue("quiet part gain ${g[750]}", g[750] > 12f)
        assertTrue(g.all { it in 0.69f..20.01f })
    }

    @Test
    fun noisyPausesDoNotHideQuietSpeech() {
        // Background between the loud speaker's phrases is louder than the room tone later.
        val g = Leveler.gainCurve(speech(0.08f, 500, 0.0016f) + speech(0.004f, 500, 0.00008f))
        assertTrue("quiet part gain ${g[750]}", g[750] > 12f)
    }

    @Test
    fun silenceDoesNotExplode() {
        val g = Leveler.gainCurve(FloatArray(300) { 1e-6f })
        assertTrue(g.all { !it.isNaN() && it in 0.69f..20.01f })
        assertEquals(0, Leveler.gainCurve(FloatArray(0)).size)
    }
}
