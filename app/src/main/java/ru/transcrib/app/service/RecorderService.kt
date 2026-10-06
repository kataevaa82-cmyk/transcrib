package ru.transcrib.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.transcrib.app.R
import ru.transcrib.app.TranscribApp
import ru.transcrib.app.ui.MainActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecorderState(
    val recording: Boolean = false,
    val paused: Boolean = false,
    val elapsedMs: Long = 0,
    /** 0..1, for the level meter. */
    val level: Float = 0f,
    val error: String? = null,
)

/**
 * Voice recorder running as a microphone foreground service, so recording continues with the
 * screen off. When stopped, the file is queued for transcription automatically.
 */
class RecorderService : Service() {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var accumulated = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            val s = _state.value
            if (s.recording && !s.paused) {
                val amp = runCatching { r.maxAmplitude }.getOrDefault(0)
                val level = (amp / 32767f).coerceIn(0f, 1f)
                _state.value = s.copy(elapsedMs = elapsed(), level = level)
            }
            handler.postDelayed(this, 100)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> stop(transcribe = true)
            ACTION_DISCARD -> stop(transcribe = false)
        }
        return START_NOT_STICKY
    }

    private fun start() {
        if (recorder != null) return
        // The notification chronometer is based on these, so reset them first.
        accumulated = 0
        startedAt = SystemClock.elapsedRealtime()
        _state.value = RecorderState()
        try {
            ServiceCompat.startForeground(
                this, NOTIFY_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Cannot enter foreground", e)
            _state.value = RecorderState(error = "Не удалось начать запись. Откройте приложение и попробуйте снова.")
            stopSelf()
            return
        }
        val dir = File(filesDir, "recordings").apply { mkdirs() }
        val f = File(dir, "rec_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(32000)
            r.setAudioEncodingBitRate(64000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start recording", e)
            r.release()
            f.delete()
            _state.value = RecorderState(error = "Не удалось начать запись: микрофон занят или недоступен")
            finish()
            return
        }
        recorder = r
        file = f
        startedAt = SystemClock.elapsedRealtime()
        _state.value = RecorderState(recording = true)
        updateNotification()
        handler.post(ticker)
    }

    private fun pause() {
        val r = recorder ?: return
        if (_state.value.paused) return
        runCatching { r.pause() }.onSuccess {
            accumulated = elapsed()
            _state.value = _state.value.copy(paused = true, elapsedMs = accumulated, level = 0f)
            updateNotification()
        }
    }

    private fun resume() {
        val r = recorder ?: return
        if (!_state.value.paused) return
        runCatching { r.resume() }.onSuccess {
            startedAt = SystemClock.elapsedRealtime()
            _state.value = _state.value.copy(paused = false)
            updateNotification()
        }
    }

    private fun stop(transcribe: Boolean) {
        val r = recorder
        val f = file
        recorder = null
        file = null
        handler.removeCallbacks(ticker)
        val duration = elapsed()
        var ok = false
        if (r != null) {
            ok = runCatching { r.stop() }.isSuccess
            r.release()
        }
        _state.value = RecorderState()
        if (f != null) {
            if (transcribe && ok && duration >= 1000) {
                val app = application as TranscribApp
                val title = "Запись " + SimpleDateFormat("d MMM yyyy, HH:mm", Locale("ru")).format(Date())
                app.jobs.enqueue(Uri.fromFile(f), app.settings.speakers, ownRecording = f, title = title)
            } else {
                f.delete()
                if (transcribe) _state.value = RecorderState(error = "Запись слишком короткая")
            }
        }
        finish()
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun elapsed(): Long =
        if (_state.value.paused) accumulated else accumulated + (SystemClock.elapsedRealtime() - startedAt)

    private fun updateNotification() {
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIFY_ID, notification())
        }
    }

    private fun notification(): Notification {
        val paused = _state.value.paused
        fun action(a: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, RecorderService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 10, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_RECORDER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, TranscribApp.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFFF6A45.toInt())
            .setContentTitle(if (paused) "Запись на паузе" else "Идёт запись")
            .setContentText("Нажмите «Стоп», чтобы расшифровать")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .apply {
                if (!paused) {
                    setUsesChronometer(true)
                    setWhen(System.currentTimeMillis() - elapsed())
                }
            }
            .addAction(0, if (paused) "Продолжить" else "Пауза", action(if (paused) ACTION_RESUME else ACTION_PAUSE, 11))
            .addAction(0, "Стоп", action(ACTION_STOP, 12))
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        recorder?.let {
            runCatching { it.stop() }
            it.release()
        }
        recorder = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecorderService"
        private const val NOTIFY_ID = 2001
        const val ACTION_START = "ru.transcrib.app.rec.START"
        const val ACTION_PAUSE = "ru.transcrib.app.rec.PAUSE"
        const val ACTION_RESUME = "ru.transcrib.app.rec.RESUME"
        const val ACTION_STOP = "ru.transcrib.app.rec.STOP"
        const val ACTION_DISCARD = "ru.transcrib.app.rec.DISCARD"

        private val _state = MutableStateFlow(RecorderState())
        val state: StateFlow<RecorderState> = _state

        fun send(context: Context, action: String) {
            val i = Intent(context, RecorderService::class.java).setAction(action)
            try {
                context.startService(i)
            } catch (e: IllegalStateException) {
                ContextCompat.startForegroundService(context, i)
            }
        }

        fun clearError() {
            if (_state.value.error != null) _state.value = _state.value.copy(error = null)
        }
    }
}
