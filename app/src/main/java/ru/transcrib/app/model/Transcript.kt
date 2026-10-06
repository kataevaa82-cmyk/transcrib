package ru.transcrib.app.model

import org.json.JSONArray
import org.json.JSONObject

/** One sentence (or a part of a sentence, if the speaker changed inside it). */
data class Utterance(
    val start: Double,
    val end: Double,
    val speaker: Int,
    val text: String,
)

/** Consecutive utterances of one speaker, as shown on screen. */
data class Paragraph(
    val speaker: Int,
    val start: Double,
    val end: Double,
    val utterances: List<Utterance>,
) {
    val text: String get() = utterances.joinToString(" ") { it.text }
}

data class Transcript(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationSec: Double,
    val sourceName: String,
    /** -1 = diarization disabled, otherwise the number of speakers found. */
    val numSpeakers: Int,
    val speakerNames: Map<Int, String>,
    val utterances: List<Utterance>,
    val processingSec: Double,
    /** "mic" for in-app recordings, "file" otherwise. */
    val source: String = SOURCE_FILE,
) {
    fun speakerName(speaker: Int): String =
        speakerNames[speaker] ?: if (speaker >= 0) "Спикер ${speaker + 1}" else "Спикер"

    val hasSpeakers: Boolean get() = numSpeakers > 1

    fun paragraphs(): List<Paragraph> {
        val out = ArrayList<Paragraph>()
        var cur = ArrayList<Utterance>()
        for (u in utterances) {
            val last = cur.lastOrNull()
            val sameSpeaker = last != null && last.speaker == u.speaker
            // Without speakers, start a new paragraph after long pauses to keep text readable.
            val longPause = last != null && (u.start - last.end) > 4.0
            val tooLong = cur.size >= 12 && !hasSpeakers
            if (last != null && (!sameSpeaker || (!hasSpeakers && (longPause || tooLong)))) {
                out += Paragraph(cur.first().speaker, cur.first().start, cur.last().end, cur)
                cur = ArrayList()
            }
            cur += u
        }
        if (cur.isNotEmpty()) out += Paragraph(cur.first().speaker, cur.first().start, cur.last().end, cur)
        return out
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("id", id)
        put("title", title)
        put("createdAt", createdAt)
        put("durationSec", durationSec)
        put("sourceName", sourceName)
        put("numSpeakers", numSpeakers)
        put("processingSec", processingSec)
        put("source", source)
        put("speakerNames", JSONObject().apply {
            speakerNames.forEach { (k, v) -> put(k.toString(), v) }
        })
        put("utterances", JSONArray().apply {
            utterances.forEach { u ->
                put(JSONObject().apply {
                    put("s", u.start)
                    put("e", u.end)
                    put("p", u.speaker)
                    put("t", u.text)
                })
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): Transcript {
            val names = HashMap<Int, String>()
            o.optJSONObject("speakerNames")?.let { n ->
                n.keys().forEach { k -> names[k.toInt()] = n.getString(k) }
            }
            val arr = o.getJSONArray("utterances")
            val utt = ArrayList<Utterance>(arr.length())
            for (i in 0 until arr.length()) {
                val u = arr.getJSONObject(i)
                utt += Utterance(u.getDouble("s"), u.getDouble("e"), u.getInt("p"), u.getString("t"))
            }
            return Transcript(
                id = o.getString("id"),
                title = o.getString("title"),
                createdAt = o.getLong("createdAt"),
                durationSec = o.getDouble("durationSec"),
                sourceName = o.optString("sourceName"),
                numSpeakers = o.optInt("numSpeakers", -1),
                speakerNames = names,
                utterances = utt,
                processingSec = o.optDouble("processingSec", 0.0),
                source = o.optString("source", SOURCE_FILE),
            )
        }

        const val SOURCE_FILE = "file"
        const val SOURCE_MIC = "mic"
    }
}

/** Lightweight entry for the history list (stored as meta.json next to the transcript). */
data class TranscriptMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationSec: Double,
    val numSpeakers: Int,
    val preview: String,
    val source: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("createdAt", createdAt)
        put("durationSec", durationSec)
        put("numSpeakers", numSpeakers)
        put("preview", preview)
        put("source", source)
    }

    companion object {
        fun fromJson(o: JSONObject) = TranscriptMeta(
            id = o.getString("id"),
            title = o.getString("title"),
            createdAt = o.getLong("createdAt"),
            durationSec = o.getDouble("durationSec"),
            numSpeakers = o.optInt("numSpeakers", -1),
            preview = o.optString("preview"),
            source = o.optString("source", Transcript.SOURCE_FILE),
        )
    }
}
