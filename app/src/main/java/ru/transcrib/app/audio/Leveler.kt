package ru.transcrib.app.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Evens out loudness in place so that quiet speakers get recognized and are audible on playback.
 *
 * The speech level is measured over ±1.5 s windows (silence and background are ignored), the gain
 * brings it to about −22 dBFS (up to +26 dB, never more than −3 dB of attenuation), is smoothed to
 * avoid pumping and followed by a soft limiter. On a test dialog with one voice 30 dB quieter this
 * cut GigaAM's word error rate from 48% to 2%, with no change on normal recordings.
 */
object Leveler {
    const val FRAME = 320 // 20 ms at 16 kHz
    private const val TARGET = 0.08f
    private const val MIN_GAIN = 0.7f
    private const val MAX_GAIN = 20f
    private const val WINDOW = 75 // ±1.5 s
    private const val SMOOTH = 12 // ±0.25 s
    private const val CHUNK_FRAMES = 512

    /** Levels the 16-bit PCM [file] in place and feeds the result to [aac], if any. */
    fun process(file: File, aac: AacEncoder?, onProgress: (Float) -> Unit, isCancelled: () -> Boolean) {
        RandomAccessFile(file, "rw").use { raf ->
            val ch = raf.channel
            val total = raf.length() / 2
            val frames = (total / FRAME).toInt()
            val buf = ByteBuffer.allocateDirect(CHUNK_FRAMES * FRAME * 2).order(ByteOrder.LITTLE_ENDIAN)

            // Pass 1: loudness of every 20 ms frame.
            val rms = FloatArray(frames)
            var f = 0
            while (f < frames) {
                if (isCancelled()) throw CancellationException()
                val count = minOf(CHUNK_FRAMES, frames - f)
                read(ch, buf, f.toLong() * FRAME * 2, count * FRAME * 2)
                val sb = buf.asShortBuffer()
                for (k in 0 until count) {
                    var s = 0.0
                    val base = k * FRAME
                    for (j in 0 until FRAME) {
                        val v = sb.get(base + j) / 32768.0
                        s += v * v
                    }
                    rms[f + k] = sqrt(s / FRAME).toFloat()
                }
                f += count
                onProgress(0.3f * f / frames.coerceAtLeast(1))
            }

            val gains = gainCurve(rms)

            // Pass 2: apply the gain, write back, encode the playback copy.
            var shorts = ShortArray(CHUNK_FRAMES * FRAME)
            var pos = 0L
            while (pos < total) {
                if (isCancelled()) throw CancellationException()
                val count = minOf(CHUNK_FRAMES.toLong() * FRAME, total - pos).toInt()
                read(ch, buf, pos * 2, count * 2)
                val sb = buf.asShortBuffer()
                if (shorts.size < count) shorts = ShortArray(count)
                for (j in 0 until count) {
                    val i = pos + j
                    val g = gainAt(gains, i)
                    var y = sb.get(j) / 32768f * g
                    val a = abs(y)
                    if (a > 0.9f) y = sign(y) * (0.9f + 0.1f * tanh((a - 0.9f) / 0.1f))
                    shorts[j] = (y * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                }
                buf.clear()
                buf.asShortBuffer().put(shorts, 0, count)
                buf.limit(count * 2)
                var written = 0
                while (written < count * 2) {
                    written += ch.write(buf, pos * 2 + written)
                }
                aac?.write(shorts, count)
                pos += count
                onProgress(0.3f + 0.7f * pos / total.coerceAtLeast(1))
            }
        }
    }

    /** Gain per frame; exposed for tests. */
    fun gainCurve(rms: FloatArray): FloatArray {
        val n = rms.size
        if (n == 0) return FloatArray(0)
        val nonZero = rms.filter { it > 1e-5f }.sorted()
        // Background level: the quietest frames. Speech is anything clearly (8 dB) above it.
        val noise = if (nonZero.isEmpty()) 1e-4f else nonZero[(nonZero.size * 0.05).toInt()]
        val speechThreshold = maxOf(noise * 2.5f, 2e-4f)

        // Prefix sums of speech energy and speech frame counts.
        val ce = DoubleArray(n + 1)
        val cc = IntArray(n + 1)
        for (i in 0 until n) {
            val speech = rms[i] > speechThreshold
            ce[i + 1] = ce[i] + if (speech) rms[i].toDouble() * rms[i] else 0.0
            cc[i + 1] = cc[i] + if (speech) 1 else 0
        }
        val raw = FloatArray(n)
        var level = TARGET
        for (i in 0 until n) {
            val a = maxOf(0, i - WINDOW)
            val b = minOf(n, i + WINDOW + 1)
            val cnt = cc[b] - cc[a]
            if (cnt >= 5) level = sqrt((ce[b] - ce[a]) / cnt).toFloat()
            raw[i] = (TARGET / level.coerceAtLeast(1e-6f)).coerceIn(MIN_GAIN, MAX_GAIN)
        }
        // Moving average against pumping.
        val out = FloatArray(n)
        var sum = 0.0
        val w = 2 * SMOOTH + 1
        for (k in -SMOOTH..SMOOTH) sum += raw[k.coerceIn(0, n - 1)]
        for (i in 0 until n) {
            out[i] = (sum / w).toFloat()
            sum += raw[(i + SMOOTH + 1).coerceAtMost(n - 1)] - raw[(i - SMOOTH).coerceAtLeast(0)]
        }
        return out
    }

    private fun gainAt(gains: FloatArray, sample: Long): Float {
        if (gains.isEmpty()) return 1f
        val x = (sample - FRAME / 2).toDouble() / FRAME
        if (x <= 0) return gains[0]
        val f = x.toInt()
        if (f >= gains.size - 1) return gains[gains.size - 1]
        val t = (x - f).toFloat()
        return gains[f] + (gains[f + 1] - gains[f]) * t
    }

    private fun read(ch: FileChannel, buf: ByteBuffer, position: Long, bytes: Int) {
        buf.clear()
        buf.limit(bytes)
        var done = 0
        while (done < bytes) {
            val r = ch.read(buf, position + done)
            if (r <= 0) break
            done += r
        }
        buf.flip()
    }
}
