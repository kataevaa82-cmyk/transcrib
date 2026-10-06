package ru.transcrib.app.export

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.core.content.FileProvider
import ru.transcrib.app.model.Transcript
import java.io.File

/** A messenger the transcript can be sent to directly, without the system chooser. */
data class ShareTarget(val label: String, val packageName: String, val icon: Drawable)

/** What is sent: a file in one of the export formats, or the text itself as a message. */
sealed interface ShareContent {
    data class FileOf(val format: ExportFormat) : ShareContent
    data object Message : ShareContent
}

object ShareTargets {
    /** In order of priority; the first installed package of each group is used. */
    private val known = listOf(
        "MAX" to listOf("ru.oneme.app"),
        "VK" to listOf("com.vkontakte.android", "com.vk.im"),
        "Telegram" to listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram"),
    )

    /** Messengers installed on the phone that accept this kind of content. */
    fun available(context: Context, content: ShareContent): List<ShareTarget> {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_SEND).setType(mimeOf(content))
        return known.mapNotNull { (label, packages) ->
            packages.firstNotNullOfOrNull { pkg ->
                val handles = runCatching {
                    pm.queryIntentActivities(Intent(probe).setPackage(pkg), PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
                }.getOrDefault(false)
                if (!handles) null
                else runCatching { ShareTarget(label, pkg, pm.getApplicationIcon(pkg)) }.getOrNull()
            }
        }
    }

    /**
     * Sends to [target] (a package) or, when null, through the system chooser.
     * Falls back to the chooser if the messenger refuses the content.
     */
    fun send(context: Context, t: Transcript, content: ShareContent, timestamps: Boolean, target: String?) {
        val intent = buildIntent(context, t, content, timestamps)
        if (target != null) {
            try {
                context.startActivity(Intent(intent).setPackage(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // fall through to the chooser
            } catch (_: SecurityException) {
            }
        }
        context.startActivity(
            Intent.createChooser(intent, "Отправить расшифровку").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun mimeOf(content: ShareContent) = when (content) {
        is ShareContent.FileOf -> content.format.mime
        ShareContent.Message -> "text/plain"
    }

    private fun buildIntent(context: Context, t: Transcript, content: ShareContent, timestamps: Boolean): Intent =
        when (content) {
            ShareContent.Message -> Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, t.title)
                .putExtra(Intent.EXTRA_TEXT, t.title + "\n\n" + Exporter.plainText(t, timestamps))

            is ShareContent.FileOf -> {
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, Exporter.fileName(t, content.format))
                Exporter.write(context, t, content.format, timestamps, file.outputStream())
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                Intent(Intent.ACTION_SEND)
                    .setType(content.format.mime)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, t.title)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .apply { clipData = ClipData.newRawUri(t.title, uri) }
            }
        }
}
