package ru.transcrib.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.transcrib.app.data.TranscriptRepository
import ru.transcrib.app.engine.Models
import ru.transcrib.app.service.JobManager

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _speakers = MutableStateFlow(prefs.getInt(KEY_SPEAKERS, 0))

    /** 0 = auto, 1 = one speaker (no diarization), N = exactly N speakers. */
    val speakersFlow: StateFlow<Int> = _speakers
    var speakers: Int
        get() = _speakers.value
        set(v) {
            _speakers.value = v
            prefs.edit().putInt(KEY_SPEAKERS, v).apply()
        }

    var showTimestamps: Boolean
        get() = prefs.getBoolean(KEY_TIMESTAMPS, true)
        set(v) = prefs.edit().putBoolean(KEY_TIMESTAMPS, v).apply()

    /** Transcript layout: chat bubbles (true) or plain document (false). */
    var chatView: Boolean
        get() = prefs.getBoolean(KEY_CHAT, true)
        set(v) = prefs.edit().putBoolean(KEY_CHAT, v).apply()

    /** Last format used in "Поделиться": docx / text / txt / srt. */
    var shareFormat: String
        get() = prefs.getString(KEY_SHARE, "docx") ?: "docx"
        set(v) = prefs.edit().putString(KEY_SHARE, v).apply()

    /** Reading text size multiplier in transcripts. */
    var textScale: Float
        get() = prefs.getFloat(KEY_TEXT_SCALE, 1f)
        set(v) = prefs.edit().putFloat(KEY_TEXT_SCALE, v).apply()

    var batteryHintDismissed: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_HINT, false)
        set(v) = prefs.edit().putBoolean(KEY_BATTERY_HINT, v).apply()

    var playbackSpeed: Float
        get() = prefs.getFloat(KEY_SPEED, 1f)
        set(v) = prefs.edit().putFloat(KEY_SPEED, v).apply()

    private companion object {
        const val KEY_SPEAKERS = "speakers"
        const val KEY_TIMESTAMPS = "timestamps"
        const val KEY_CHAT = "chat_view"
        const val KEY_SPEED = "speed"
        const val KEY_SHARE = "share_format"
        const val KEY_TEXT_SCALE = "text_scale"
        const val KEY_BATTERY_HINT = "battery_hint_dismissed"
    }
}

class TranscribApp : Application() {
    lateinit var repository: TranscriptRepository
        private set
    lateinit var models: Models
        private set
    lateinit var jobs: JobManager
        private set
    lateinit var settings: Settings
        private set

    /** True while an activity of the app is on screen (set by MainActivity). */
    @Volatile
    var uiVisible = false

    override fun onCreate() {
        super.onCreate()
        repository = TranscriptRepository(this)
        models = Models(assets)
        jobs = JobManager(this)
        settings = Settings(this)
        createChannels()
        // Leftovers of a job killed by the system in a previous run.
        val started = System.currentTimeMillis() - 2000
        Thread({
            cacheDir.listFiles { f -> f.name.startsWith("job_") && f.lastModified() < started }
                ?.forEach { it.delete() }
            repository.cleanupUnfinished(started)
        }, "cleanup").start()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Ход расшифровки", NotificationManager.IMPORTANCE_LOW)
        )
        nm.deleteNotificationChannel("done")
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Готовые расшифровки", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Сообщает, когда расшифровка готова, даже если приложение свёрнуто"
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, "Запись с микрофона", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL_PROGRESS = "progress"
        const val CHANNEL_DONE = "done_alerts"
        const val CHANNEL_RECORDING = "recording"
    }
}
