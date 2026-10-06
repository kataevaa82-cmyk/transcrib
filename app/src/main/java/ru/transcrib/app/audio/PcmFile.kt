package ru.transcrib.app.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Random access to a 16 kHz mono 16-bit PCM file as float samples. */
class PcmFile(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val channel = raf.channel
    val numSamples: Long = raf.length() / 2
    private var byteBuf: ByteBuffer = ByteBuffer.allocateDirect(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)

    /** Reads samples [start, start + count) clamped to the file; returns a new array. */
    fun read(start: Long, count: Int): FloatArray {
        val s = start.coerceIn(0, numSamples)
        val n = minOf(count.toLong(), numSamples - s).toInt().coerceAtLeast(0)
        val out = FloatArray(n)
        read(s, out, 0, n)
        return out
    }

    fun read(start: Long, dst: FloatArray, dstOffset: Int, count: Int): Int {
        var done = 0
        var pos = start * 2
        while (done < count) {
            val want = minOf(count - done, byteBuf.capacity() / 2)
            byteBuf.clear()
            byteBuf.limit(want * 2)
            var readBytes = 0
            while (byteBuf.hasRemaining()) {
                val r = channel.read(byteBuf, pos + readBytes)
                if (r <= 0) break
                readBytes += r
            }
            byteBuf.flip()
            val got = readBytes / 2
            if (got == 0) break
            val sb = byteBuf.asShortBuffer()
            for (i in 0 until got) dst[dstOffset + done + i] = sb.get(i) / 32768f
            done += got
            pos += got * 2L
        }
        return done
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { raf.close() }
    }
}
