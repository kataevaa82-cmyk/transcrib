package ru.transcrib.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {
    private fun tone(rate: Int, freq: Double, seconds: Double) =
        FloatArray((rate * seconds).toInt()) { (0.5 * sin(2 * PI * freq * it / rate)).toFloat() }

    private fun resample(input: FloatArray, from: Int, to: Int, chunk: Int = 1234): FloatArray {
        val r = Resampler(from, to)
        val out = ArrayList<Float>()
        val sink: (FloatArray, Int) -> Unit = { d, n -> for (i in 0 until n) out += d[i] }
        var i = 0
        while (i < input.size) {
            val n = minOf(chunk, input.size - i)
            r.process(input.copyOfRange(i, i + n), n, sink)
            i += n
        }
        r.flush(sink)
        return out.toFloatArray()
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return sqrt(s / (to - from))
    }

    @Test
    fun keepsLengthAndPassband() {
        for (rate in listOf(8000, 22050, 44100, 48000)) {
            val x = tone(rate, 1000.0, 2.0)
            val y = resample(x, rate, 16000)
            assertEquals("length for $rate", 32000.0, y.size.toDouble(), 2.0)
            val r = rms(y, 2000, 30000)
            assertEquals("amplitude for $rate", 0.5 / sqrt(2.0), r, 0.01)
            // Compare with the ideal 1 kHz tone at 16 kHz.
            var err = 0.0
            for (i in 2000 until 30000) {
                val ideal = 0.5 * sin(2 * PI * 1000.0 * i / 16000)
                err = maxOf(err, abs(y[i] - ideal))
            }
            assertTrue("waveform error $err for $rate", err < 0.02)
        }
    }

    @Test
    fun removesAliases() {
        // 11 kHz cannot be represented at 16 kHz and must not fold back as 5 kHz.
        val x = tone(44100, 11000.0, 1.0)
        val y = resample(x, 44100, 16000)
        assertTrue(rms(y, 1000, 15000) < 0.005)
    }

    @Test
    fun chunkingDoesNotMatter() {
        val x = tone(44100, 440.0, 1.0)
        val a = resample(x, 44100, 16000, chunk = 100)
        val b = resample(x, 44100, 16000, chunk = 44100)
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals(a[i], b[i], 1e-5f)
    }
}
