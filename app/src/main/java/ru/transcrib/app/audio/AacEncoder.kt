package ru.transcrib.app.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder

/**
 * Encodes mono 16-bit PCM into an AAC .m4a file. Used to keep a compact copy of the audio
 * next to the transcript so it can be played back with sentence-level seeking.
 */
class AacEncoder(
    private val outFile: File,
    private val sampleRate: Int = 16000,
    bitRate: Int = 40000,
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var samplesQueued = 0L
    private var released = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            muxer.release()
            released = true
            throw e
        }
    }

    fun write(pcm: ShortArray, n: Int) {
        var offset = 0
        while (offset < n) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = codec.getInputBuffer(idx)!!
                buf.clear()
                buf.order(ByteOrder.LITTLE_ENDIAN)
                val count = minOf(n - offset, buf.capacity() / 2)
                buf.asShortBuffer().put(pcm, offset, count)
                val pts = samplesQueued * 1_000_000L / sampleRate
                codec.queueInputBuffer(idx, 0, count * 2, pts, 0)
                samplesQueued += count
                offset += count
            }
            drain(false)
        }
    }

    /** Finishes the file. */
    fun finish() {
        var eosQueued = false
        while (!eosQueued) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val pts = samplesQueued * 1_000_000L / sampleRate
                codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                eosQueued = true
            } else {
                drain(false)
            }
        }
        drain(true)
        release()
    }

    fun abort() {
        release()
        outFile.delete()
    }

    private fun release() {
        if (released) return
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (muxerStarted) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }

    private fun drain(untilEos: Boolean) {
        var idleRounds = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos || ++idleRounds > 300) return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)!!
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}
