package ru.transcrib.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import ru.transcrib.app.model.Transcript
import ru.transcrib.app.model.TranscriptMeta
import java.io.File

/** A transcript that matched a search, with the piece of text around the match. */
data class SearchHit(val meta: TranscriptMeta, val snippet: String?)

/**
 * Transcripts live in filesDir/transcripts/<id>/ as transcript.json, a small meta.json for the
 * list, and audio.m4a used for playback.
 */
class TranscriptRepository(context: Context) {
    private val root = File(context.filesDir, "transcripts").apply { mkdirs() }

    private val _items = MutableStateFlow<List<TranscriptMeta>>(emptyList())
    val items: StateFlow<List<TranscriptMeta>> = _items

    /** Ids deleted in this session: a late save must not bring them back. */
    private val deleted = HashSet<String>()

    init {
        Thread({ refresh() }, "history-load").start()
    }

    fun dirFor(id: String) = File(root, id)

    fun audioFile(id: String) = File(dirFor(id), AUDIO_NAME)

    /** Removes folders of jobs killed before finishing (in an earlier process, i.e. before [before]). */
    fun cleanupUnfinished(before: Long) {
        root.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.lastModified() < before && !File(dir, JSON_NAME).exists()) {
                dir.deleteRecursively()
            }
        }
    }

    fun refresh() {
        val list = root.listFiles()?.mapNotNull { dir -> runCatching { readMeta(dir) }.getOrNull() } ?: emptyList()
        synchronized(this) {
            // Keep entries saved while the list was loading.
            val known = list.map { it.id }.toSet()
            _items.value = (list + _items.value.filter { it.id !in known && dirFor(it.id).exists() })
                .filter { it.id !in deleted }
                .sortedByDescending { it.createdAt }
        }
    }

    /** meta.json is tiny; older transcripts without it get one on first read. */
    private fun readMeta(dir: File): TranscriptMeta? {
        val metaFile = File(dir, META_NAME)
        if (metaFile.exists()) {
            runCatching { return TranscriptMeta.fromJson(JSONObject(metaFile.readText())) }
        }
        val t = load(dir.name) ?: return null
        val meta = t.toMeta()
        runCatching { metaFile.writeText(meta.toJson().toString()) }
        return meta
    }

    fun load(id: String): Transcript? {
        val f = File(dirFor(id), JSON_NAME)
        if (!f.exists()) return null
        return Transcript.fromJson(JSONObject(f.readText()))
    }

    @Synchronized
    fun save(t: Transcript) {
        if (t.id in deleted) return
        writeFile(t)
        val meta = t.toMeta()
        runCatching { File(dirFor(t.id), META_NAME).writeText(meta.toJson().toString()) }
        _items.value = (listOf(meta) + _items.value.filter { it.id != t.id })
            .sortedByDescending { it.createdAt }
    }

    /** Writes the transcript to disk without showing it in the list (intermediate result). */
    @Synchronized
    fun saveSilently(t: Transcript) {
        if (t.id in deleted) return
        writeFile(t)
    }

    private fun writeFile(t: Transcript) {
        val dir = dirFor(t.id).apply { mkdirs() }
        val tmp = File(dir, "$JSON_NAME.tmp")
        tmp.writeText(t.toJson().toString())
        val dst = File(dir, JSON_NAME)
        if (!tmp.renameTo(dst)) {
            dst.delete()
            tmp.renameTo(dst)
        }
    }

    @Synchronized
    fun delete(id: String) {
        deleted += id
        dirFor(id).deleteRecursively()
        _items.value = _items.value.filter { it.id != id }
    }

    /**
     * Full-text search over titles, speaker names and the transcripts themselves.
     * Reads every transcript, so call it off the main thread.
     */
    fun search(query: String): List<SearchHit> {
        val q = normalize(query.trim())
        if (q.isEmpty()) return emptyList()
        return _items.value.mapNotNull { meta ->
            if (normalize(meta.title).contains(q)) return@mapNotNull SearchHit(meta, null)
            val t = runCatching { load(meta.id) }.getOrNull() ?: return@mapNotNull null
            if (t.speakerNames.values.any { normalize(it).contains(q) }) return@mapNotNull SearchHit(meta, null)
            val u = t.utterances.firstOrNull { normalize(it.text).contains(q) } ?: return@mapNotNull null
            SearchHit(meta, snippet(u.text, q))
        }
    }

    private fun snippet(text: String, q: String): String {
        val i = normalize(text).indexOf(q).coerceAtLeast(0)
        val from = (i - 40).coerceAtLeast(0)
        val to = (i + q.length + 80).coerceAtMost(text.length)
        return (if (from > 0) "…" else "") + text.substring(from, to) + (if (to < text.length) "…" else "")
    }

    private fun Transcript.toMeta() = TranscriptMeta(
        id = id,
        title = title,
        createdAt = createdAt,
        durationSec = durationSec,
        numSpeakers = numSpeakers,
        preview = utterances.asSequence().take(4).joinToString(" ") { it.text }.take(160),
        source = source,
    )

    companion object {
        const val JSON_NAME = "transcript.json"
        const val META_NAME = "meta.json"
        const val AUDIO_NAME = "audio.m4a"

        /** Case- and ё-insensitive form used for search (same length as the input). */
        fun normalize(s: String) = s.lowercase().replace('ё', 'е')
    }
}
