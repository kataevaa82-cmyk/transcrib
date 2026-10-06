package ru.transcrib.app.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import ru.transcrib.app.engine.JobRequest
import ru.transcrib.app.engine.Stage
import java.io.File
import java.util.UUID

data class ActiveJob(
    val id: String,
    val title: String,
    val stage: Stage,
    val progress: Float,
    /** Estimated seconds left, once there is enough progress to judge. */
    val etaSec: Long? = null,
    val cancelling: Boolean = false,
)

data class JobsState(
    val active: ActiveJob? = null,
    val queued: List<JobRequest> = emptyList(),
    /** Jobs that were running or queued when the system killed the app. */
    val interrupted: List<JobRequest> = emptyList(),
    /** Last finished transcript id, used to open it automatically. */
    val lastCompletedId: String? = null,
    val lastError: String? = null,
)

/**
 * Queue of transcription jobs; the work itself runs in [TranscriptionService].
 * The queue is persisted, so jobs lost to the system killing the app can be restarted.
 */
class JobManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("jobs", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(JobsState(interrupted = loadPersisted()))
    val state: StateFlow<JobsState> = _state

    private val pending = ArrayDeque<JobRequest>()
    private var active: JobRequest? = null
    private var workerActive = false
    private var activeStartedAt = 0L

    @Volatile
    var cancelRequested = false
        private set

    init {
        // What was saved belongs to a previous process; it is offered for restart, not re-run silently.
        prefs.edit().remove(KEY_QUEUE).apply()
    }

    fun enqueue(uri: Uri, ownRecording: File? = null, title: String? = null) {
        add(
            JobRequest(
                id = UUID.randomUUID().toString(),
                uri = uri,
                title = title ?: displayName(uri),
                ownRecording = ownRecording,
            ),
        )
    }

    /** Recognizes an existing transcript again from its saved audio. */
    fun rerun(id: String, title: String, createdAt: Long, audio: File) {
        add(
            JobRequest(
                id = id,
                uri = Uri.fromFile(audio),
                title = title,
                rerun = true,
                createdAt = createdAt,
            ),
        )
    }

    /** Puts an interrupted job back into the queue. */
    fun retry(job: JobRequest) {
        dismissInterrupted(job.id)
        add(job)
    }

    /** Forgets an interrupted job; an unfinished in-app recording is deleted with it. */
    fun dismissInterrupted(id: String, deleteRecording: Boolean = false) {
        val job = _state.value.interrupted.firstOrNull { it.id == id }
        if (deleteRecording) job?.ownRecording?.delete()
        _state.update { s -> s.copy(interrupted = s.interrupted.filter { it.id != id }) }
    }

    private fun add(job: JobRequest) {
        synchronized(pending) {
            pending.addLast(job)
            _state.update { it.copy(queued = pending.toList(), lastError = null) }
            persist()
        }
        startService()
    }

    /**
     * A plain start is used when allowed (app visible or already running a foreground service):
     * unlike startForegroundService() it has no hard deadline for reaching startForeground(),
     * which slow devices can miss while the UI is still being built.
     */
    private fun startService() {
        val intent = Intent(context, TranscriptionService::class.java)
        try {
            context.startService(intent)
        } catch (e: IllegalStateException) {
            ContextCompat.startForegroundService(context, intent)
        }
    }

    /** @return true if the caller should start a worker thread. */
    internal fun claimWorker(): Boolean = synchronized(pending) {
        if (workerActive) false else {
            workerActive = true
            true
        }
    }

    internal fun isWorkerActive(): Boolean = synchronized(pending) { workerActive }

    /** Next job, or null — in which case the worker is marked as finished atomically. */
    internal fun next(): JobRequest? = synchronized(pending) {
        val job = pending.removeFirstOrNull()
        if (job == null) workerActive = false
        active = job
        cancelRequested = false
        activeStartedAt = SystemClock.elapsedRealtime()
        _state.update {
            it.copy(
                queued = pending.toList(),
                active = job?.let { j -> ActiveJob(j.id, j.title, Stage.DECODING, 0f) },
            )
        }
        persist()
        job
    }

    internal fun progress(stage: Stage, fraction: Float) {
        val elapsed = (SystemClock.elapsedRealtime() - activeStartedAt) / 1000.0
        // Stage weights roughly match their share of time, so a linear estimate works after a while.
        val eta = if (fraction > 0.08f && elapsed > 15) ((elapsed / fraction) * (1 - fraction)).toLong() else null
        _state.update { s -> s.copy(active = s.active?.copy(stage = stage, progress = fraction, etaSec = eta)) }
    }

    internal fun finished(id: String?, error: String?) {
        synchronized(pending) {
            active = null
            persist()
        }
        _state.update { it.copy(active = null, lastCompletedId = id ?: it.lastCompletedId, lastError = error) }
    }

    fun cancelActive() {
        cancelRequested = true
        _state.update { s -> s.copy(active = s.active?.copy(cancelling = true)) }
    }

    fun removeQueued(id: String) = synchronized(pending) {
        pending.removeAll { it.id == id }
        _state.update { it.copy(queued = pending.toList()) }
        persist()
    }

    fun consumeCompleted() = _state.update { it.copy(lastCompletedId = null) }

    fun consumeError() = _state.update { it.copy(lastError = null) }

    /** Must be called under the `pending` lock. */
    private fun persist() {
        val all = listOfNotNull(active) + pending
        val arr = JSONArray()
        all.forEach { j ->
            arr.put(JSONObject().apply {
                put("id", j.id)
                put("uri", j.uri.toString())
                put("title", j.title)
                j.ownRecording?.let { put("recording", it.absolutePath) }
                put("rerun", j.rerun)
                j.createdAt?.let { put("createdAt", it) }
            })
        }
        prefs.edit().putString(KEY_QUEUE, arr.toString()).apply()
    }

    private fun loadPersisted(): List<JobRequest> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_QUEUE, null) ?: return emptyList())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            JobRequest(
                id = o.getString("id"),
                uri = Uri.parse(o.getString("uri")),
                title = o.getString("title"),
                ownRecording = o.optString("recording").takeIf { it.isNotEmpty() }?.let(::File),
                rerun = o.optBoolean("rerun"),
                createdAt = if (o.has("createdAt")) o.getLong("createdAt") else null,
            )
        }.filter { j -> j.ownRecording?.exists() ?: true }
    }.getOrDefault(emptyList())

    private fun displayName(uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "Запись"
        return name.substringBeforeLast('.').ifBlank { name }
    }

    private companion object {
        const val KEY_QUEUE = "queue"
    }
}
