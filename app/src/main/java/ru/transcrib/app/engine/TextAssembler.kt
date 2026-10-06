package ru.transcrib.app.engine

import ru.transcrib.app.model.Utterance

class Word(
    var text: String,
    val start: Double,
    var end: Double,
    /** Time of the last token of the word, used to estimate where it ends. */
    var lastTokenTime: Double,
) {
    var speaker = -1
}

data class SpeakerTurn(val start: Double, val end: Double, val speaker: Int)

/**
 * Turns token-level ASR output into words, sentences and speaker-attributed utterances.
 *
 * GigaAM v3 emits SentencePiece tokens; sherpa-onnx turns the word-start marker into a leading
 * space and punctuation comes as separate tokens (",", ".", "?", "»." ...).
 */
object TextAssembler {
    private const val WORD_TAIL = 0.3
    private const val SENTENCE_GAP = 2.5
    private val OPENING = setOf('«', '(', '"', '„', '“', '[')
    private val CLOSING = charArrayOf('»', '"', '\'', ')', ']', '”')
    private val TERMINAL = charArrayOf('.', '!', '?', '…')

    /**
     * @param offsetSec absolute time of the start of the decoded audio span
     * @param spanEndSec absolute time of its end
     */
    fun words(tokens: Array<String>, timestamps: FloatArray, offsetSec: Double, spanEndSec: Double): List<Word> {
        val out = ArrayList<Word>()
        if (tokens.isEmpty()) return out
        val haveTs = timestamps.size == tokens.size
        val span = spanEndSec - offsetSec
        var cur: StringBuilder? = null
        var curStart = 0.0
        var curLast = 0.0
        var pendingPrefix = ""

        fun flush() {
            val c = cur ?: return
            cur = null
            val text = c.toString()
            if (text.isBlank()) return
            val hasWordChars = text.any { it.isLetterOrDigit() }
            if (!hasWordChars) {
                if (text.all { it in OPENING }) {
                    pendingPrefix += text
                    return
                }
                val prev = out.lastOrNull()
                if (prev != null) {
                    prev.text += text
                    return
                }
            }
            out += Word(pendingPrefix + text, curStart, curLast + WORD_TAIL, curLast)
            pendingPrefix = ""
        }

        for (i in tokens.indices) {
            val tok = tokens[i]
            val t = offsetSec + if (haveTs) timestamps[i].toDouble() else span * i / tokens.size
            val startsWord = tok.startsWith(" ") || tok.startsWith("▁")
            val body = tok.trimStart(' ', '▁')
            if (startsWord || cur == null) {
                flush()
                cur = StringBuilder(body)
                curStart = t
            } else {
                cur!!.append(body)
            }
            curLast = t
        }
        flush()

        // A word ends shortly after its last token, but never after the next word starts.
        for (i in out.indices) {
            val limit = if (i + 1 < out.size) out[i + 1].start else spanEndSec
            out[i].end = minOf(out[i].lastTokenTime + WORD_TAIL, limit).coerceAtLeast(out[i].start + 0.05)
        }
        return out
    }

    fun assemble(words: List<Word>, turns: List<SpeakerTurn>?): List<Utterance> {
        if (words.isEmpty()) return emptyList()
        assignSpeakers(words, turns)

        val result = ArrayList<Utterance>()
        var sentence = ArrayList<Word>()
        for ((i, w) in words.withIndex()) {
            val prev = words.getOrNull(i - 1)
            if (prev != null && sentence.isNotEmpty() && w.start - prev.end > SENTENCE_GAP) {
                emitSentence(sentence, result)
                sentence = ArrayList()
            }
            sentence += w
            if (endsSentence(w.text)) {
                emitSentence(sentence, result)
                sentence = ArrayList()
            }
        }
        if (sentence.isNotEmpty()) emitSentence(sentence, result)
        return result
    }

    private fun endsSentence(text: String): Boolean {
        val t = text.trimEnd(*CLOSING)
        return t.isNotEmpty() && t.last() in TERMINAL
    }

    /** Splits a sentence where the speaker changes, ignoring short flickers of the diarization. */
    private fun emitSentence(words: List<Word>, out: MutableList<Utterance>) {
        val runs = ArrayList<MutableList<Word>>()
        for (w in words) {
            val last = runs.lastOrNull()
            if (last != null && last.first().speaker == w.speaker) last += w else runs += arrayListOf(w)
        }
        var changed = true
        while (changed && runs.size > 1) {
            changed = false
            for (i in runs.indices) {
                val r = runs[i]
                val dur = r.last().end - r.first().start
                if (r.size < 3 && dur < 1.2) {
                    val target = if (i == 0) runs[1] else runs[i - 1]
                    val sp = target.first().speaker
                    r.forEach { it.speaker = sp }
                    if (i == 0) target.addAll(0, r) else target.addAll(r)
                    runs.removeAt(i)
                    changed = true
                    break
                }
            }
            // Adjacent runs may now share a speaker.
            var j = 1
            while (j < runs.size) {
                if (runs[j].first().speaker == runs[j - 1].first().speaker) {
                    runs[j - 1].addAll(runs[j])
                    runs.removeAt(j)
                } else {
                    j++
                }
            }
        }
        for (r in runs) {
            val text = r.joinToString(" ") { it.text }.replaceFirstChar { it.uppercaseChar() }
            out += Utterance(r.first().start, r.last().end, r.first().speaker, text)
        }
    }

    private fun assignSpeakers(words: List<Word>, turns: List<SpeakerTurn>?) {
        if (turns.isNullOrEmpty()) {
            words.forEach { it.speaker = 0 }
            return
        }
        val sorted = turns.sortedBy { it.start }
        val starts = DoubleArray(sorted.size) { sorted[it].start }
        val maxLen = sorted.maxOf { it.end - it.start }
        for (w in words) {
            var lo = lowerBound(starts, w.start - maxLen - 2.0)
            val overlap = HashMap<Int, Double>()
            var nearest = -1
            var nearestDist = Double.MAX_VALUE
            while (lo < sorted.size && sorted[lo].start <= w.end + 2.0) {
                val t = sorted[lo]
                val ov = minOf(t.end, w.end) - maxOf(t.start, w.start)
                if (ov > 0) {
                    overlap[t.speaker] = (overlap[t.speaker] ?: 0.0) + ov
                } else {
                    val d = if (t.end <= w.start) w.start - t.end else t.start - w.end
                    if (d < nearestDist) {
                        nearestDist = d
                        nearest = t.speaker
                    }
                }
                lo++
            }
            w.speaker = overlap.maxByOrNull { it.value }?.key ?: if (nearestDist <= 1.5) nearest else -1
        }
        // Fill gaps from neighbours.
        var last = -1
        for (w in words) {
            if (w.speaker < 0) w.speaker = last else last = w.speaker
        }
        var next = words.lastOrNull { it.speaker >= 0 }?.speaker ?: 0
        for (i in words.indices.reversed()) {
            if (words[i].speaker < 0) words[i].speaker = next else next = words[i].speaker
        }
    }

    private fun lowerBound(a: DoubleArray, x: Double): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < x) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
