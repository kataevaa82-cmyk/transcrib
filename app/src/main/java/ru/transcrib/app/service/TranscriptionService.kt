package ru.transcrib.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import ru.transcrib.app.R
import ru.transcrib.app.TranscribApp
import ru.transcrib.app.model.Transcript
import ru.transcrib.app.engine.Pipeline
import ru.transcrib.app.engine.Stage
import ru.transcrib.app.ui.MainActivity
import kotlin.coroutines.cancellation.CancellationException

/** Runs queued transcription jobs one by one while showing a progress notification. */
class TranscriptionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as TranscribApp
        if (intent?.action == ACTION_CANCEL) {
            app.jobs.cancelActive()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFY_PROGRESS,
                progressNotification("Подготовка…", 0f, indeterminate = true),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: Exception) {
            // The app went to background before the service started: work continues without
            // the foreground status (the system may pause it, the job can be restarted later).
            Log.w(TAG, "startForeground not allowed", e)
        }
        if (app.jobs.claimWorker()) {
            Thread({ workLoop(app) }, "transcriber").start()
        }
        return START_NOT_STICKY
    }

    private fun workLoop(app: TranscribApp) {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "transcrib:job").apply {
            setReferenceCounted(false)
        }
        try {
            while (true) {
                val job = app.jobs.next() ?: break
                wakeLock.acquire(6 * 60 * 60 * 1000L)
                var error: String? = null
                var doneId: String? = null
                try {
                    val pipeline = Pipeline(this, app.models)
                    val transcript = pipeline.run(
                        job = job,
                        outDir = app.repository.dirFor(job.id),
                        progress = { stage, f -> onProgress(app, job.title, stage, f) },
                        isCancelled = { app.jobs.cancelRequested },
                        checkpoint = { app.repository.saveSilently(it) },
                    )
                    app.repository.save(transcript)
                    job.ownRecording?.delete()
                    // Persisted read grants are capped (128–512 per app); this one is no longer needed.
                    if (!job.rerun && job.uri.scheme == "content") {
                        runCatching {
                            contentResolver.releasePersistableUriPermission(job.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }
                    doneId = transcript.id
                    notifyDone(app, job.title, transcript, null)
                } catch (e: CancellationException) {
                    // A re-run keeps the transcript it was started from.
                    if (!job.rerun) app.repository.delete(job.id)
                } catch (e: Throwable) {
                    Log.e(TAG, "Job failed", e)
                    if (!job.rerun) app.repository.delete(job.id)
                    error = friendlyError(e)
                    notifyDone(app, job.title, null, error)
                } finally {
                    app.jobs.finished(doneId, error)
                }
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            // onStartCommand also runs on the main thread, so a job enqueued right now
            // either restarts the worker before this check or sees the service stopping.
            main.post {
                if (!app.jobs.isWorkerActive()) {
                    app.models.release()
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun onProgress(app: TranscribApp, title: String, stage: Stage, f: Float) {
        app.jobs.progress(stage, f)
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotify < 1000) return
        lastNotify = now
        val eta = app.jobs.state.value.active?.etaSec
        val text = buildString {
            append(stage.title).append(" · ").append((f * 100).toInt()).append('%')
            if (eta != null) append(" · осталось ").append(if (eta < 60) "меньше минуты" else "~${(eta + 30) / 60} мин")
        }
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(NOTIFY_PROGRESS, progressNotification(title, f, false, text))
        }
    }

    private fun progressNotification(
        title: String,
        f: Float,
        indeterminate: Boolean,
        text: String? = null,
    ): Notification {
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, TranscriptionService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, TranscribApp.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFFF6A45.toInt())
            .setContentTitle(title)
            .setContentText(text ?: "Расшифровка")
            .setProgress(1000, (f * 1000).toInt(), indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent(null))
            .addAction(0, "Отменить", cancel)
            .build()
    }

    private fun notifyDone(app: TranscribApp, title: String, t: Transcript?, error: String?) {
        // Heads-up when the app is in the background; a quiet entry when the user is looking at it.
        val channel = if (app.uiVisible) TranscribApp.CHANNEL_PROGRESS else TranscribApp.CHANNEL_DONE
        val preview = t?.utterances?.asSequence()?.take(6)?.joinToString(" ") { it.text }?.take(400)
        val text = when {
            error != null -> "$title: $error"
            preview.isNullOrBlank() -> "$title — речь не обнаружена"
            else -> preview
        }
        val n = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFFF6A45.toInt())
            .setContentTitle(if (error == null) "Готово: $title" else "Не удалось расшифровать")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(t?.id))
            .apply {
                if (t != null) {
                    addAction(0, "Открыть", openAppIntent(t.id))
                    addAction(0, "Поделиться", openAppIntent(t.id, share = true))
                }
            }
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(t?.id?.hashCode() ?: NOTIFY_ERROR, n) }
    }

    /** Turns exceptions into something a person can act on. */
    private fun friendlyError(e: Throwable): String {
        val msg = e.message.orEmpty()
        return when {
            e is OutOfMemoryError -> "Недостаточно оперативной памяти. Закройте другие приложения и попробуйте снова."
            e is SecurityException -> "Нет доступа к файлу. Откройте его заново через «Выбрать файл» или «Поделиться»."
            e is java.io.FileNotFoundException -> "Файл не найден — возможно, он удалён или перемещён."
            "ENOSPC" in msg || "No space left" in msg -> "Недостаточно свободного места в памяти телефона."
            // Messages written for people (all ours are in Russian).
            msg.any { it in 'а'..'я' || it in 'А'..'Я' } -> msg
            e is IllegalStateException || e is IllegalArgumentException ->
                "Не удалось прочитать звук: формат файла не поддерживается этим телефоном."
            else -> "Непредвиденная ошибка (${e.javaClass.simpleName}). Попробуйте ещё раз."
        }
    }

    private fun openAppIntent(id: String?, share: Boolean = false): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (id != null) intent.putExtra(MainActivity.EXTRA_OPEN_ID, id)
        if (share) intent.putExtra(MainActivity.EXTRA_SHARE, true)
        return PendingIntent.getActivity(
            this, (id?.hashCode() ?: 0) + if (share) 1 else 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val TAG = "TranscriptionService"
        const val ACTION_CANCEL = "ru.transcrib.app.CANCEL"
        private const val NOTIFY_PROGRESS = 1001
        private const val NOTIFY_ERROR = 1002
    }
}
