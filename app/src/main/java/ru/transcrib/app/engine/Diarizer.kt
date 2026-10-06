package ru.transcrib.app.engine

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment
import ru.transcrib.app.audio.PcmFile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.sqrt

/**
 * Speaker diarization that scales to multi-hour recordings.
 *
 * sherpa-onnx diarization needs the whole signal in memory, so long files are cut into
 * chunks of several minutes (in pauses, when possible). Each chunk is diarized on its own,
 * every local speaker gets a centroid voice embedding, and the centroids are clustered
 * globally so that "Спикер 1" means the same person across the whole recording.
 */
class Diarizer(private val models: Models) {

    fun run(
        pcm: PcmFile,
        numSpeakers: Int,
        speech: List<LongRange>,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): List<SpeakerTurn> {
        val total = pcm.numSamples
        if (total < SR) return emptyList()
        val bounds = chunkBounds(total, speech)

        if (bounds.size == 2) {
            val clustering = if (numSpeakers > 0) {
                FastClusteringConfig(numClusters = numSpeakers)
            } else {
                FastClusteringConfig(numClusters = -1, threshold = AUTO_THRESHOLD)
            }
            val samples = pcm.read(0, total.toInt())
            val segs = process(samples, clustering) { onProgress(it) }
            if (isCancelled()) throw CancellationException()
            return relabel(segs.map { SpeakerTurn(it.start.toDouble(), it.end.toDouble(), it.speaker) })
        }

        // Long recording: diarize chunk by chunk.
        val numChunks = bounds.size - 1
        val local = ArrayList<LocalSpeaker>()
        val localTurns = ArrayList<Pair<LocalSpeaker, SpeakerTurn>>()
        for (c in 0 until numChunks) {
            if (isCancelled()) throw CancellationException()
            val start = bounds[c]
            val samples = pcm.read(start, (bounds[c + 1] - start).toInt())
            val segs = process(samples, FastClusteringConfig(numClusters = -1, threshold = AUTO_THRESHOLD)) {
                onProgress((c + it * 0.85f) / numChunks)
            }
            if (isCancelled()) throw CancellationException()
            val offset = start.toDouble() / SR
            for ((spk, list) in segs.groupBy { it.speaker }) {
                val ls = LocalSpeaker(c, spk, list.sumOf { (it.end - it.start).toDouble() })
                ls.centroid = centroid(samples, list)
                local += ls
                list.forEach { localTurns += ls to SpeakerTurn(it.start + offset, it.end + offset, 0) }
            }
            onProgress((c + 1f) / numChunks)
        }

        val labels = clusterGlobally(local, numSpeakers)
        val turns = localTurns.map { (ls, t) -> t.copy(speaker = labels.getValue(ls)) }
        return relabel(turns.sortedBy { it.start })
    }

    private fun process(
        samples: FloatArray,
        clustering: FastClusteringConfig,
        progress: (Float) -> Unit,
    ): Array<OfflineSpeakerDiarizationSegment> {
        val d = models.diarizer(clustering)
        return d.processWithCallback(samples, ProgressCallback(progress))
    }

    /** Chunk boundaries in samples, placed in the longest pause near each target position. */
    private fun chunkBounds(total: Long, speech: List<LongRange>): List<Long> {
        if (total <= SINGLE_PASS_MAX) return listOf(0L, total)
        val gaps = ArrayList<Pair<Long, Long>>() // (midpoint, length)
        for (i in 1 until speech.size) {
            val a = speech[i - 1].last
            val b = speech[i].first
            if (b > a) gaps += ((a + b) / 2) to (b - a)
        }
        val bounds = arrayListOf(0L)
        var target = CHUNK
        while (total - target > MIN_LAST_CHUNK) {
            val window = 60L * SR
            val best = gaps.filter { it.first in (target - window)..(target + window) }.maxByOrNull { it.second }
            val cut = best?.first ?: target
            bounds += cut
            target = cut + CHUNK
        }
        bounds += total
        return bounds
    }

    private fun centroid(samples: FloatArray, segs: List<OfflineSpeakerDiarizationSegment>): FloatArray? {
        val embedder = models.embedder()
        var acc: FloatArray? = null
        var used = 0.0
        for (s in segs.sortedByDescending { it.end - it.start }) {
            if (used >= 40.0) break
            val dur = (s.end - s.start).toDouble()
            if (dur < 0.8 && acc != null) break
            val from = (s.start * SR).toInt().coerceIn(0, samples.size)
            val to = minOf((s.end * SR).toInt(), from + 12 * SR, samples.size)
            if (to - from < SR / 4) continue
            val stream = embedder.createStream()
            try {
                stream.acceptWaveform(samples.copyOfRange(from, to), SR)
                stream.inputFinished()
                if (!embedder.isReady(stream)) continue
                val e = normalize(embedder.compute(stream))
                val w = (to - from).toFloat() / SR
                val a = acc ?: FloatArray(e.size).also { acc = it }
                for (i in e.indices) a[i] += e[i] * w
                used += w
            } finally {
                stream.release()
            }
        }
        return acc?.let { normalize(it) }
    }

    /**
     * Average-linkage agglomerative clustering of local speakers. Two speakers found in the same
     * chunk were already judged different, so they are only merged when a fixed speaker count
     * cannot be reached otherwise.
     */
    private fun clusterGlobally(local: List<LocalSpeaker>, numSpeakers: Int): Map<LocalSpeaker, Int> {
        val withEmb = local.filter { it.centroid != null }
        val clusters = withEmb.map { mutableListOf(it) }.toMutableList()

        fun similarity(a: List<LocalSpeaker>, b: List<LocalSpeaker>): Double {
            var sum = 0.0
            var wsum = 0.0
            for (x in a) for (y in b) {
                val w = x.duration * y.duration
                sum += dot(x.centroid!!, y.centroid!!) * w
                wsum += w
            }
            return if (wsum > 0) sum / wsum else -1.0
        }

        fun conflict(a: List<LocalSpeaker>, b: List<LocalSpeaker>) =
            a.any { x -> b.any { y -> x.chunk == y.chunk } }

        while (clusters.size > 1) {
            var best = -2.0
            var bi = -1
            var bj = -1
            var bestAny = -2.0
            var ai = -1
            var aj = -1
            for (i in clusters.indices) for (j in i + 1 until clusters.size) {
                val s = similarity(clusters[i], clusters[j])
                if (s > bestAny) {
                    bestAny = s; ai = i; aj = j
                }
                if (s > best && !conflict(clusters[i], clusters[j])) {
                    best = s; bi = i; bj = j
                }
            }
            if (numSpeakers > 0) {
                if (clusters.size <= numSpeakers) break
                if (bi < 0) {
                    bi = ai; bj = aj
                }
            } else {
                if (bi < 0 || 1.0 - best > AUTO_THRESHOLD) break
            }
            clusters[bi].addAll(clusters[bj])
            clusters.removeAt(bj)
        }

        val labels = HashMap<LocalSpeaker, Int>()
        clusters.forEachIndexed { idx, members -> members.forEach { labels[it] = idx } }
        // Speakers too short for an embedding: attach to the main voice of their chunk.
        for (ls in local) {
            if (ls in labels) continue
            val main = local.filter { it.chunk == ls.chunk && it in labels }.maxByOrNull { it.duration }
            labels[ls] = main?.let { labels[it] } ?: 0
        }
        return labels
    }

    /** Numbers speakers in order of first appearance and merges touching turns. */
    private fun relabel(turns: List<SpeakerTurn>): List<SpeakerTurn> {
        val order = HashMap<Int, Int>()
        val out = ArrayList<SpeakerTurn>()
        for (t in turns.sortedBy { it.start }) {
            val id = order.getOrPut(t.speaker) { order.size }
            val last = out.lastOrNull()
            if (last != null && last.speaker == id && t.start - last.end < 0.3) {
                out[out.size - 1] = last.copy(end = maxOf(last.end, t.end))
            } else {
                out += SpeakerTurn(t.start, t.end, id)
            }
        }
        return out
    }

    /**
     * The JNI side looks up exactly `invoke(IIJ)Ljava/lang/Integer;`. Kotlin 2 compiles plain lambdas
     * via invokedynamic without that specialized method, so an explicit class is required.
     */
    class ProgressCallback(private val progress: (Float) -> Unit) : (Int, Int, Long) -> Int {
        override fun invoke(done: Int, total: Int, arg: Long): Int {
            if (total > 0) progress(done.toFloat() / total)
            return 0
        }
    }

    private class LocalSpeaker(val chunk: Int, val id: Int, val duration: Double) {
        var centroid: FloatArray? = null
    }

    companion object {
        private const val SR = Models.SAMPLE_RATE
        /** Cosine-distance threshold for automatic speaker count (tuned on Russian test dialogs). */
        const val AUTO_THRESHOLD = 0.65f
        private const val SINGLE_PASS_MAX = 12L * 60 * SR
        private const val CHUNK = 8L * 60 * SR
        private const val MIN_LAST_CHUNK = 3L * 60 * SR

        private fun dot(a: FloatArray, b: FloatArray): Double {
            var s = 0.0
            for (i in a.indices) s += a[i] * b[i]
            return s
        }

        private fun normalize(v: FloatArray): FloatArray {
            var n = 0.0
            for (x in v) n += x * x
            val inv = if (n > 0) (1.0 / sqrt(n)).toFloat() else 0f
            for (i in v.indices) v[i] *= inv
            return v
        }
    }
}
