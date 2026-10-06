package ru.transcrib.app.export

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import android.text.format.DateUtils
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import ru.transcrib.app.model.Transcript
import java.io.File
import java.io.OutputStream
import java.util.Locale

enum class ExportFormat(val ext: String, val mime: String, val label: String, val hint: String) {
    DOCX(
        "docx",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "Word",
        "Документ .docx со спикерами",
    ),
    TXT("txt", "text/plain", "Текст", "Простой файл .txt"),
    SRT("srt", "application/x-subrip", "Субтитры", "Файл .srt для видео"),
}

object Exporter {

    fun plainText(t: Transcript, timestamps: Boolean): String = buildString {
        for (p in t.paragraphs()) {
            val head = buildList {
                if (timestamps) add(formatTime(p.start))
                if (t.hasSpeakers) add(t.speakerName(p.speaker))
            }
            if (head.isNotEmpty()) append(head.joinToString(" · ")).append('\n')
            append(p.text).append("\n\n")
        }
    }.trimEnd() + "\n"

    fun srt(t: Transcript): String = buildString {
        var n = 1
        for (u in t.utterances) {
            append(n++).append('\n')
            append(srtTime(u.start)).append(" --> ").append(srtTime(maxOf(u.end, u.start + 0.5))).append('\n')
            if (t.hasSpeakers) append(t.speakerName(u.speaker)).append(": ")
            append(u.text).append("\n\n")
        }
    }

    fun copyToClipboard(context: Context, t: Transcript, timestamps: Boolean) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(t.title, plainText(t, timestamps)))
    }

    fun fileName(t: Transcript, format: ExportFormat): String {
        val safe = t.title.replace(Regex("[\\/:*?\"<>|]"), "_").take(80).ifBlank { "transcript" }
        return "$safe.${format.ext}"
    }

    fun write(context: Context, t: Transcript, format: ExportFormat, timestamps: Boolean, out: OutputStream) {
        when (format) {
            ExportFormat.DOCX -> DocxWriter.write(t, meta(context, t), timestamps, out)
            ExportFormat.TXT -> out.use { it.write(plainText(t, timestamps).toByteArray(Charsets.UTF_8)) }
            ExportFormat.SRT -> out.use { it.write(srt(t).toByteArray(Charsets.UTF_8)) }
        }
    }

    /**
     * Saves straight to Downloads/Транскрибатор (Android 10+, no permission needed).
     * @return the saved file's uri and display name
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveToDownloads(context: Context, t: Transcript, format: ExportFormat, timestamps: Boolean): Pair<Uri, String> {
        val name = fileName(t, format)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, format.mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_FOLDER)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Не удалось создать файл в «Загрузках»")
        try {
            val out = resolver.openOutputStream(uri) ?: error("Не удалось открыть файл")
            write(context, t, format, timestamps, out)
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        // The system may have renamed it ("name (1).docx") if the file already existed.
        val finalName = runCatching {
            resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: name
        return uri to finalName
    }

    /** Opens a saved file in a suitable app (Word, МойОфис, WPS, a text viewer...). */
    fun open(context: Context, uri: Uri, format: ExportFormat) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, format.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(view)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                context,
                if (format == ExportFormat.DOCX) "Нет приложения для .docx — подойдут МойОфис, WPS Office или Word"
                else "Нет приложения, чтобы открыть этот файл",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    const val DOWNLOAD_FOLDER = "Транскрибатор"

    /** Saves to a location picked by the user (ACTION_CREATE_DOCUMENT). */
    fun saveTo(context: Context, t: Transcript, format: ExportFormat, timestamps: Boolean, uri: Uri) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Не удалось открыть файл")
        write(context, t, format, timestamps, out)
    }

    fun share(context: Context, t: Transcript, format: ExportFormat, timestamps: Boolean) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, fileName(t, format))
        write(context, t, format, timestamps, file.outputStream())
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(format.mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, t.title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Отправить расшифровку"))
    }

    fun meta(context: Context, t: Transcript): String {
        val date = DateUtils.formatDateTime(
            context, t.createdAt,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME,
        )
        val parts = mutableListOf(date, "длительность ${formatTime(t.durationSec)}")
        val speakers = t.utterances.map { it.speaker }.toSet().size
        if (t.hasSpeakers && speakers > 1) {
            parts += "участники: " + t.utterances.map { it.speaker }.distinct().sorted()
                .joinToString(", ") { t.speakerName(it) }
        }
        return parts.joinToString(" · ")
    }

    fun formatTime(sec: Double): String {
        val s = sec.toLong()
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, ss)
        else String.format(Locale.US, "%d:%02d", m, ss)
    }

    private fun srtTime(sec: Double): String {
        val ms = (sec * 1000).toLong()
        return String.format(
            Locale.US, "%02d:%02d:%02d,%03d",
            ms / 3_600_000, (ms % 3_600_000) / 60_000, (ms % 60_000) / 1000, ms % 1000,
        )
    }
}
