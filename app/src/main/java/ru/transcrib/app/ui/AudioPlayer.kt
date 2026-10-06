package ru.transcrib.app.ui

import android.media.MediaPlayer
import java.io.File

/**
 * MediaPlayer wrapper that is safe to touch after release: a screen keeps recomposing during its
 * exit animation, and a released MediaPlayer throws IllegalStateException on any call.
 */
class AudioPlayer private constructor(private var mp: MediaPlayer?) {
    val durationMs: Int = runCatching { mp?.duration ?: 0 }.getOrDefault(0)

    var onComplete: (() -> Unit)? = null

    init {
        mp?.setOnCompletionListener { onComplete?.invoke() }
    }

    val isPlaying: Boolean get() = runCatching { mp?.isPlaying == true }.getOrDefault(false)

    val positionMs: Int get() = runCatching { mp?.currentPosition ?: 0 }.getOrDefault(0)

    fun play(speed: Float) {
        val p = mp ?: return
        runCatching {
            p.start()
            // Changing params on a paused player would start it, so speed is applied only here.
            p.playbackParams = p.playbackParams.setSpeed(speed)
        }
    }

    fun pause() {
        runCatching { mp?.pause() }
    }

    fun seekTo(ms: Long) {
        runCatching { mp?.seekTo(ms.coerceIn(0, durationMs.toLong()), MediaPlayer.SEEK_CLOSEST) }
    }

    fun setSpeed(speed: Float) {
        val p = mp ?: return
        if (isPlaying) runCatching { p.playbackParams = p.playbackParams.setSpeed(speed) }
    }

    fun release() {
        val p = mp ?: return
        mp = null
        onComplete = null
        runCatching { p.release() }
    }

    companion object {
        fun open(file: File): AudioPlayer? {
            if (!file.exists()) return null
            val mp = MediaPlayer()
            return try {
                mp.setDataSource(file.absolutePath)
                mp.prepare()
                AudioPlayer(mp)
            } catch (e: Exception) {
                mp.release()
                null
            }
        }
    }
}
