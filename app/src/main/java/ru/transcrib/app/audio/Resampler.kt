package ru.transcrib.app.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Streaming band-limited resampler (windowed sinc, Blackman window) for arbitrary rate pairs.
 * Output sample m corresponds to input position m * inRate / outRate, computed exactly
 * with integer arithmetic so there is no drift on long recordings.
 */
class Resampler(private val inRate: Int, private val outRate: Int) {
    private val passthrough = inRate == outRate

    // Cutoff relative to the input Nyquist frequency.
    private val cutoff = min(1.0, outRate.toDouble() / inRate) * 0.94
    private val halfWidth = ceil(ZERO_CROSSINGS / cutoff).toInt()
    private val table: FloatArray

    private var buf = FloatArray(1 shl 15)
    private var bufLen = 0
    private var bufStart = 0L // absolute input index of buf[0]
    private var inputTotal = 0L
    private var outIndex = 0L
    private val out = FloatArray(4096)

    init {
        val n = halfWidth * PHASES + 2
        table = FloatArray(n)
        for (i in 0 until n) {
            val x = i.toDouble() / PHASES // distance in input samples
            if (x >= halfWidth) {
                table[i] = 0f
                continue
            }
            val arg = PI * cutoff * x
            val sinc = if (x == 0.0) 1.0 else sin(arg) / arg
            val w = x / halfWidth // 0..1
            val blackman = 0.42 + 0.5 * cos(PI * w) + 0.08 * cos(2 * PI * w)
            table[i] = (cutoff * sinc * blackman).toFloat()
        }
    }

    /** Feeds [n] samples of [input]; resampled output is handed to [sink] (may be called several times). */
    fun process(input: FloatArray, n: Int, sink: (FloatArray, Int) -> Unit) {
        if (n <= 0) return
        if (passthrough) {
            sink(input, n)
            return
        }
        append(input, n)
        produce(sink, flushing = false)
    }

    fun flush(sink: (FloatArray, Int) -> Unit) {
        if (passthrough) return
        val zeros = FloatArray(halfWidth + 1)
        val realTotal = inputTotal
        append(zeros, zeros.size)
        inputTotal = realTotal
        produce(sink, flushing = true)
    }

    private fun append(input: FloatArray, n: Int) {
        if (bufLen + n > buf.size) {
            var cap = buf.size
            while (bufLen + n > cap) cap *= 2
            buf = buf.copyOf(cap)
        }
        System.arraycopy(input, 0, buf, bufLen, n)
        bufLen += n
        inputTotal += n
    }

    private fun produce(sink: (FloatArray, Int) -> Unit, flushing: Boolean) {
        var outN = 0
        val available = bufStart + bufLen
        while (true) {
            val num = outIndex * inRate
            val center = num / outRate
            val frac = (num % outRate).toDouble() / outRate
            if (flushing) {
                if (center >= inputTotal) break
            } else if (center + halfWidth + 1 >= available) {
                break
            }
            val t = center + frac // input position
            var acc = 0.0f
            val kStart = (center - halfWidth + 1).coerceAtLeast(0)
            val kEnd = min(center + halfWidth, available - 1)
            var k = kStart
            while (k <= kEnd) {
                val idx = (k - bufStart).toInt()
                if (idx >= 0) {
                    var d = t - k
                    if (d < 0) d = -d
                    val pos = d * PHASES
                    val i0 = floor(pos).toInt()
                    if (i0 < table.size - 1) {
                        val f = (pos - i0).toFloat()
                        val h = table[i0] + (table[i0 + 1] - table[i0]) * f
                        acc += buf[idx] * h
                    }
                }
                k++
            }
            out[outN++] = acc
            if (outN == out.size) {
                sink(out, outN)
                outN = 0
            }
            outIndex++
        }
        if (outN > 0) sink(out, outN)

        // Drop input that is no longer needed.
        val nextCenter = (outIndex * inRate) / outRate
        val keepFrom = nextCenter - halfWidth - 1
        val drop = (keepFrom - bufStart).toInt()
        if (drop > 0 && drop <= bufLen) {
            System.arraycopy(buf, drop, buf, 0, bufLen - drop)
            bufLen -= drop
            bufStart += drop
        }
    }

    companion object {
        private const val ZERO_CROSSINGS = 12
        private const val PHASES = 128
    }
}
