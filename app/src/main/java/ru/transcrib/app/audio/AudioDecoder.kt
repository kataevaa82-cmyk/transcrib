package ru.transcrib.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException

class NoAudioTrackException : IOException("В файле нет звуковой дорожки")

/**
 * Decodes the audio track of any audio/video file supported by the platform
 * (mp3, m4a/aac, ogg/opus, flac, wav, amr, 3gp, mp4, mkv, webm...) into
 * 16 kHz mono 16-bit little-endian PCM written to [pcmOut].
 */
class AudioDecoder(private val context: Context) {

    /** @return number of 16 kHz samples written. */
    fun decode(
        uri: Uri,
        pcmOut: File,
        aac: AacEncoder?,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Long {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Не удалось открыть файл")
        pfd.use {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(pfd.fileDescriptor)
                return decodeTrack(extractor, pcmOut, aac, onProgress, isCancelled)
            } finally {
                extractor.release()
            }
        }
    }

    private fun decodeTrack(
        extractor: MediaExtractor,
        pcmOut: File,
        aac: AacEncoder?,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Long {
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) throw NoAudioTrackException()
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

        if (durationUs > 0) {
            // 16 kHz 16-bit PCM plus some headroom for the playback copy.
            val need = durationUs / 1_000_000 * TARGET_RATE * 2 + 64L * 1024 * 1024
            val free = pcmOut.parentFile?.usableSpace ?: Long.MAX_VALUE
            if (free in 1 until need) {
                throw IOException(
                    "Недостаточно места: нужно около ${need / 1_048_576} МБ, свободно ${free / 1_048_576} МБ",
                )
            }
        }

        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            throw IOException("Формат звука не поддерживается устройством ($mime)", e)
        }

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var resampler = Resampler(sampleRate, TARGET_RATE)

        var written = 0L
        val out = BufferedOutputStream(FileOutputStream(pcmOut), 1 shl 16)
        var shorts = ShortArray(4096)
        var bytes = ByteArray(8192)

        val sink: (FloatArray, Int) -> Unit = { data, n ->
            if (shorts.size < n) {
                shorts = ShortArray(n)
                bytes = ByteArray(n * 2)
            }
            for (i in 0 until n) {
                val v = (data[i] * 32767f).toInt().coerceIn(-32768, 32767)
                shorts[i] = v.toShort()
                bytes[2 * i] = (v and 0xff).toByte()
                bytes[2 * i + 1] = ((v shr 8) and 0xff).toByte()
            }
            out.write(bytes, 0, n * 2)
            aac?.write(shorts, n)
            written += n
        }

        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var mono = FloatArray(8192)
            var inputDone = false
            var idleAfterInput = 0
            var lastProgress = -1f

            loop@ while (true) {
                if (isCancelled()) throw CancellationException()
                try {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val ib = codec.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(ib, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime.coerceAtLeast(0), 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = codec.outputFormat
                            val newRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                            encoding = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else {
                                AudioFormat.ENCODING_PCM_16BIT
                            }
                            if (newRate != sampleRate) {
                                resampler.flush(sink)
                                sampleRate = newRate
                                resampler = Resampler(sampleRate, TARGET_RATE)
                            }
                        }

                        outIdx >= 0 -> {
                            idleAfterInput = 0
                            if (info.size > 0) {
                                val ob = codec.getOutputBuffer(outIdx)!!
                                ob.position(info.offset)
                                ob.limit(info.offset + info.size)
                                val frames = info.size / (bytesPerSample(encoding) * channels)
                                if (mono.size < frames) mono = FloatArray(frames)
                                toMono(ob.order(ByteOrder.LITTLE_ENDIAN), frames, channels, encoding, mono)
                                resampler.process(mono, frames, sink)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (durationUs > 0) {
                                val p = (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                                if (p - lastProgress >= 0.005f) {
                                    lastProgress = p
                                    onProgress(p)
                                }
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@loop
                        }

                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone -> {
                            // Some decoders never signal EOS on broken files.
                            if (++idleAfterInput > 300) break@loop
                        }
                    }
                } catch (e: IllegalStateException) {
                    // Corrupted tail of a file: keep what was decoded so far.
                    if (written > TARGET_RATE) {
                        Log.w(TAG, "Decoder error after $written samples, stopping early", e)
                        break@loop
                    }
                    throw IOException("Не удалось декодировать звук", e)
                }
            }
            resampler.flush(sink)
            out.flush()
        } finally {
            runCatching { out.close() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        onProgress(1f)
        return written
    }

    private fun bytesPerSample(encoding: Int) = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_FLOAT -> 4
        ENCODING_PCM_24BIT_PACKED -> 3
        ENCODING_PCM_32BIT -> 4
        else -> 2
    }

    private fun toMono(buf: ByteBuffer, frames: Int, channels: Int, encoding: Int, out: FloatArray) {
        val inv = 1f / channels
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = buf.asFloatBuffer()
                for (i in 0 until frames) {
                    var s = 0f
                    for (c in 0 until channels) s += fb.get()
                    out[i] = s * inv
                }
            }

            AudioFormat.ENCODING_PCM_8BIT -> {
                for (i in 0 until frames) {
                    var s = 0f
                    for (c in 0 until channels) s += ((buf.get().toInt() and 0xff) - 128) / 128f
                    out[i] = s * inv
                }
            }

            ENCODING_PCM_24BIT_PACKED -> {
                for (i in 0 until frames) {
                    var s = 0f
                    for (c in 0 until channels) {
                        val b0 = buf.get().toInt() and 0xff
                        val b1 = buf.get().toInt() and 0xff
                        val b2 = buf.get().toInt()
                        s += ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
                    }
                    out[i] = s * inv
                }
            }

            ENCODING_PCM_32BIT -> {
                val ib = buf.asIntBuffer()
                for (i in 0 until frames) {
                    var s = 0f
                    for (c in 0 until channels) s += ib.get() / 2147483648f
                    out[i] = s * inv
                }
            }

            else -> {
                val sb = buf.asShortBuffer()
                for (i in 0 until frames) {
                    var s = 0f
                    for (c in 0 until channels) s += sb.get() / 32768f
                    out[i] = s * inv
                }
            }
        }
    }

    companion object {
        const val TARGET_RATE = 16000
        private const val TAG = "AudioDecoder"
        private const val ENCODING_PCM_24BIT_PACKED = 21
        private const val ENCODING_PCM_32BIT = 22
    }
}
