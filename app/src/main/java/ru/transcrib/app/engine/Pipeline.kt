package ru.transcrib.app.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import ru.transcrib.app.audio.AacEncoder
import ru.transcrib.app.audio.AudioDecoder
import ru.transcrib.app.audio.Leveler
import ru.transcrib.app.audio.PcmFile
import ru.transcrib.app.model.Transcript
import ru.transcrib.app.model.Utterance
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

enum class Stage(val title: String) {
    LOADING("Загрузка моделей"),
    DECODING("Подготовка звука"),
    RECOGNIZING("Распознавание речи"),
    DIARIZING("Разделение по спикерам"),
    SAVING("Сохранение"),
}

class JobRequest(
    val id: String,
    val uri: Uri,
    val title: String,
    /** A recording made by the app itself; deleted after a successful transcription. */
    val ownRecording: File? = null,
    /**
     * Re-recognition of an existing transcript from its own audio.m4a: the audio is not re-encoded,
     * and on failure the existing transcript is kept.
     */
    val rerun: Boolean = false,
    /** Keeps the original date when a transcript is recognized again. */
    val createdAt: Long? = null,
)

/**
 * decode → VAD → GigaAM → diarization → sentences with speakers.
 */
class Pipeline(
    private val context: Context,
    private val models: Models,
) {
    fun interface Progress {
        fun update(stage: Stage, fraction: Float)
    }

    fun run(
        job: JobRequest,
        outDir: File,
        progress: Progress,
        isCancelled: () -> Boolean,
        /** Receives the text-only result before diarization, so a crash there does not lose it. */
        checkpoint: (Transcript) -> Unit = {},
    ): Transcript {
        val started = System.currentTimeMillis()
        outDir.mkdirs()
        val pcmFile = File(context.cacheDir, "job_${job.id}.pcm")
        val audioFile = File(outDir, "audio.m4a")
        val w = Weights(0.05f, 0.62f, 0.33f)

        try {
            // 1. Decode to 16 kHz mono PCM.
            progress.update(Stage.DECODING, 0f)
            val numSamples = AudioDecoder(context).decode(
                uri = job.uri,
                pcmOut = pcmFile,
                aac = null,
                onProgress = { progress.update(Stage.DECODING, it * w.decode * 0.8f) },
                isCancelled = isCancelled,
            )
            if (numSamples < SR / 2) throw IOException("Файл пустой или слишком короткий")
            val durationSec = numSamples.toDouble() / SR

            // 2. Even out loudness (quiet speakers); the result is also the playback copy.
            val aac = if (job.rerun) null else runCatching { AacEncoder(audioFile) }
                .onFailure { Log.w(TAG, "AAC encoder unavailable", it) }
                .getOrNull()
            try {
                Leveler.process(
                    file = pcmFile,
                    aac = aac,
                    onProgress = { progress.update(Stage.DECODING, w.decode * (0.8f + 0.2f * it)) },
                    isCancelled = isCancelled,
                )
                aac?.finish()
            } catch (e: Throwable) {
                aac?.abort()
                throw e
            }
            if (aac == null && !job.rerun && job.ownRecording != null) {
                job.ownRecording.copyTo(audioFile, overwrite = true)
            }

            // 3. Load models (first run after start takes a few seconds).
            progress.update(Stage.LOADING, w.decode)
            val recognizer = models.recognizer()

            PcmFile(pcmFile).use { pcm ->
                // 3. VAD + speech recognition.
                val words = ArrayList<Word>()
                val speech = ArrayList<LongRange>()
                recognize(pcm, recognizer, words, speech, isCancelled) { f ->
                    progress.update(Stage.RECOGNIZING, w.decode + f * w.asr)
                }

                fun transcript(utterances: List<Utterance>, numSpeakers: Int) = Transcript(
                    id = job.id,
                    title = job.title,
                    createdAt = job.createdAt ?: System.currentTimeMillis(),
                    durationSec = durationSec,
                    sourceName = job.title,
                    numSpeakers = if (numSpeakers > 1) numSpeakers else -1,
                    speakerNames = emptyMap(),
                    utterances = utterances,
                    processingSec = (System.currentTimeMillis() - started) / 1000.0,
                    source = if (job.ownRecording != null) Transcript.SOURCE_MIC else Transcript.SOURCE_FILE,
                )

                // 4. Speaker diarization.
                var turns: List<SpeakerTurn>? = null
                var numSpeakers = -1
                if (words.isNotEmpty()) {
                    if (!job.rerun) runCatching { checkpoint(transcript(TextAssembler.assemble(words, null), -1)) }
                        .onFailure { Log.w(TAG, "Checkpoint failed", it) }
                    progress.update(Stage.DIARIZING, w.decode + w.asr)
                    turns = try {
                        Diarizer(models).run(pcm, 0, speech, { f ->
                            progress.update(Stage.DIARIZING, w.decode + w.asr + f * w.diar)
                        }, isCancelled)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // Text is still valuable without speakers.
                        Log.e(TAG, "Diarization failed", e)
                        null
                    }
                    numSpeakers = turns?.maxOfOrNull { it.speaker + 1 } ?: -1
                }

                // 5. Sentences.
                progress.update(Stage.SAVING, 0.99f)
                return transcript(TextAssembler.assemble(words, turns), numSpeakers)
            }
        } finally {
            pcmFile.delete()
        }
    }

    private fun recognize(
        pcm: PcmFile,
        recognizer: com.k2fsa.sherpa.onnx.OfflineRecognizer,
        words: MutableList<Word>,
        speech: MutableList<LongRange>,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ) {
        val total = pcm.numSamples
        val vad = models.newVad()
        try {
            // Neighbouring VAD segments are glued into spans of up to MAX_SPAN seconds:
            // longer context gives the model better punctuation and fewer false sentence ends.
            var spanStart = -1L
            var spanEnd = -1L

            fun decodeSpan() {
                if (spanStart < 0) return
                val from = (spanStart - PAD).coerceAtLeast(0)
                val to = (spanEnd + PAD).coerceAtMost(total)
                val samples = pcm.read(from, (to - from).toInt())
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(samples, SR)
                    recognizer.decode(stream)
                    val r = recognizer.getResult(stream)
                    words += TextAssembler.words(r.tokens, r.timestamps, from.toDouble() / SR, to.toDouble() / SR)
                } finally {
                    stream.release()
                }
                spanStart = -1
                onProgress((spanEnd.toFloat() / total).coerceIn(0f, 1f))
            }

            fun onSegment(start: Long, length: Int) {
                val end = start + length
                speech += start until end
                if (spanStart >= 0) {
                    val gap = start - spanEnd
                    if (gap > MAX_GLUE_GAP || end - spanStart > MAX_SPAN) decodeSpan()
                }
                if (spanStart < 0) spanStart = start
                spanEnd = end
            }

            val window = Models.VAD_WINDOW
            val block = window * 64
            val buf = FloatArray(block)
            val frame = FloatArray(window)
            var pos = 0L
            while (pos < total) {
                if (isCancelled()) throw CancellationException()
                val n = pcm.read(pos, buf, 0, block)
                if (n <= 0) break
                var i = 0
                while (i + window <= n) {
                    System.arraycopy(buf, i, frame, 0, window)
                    vad.acceptWaveform(frame)
                    i += window
                }
                if (i < n) {
                    // Tail of the file: pad the last window with silence.
                    frame.fill(0f)
                    System.arraycopy(buf, i, frame, 0, n - i)
                    vad.acceptWaveform(frame)
                }
                while (!vad.empty()) {
                    val seg = vad.front()
                    onSegment(seg.start.toLong(), seg.samples.size)
                    vad.pop()
                }
                pos += n
                if (spanStart < 0) onProgress((pos.toFloat() / total) * 0.98f)
            }
            vad.flush()
            while (!vad.empty()) {
                val seg = vad.front()
                onSegment(seg.start.toLong(), seg.samples.size)
                vad.pop()
            }
            decodeSpan()
        } finally {
            vad.release()
        }
    }

    private class Weights(val decode: Float, val asr: Float, val diar: Float)

    companion object {
        private const val TAG = "Pipeline"
        private const val SR = Models.SAMPLE_RATE
        private const val PAD = SR / 5 // 0.2 s around speech
        private const val MAX_SPAN = 22L * SR
        private const val MAX_GLUE_GAP = SR * 3 / 2 // 1.5 s
    }
}
