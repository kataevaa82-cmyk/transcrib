package ru.transcrib.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import ru.transcrib.app.data.TranscriptRepository
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import ru.transcrib.app.export.ShareContent
import ru.transcrib.app.export.ShareTargets
import android.os.Build
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.transcrib.app.TranscribApp
import ru.transcrib.app.export.ExportFormat
import ru.transcrib.app.export.Exporter
import ru.transcrib.app.model.Paragraph
import ru.transcrib.app.model.Transcript
import ru.transcrib.app.model.Utterance

private val speeds = listOf(1f, 1.25f, 1.5f, 2f)

@Composable
fun TranscriptScreen(app: TranscribApp, id: String, openExport: Boolean = false, onBack: () -> Unit) {
    DefaultSystemBarsIcons()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var transcript by remember(id) { mutableStateOf<Transcript?>(null) }
    var loaded by remember(id) { mutableStateOf(false) }
    var showTimestamps by remember { mutableStateOf(app.settings.showTimestamps) }
    var chatView by remember { mutableStateOf(app.settings.chatView) }

    var renameTitle by remember { mutableStateOf(false) }
    var fileSheet by remember(id) { mutableStateOf(if (openExport) FileAction.SHARE else null) }
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    var speakerDialog by remember { mutableStateOf<Int?>(null) }
    var paragraphDialog by remember { mutableStateOf<Paragraph?>(null) }

    LaunchedEffect(id) {
        transcript = withContext(Dispatchers.IO) { runCatching { app.repository.load(id) }.getOrNull() }
        loaded = true
    }

    fun update(t: Transcript) {
        transcript = t
        scope.launch(Dispatchers.IO) { app.repository.save(t) }
    }

    // ---- Player ----
    val player = remember(id) { AudioPlayer.open(app.repository.audioFile(id)) }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(app.settings.playbackSpeed) }
    DisposableEffect(player) {
        player?.onComplete = { playing = false }
        onDispose { player?.release() }
    }
    LaunchedEffect(playing) {
        while (playing && player != null) {
            positionMs = player.positionMs
            delay(120)
        }
    }

    fun seekTo(sec: Double, start: Boolean = true) {
        val p = player ?: return
        val ms = (sec * 1000).toLong().coerceIn(0, p.durationMs.toLong())
        p.seekTo(ms)
        positionMs = ms.toInt()
        if (start && !p.isPlaying) {
            p.play(speed)
            playing = true
        }
    }

    fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            playing = false
        } else {
            p.play(speed)
            playing = true
        }
    }

    val listState = rememberLazyListState()

    // Find in text
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findIndex by remember { mutableIntStateOf(0) }
    val findMatches = remember(transcript, findQuery) {
        val t = transcript
        val q = TranscriptRepository.normalize(findQuery.trim())
        if (t == null || q.isEmpty()) emptyList()
        else t.paragraphs().withIndex().filter { TranscriptRepository.normalize(it.value.text).contains(q) }.map { it.index }
    }
    LaunchedEffect(findMatches) { findIndex = 0 }

    var textScale by remember { mutableFloatStateOf(app.settings.textScale) }
    var rerunDialog by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                t = transcript,
                onBack = onBack,
                onRename = { renameTitle = true },
                onDownload = { fileSheet = FileAction.DOWNLOAD },
                onExport = { fileSheet = FileAction.SHARE },
                showTimestamps = showTimestamps,
                onToggleTimestamps = {
                    showTimestamps = !showTimestamps
                    app.settings.showTimestamps = showTimestamps
                },
                onDelete = { confirmDelete = true },
                onFind = { findOpen = true },
                onRerun = if (player != null) ({ rerunDialog = true }) else null,
            )
            if (findOpen) {
                FindBar(
                    query = findQuery,
                    onQuery = { findQuery = it },
                    position = if (findMatches.isEmpty()) 0 else findIndex + 1,
                    total = findMatches.size,
                    onPrev = { if (findMatches.isNotEmpty()) findIndex = (findIndex - 1 + findMatches.size) % findMatches.size },
                    onNext = { if (findMatches.isNotEmpty()) findIndex = (findIndex + 1) % findMatches.size },
                    onClose = {
                        findOpen = false
                        findQuery = ""
                    },
                )
            }
            val t = transcript
            when {
                !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                t == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Расшифровка не найдена")
                }

                else -> CompositionLocalProvider(
                    LocalReading provides Reading(textScale, findQuery),
                ) { TranscriptBody(
                    t = t,
                    focusParagraph = findMatches.getOrNull(findIndex) ?: -1,
                    textScale = textScale,
                    onTextScale = {
                        textScale = it
                        app.settings.textScale = it
                    },
                    listState = listState,
                    chatView = chatView,
                    onChatView = {
                        chatView = it
                        app.settings.chatView = it
                    },
                    showTimestamps = showTimestamps,
                    currentSec = if (player != null && (playing || positionMs > 0)) positionMs / 1000.0 else null,
                    follow = playing,
                    bottomPadding = if (player != null) 120.dp else 32.dp,
                    onSeek = { seekTo(it) },
                    onSpeaker = { speakerDialog = it },
                    onParagraph = { paragraphDialog = it },
                    onShare = { fileSheet = FileAction.SHARE },
                    onDownload = { fileSheet = FileAction.DOWNLOAD },
                    onCopy = {
                        Exporter.copyToClipboard(context, t, showTimestamps)
                        Toast.makeText(context, "Текст скопирован", Toast.LENGTH_SHORT).show()
                    },
                ) }
            }
        }
        if (player != null) {
            FloatingPlayer(
                playing = playing,
                positionMs = positionMs,
                durationMs = player.durationMs,
                speed = speed,
                onToggle = { togglePlay() },
                onBack5 = { seekTo((positionMs - 5000) / 1000.0, start = false) },
                onSeek = { seekTo(it / 1000.0, start = false) },
                onSpeed = {
                    speed = speeds[(speeds.indexOf(speed) + 1) % speeds.size]
                    app.settings.playbackSpeed = speed
                    player.setSpeed(speed)
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        AppSnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = if (player != null) 92.dp else 8.dp),
        )
    }

    val t = transcript ?: return

    if (fileSheet == FileAction.SHARE) {
        ShareSheet(
            app = app,
            t = t,
            timestamps = showTimestamps,
            onTimestamps = {
                showTimestamps = it
                app.settings.showTimestamps = it
            },
            onDismiss = { fileSheet = null },
        )
    }
    fileSheet?.takeIf { it == FileAction.DOWNLOAD }?.let { action ->
        FileSheet(
            t = t,
            action = action,
            timestamps = showTimestamps,
            onTimestamps = {
                showTimestamps = it
                app.settings.showTimestamps = it
            },
            onDismiss = { fileSheet = null },
            onSaved = { uri, name, format ->
                scope.launch {
                    val r = snackbar.showSnackbar(
                        "Сохранено в «Загрузки»: $name",
                        actionLabel = "Открыть",
                        duration = SnackbarDuration.Long,
                    )
                    if (r == SnackbarResult.ActionPerformed) Exporter.open(context, uri, format)
                }
            },
        )
    }

    if (rerunDialog) {
        RerunDialog(
            onDismiss = { rerunDialog = false },
            onConfirm = {
                rerunDialog = false
                player?.pause()
                playing = false
                app.jobs.rerun(t.id, t.title, t.createdAt, app.repository.audioFile(t.id))
                Toast.makeText(context, "Распознаём заново — ход виден на главном экране", Toast.LENGTH_LONG).show()
                onBack()
            },
        )
    }

    if (renameTitle) {
        TextInputDialog(
            title = "Название",
            initial = t.title,
            onDismiss = { renameTitle = false },
            onConfirm = {
                renameTitle = false
                if (it.isNotBlank()) update(t.copy(title = it.trim()))
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить расшифровку?") },
            text = { Text("Текст и аудио будут удалены с телефона.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    // Only stop here: the player is released once the screen leaves composition.
                    player?.pause()
                    playing = false
                    app.repository.delete(t.id)
                    onBack()
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }

    speakerDialog?.let { sp ->
        SpeakerDialog(
            t = t,
            speaker = sp,
            onDismiss = { speakerDialog = null },
            onRename = { name ->
                speakerDialog = null
                val names = t.speakerNames.toMutableMap()
                if (name.isBlank()) names.remove(sp) else names[sp] = name.trim()
                update(t.copy(speakerNames = names))
            },
            onMerge = { into ->
                speakerDialog = null
                update(reassign(t, { it.speaker == sp }, into))
            },
        )
    }

    paragraphDialog?.let { p ->
        ParagraphDialog(
            t = t,
            paragraph = p,
            onDismiss = { paragraphDialog = null },
            onCopy = {
                paragraphDialog = null
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("text", p.text))
                Toast.makeText(context, "Абзац скопирован", Toast.LENGTH_SHORT).show()
            },
            onSetSpeaker = { sp ->
                paragraphDialog = null
                val set = p.utterances.toSet()
                update(reassign(t, { it in set }, sp))
            },
            onEdit = { text ->
                paragraphDialog = null
                val first = t.utterances.indexOf(p.utterances.first())
                if (first >= 0) {
                    val rest = t.utterances.filterNot { u -> p.utterances.any { it === u } }.toMutableList()
                    if (text.isNotBlank()) {
                        rest.add(first, Utterance(p.start, p.end, p.speaker, text.trim().replace(Regex("\\s+"), " ")))
                    }
                    update(t.copy(utterances = rest))
                }
            },
        )
    }
}

/** Moves matching utterances to [speaker]. */
private fun reassign(t: Transcript, match: (Utterance) -> Boolean, speaker: Int): Transcript {
    val utt = t.utterances.map { if (match(it)) it.copy(speaker = speaker) else it }
    val used = utt.map { it.speaker }.toSet().size
    return t.copy(utterances = utt, numSpeakers = if (used > 1) used else -1)
}

@Composable
private fun TopBar(
    t: Transcript?,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onDownload: () -> Unit,
    onExport: () -> Unit,
    showTimestamps: Boolean,
    onToggleTimestamps: () -> Unit,
    onDelete: () -> Unit,
    onFind: () -> Unit,
    onRerun: (() -> Unit)?,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = t != null, onClick = onRename)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Text(t?.title ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t != null) {
                val date = remember(t.createdAt) {
                    DateUtils.formatDateTime(
                        context, t.createdAt,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
                    )
                }
                Text(
                    "$date · ${formatDuration(t.durationSec)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (t != null) {
            IconButton(onClick = onFind) { Icon(Icons.Filled.Search, "Найти в тексте") }
            IconButton(onClick = onExport) { Icon(Icons.Filled.Share, "Поделиться") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Ещё") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Переименовать") }, onClick = {
                        menu = false
                        onRename()
                    })
                    DropdownMenuItem(
                        text = { Text(if (showTimestamps) "Скрыть время" else "Показывать время") },
                        onClick = {
                            menu = false
                            onToggleTimestamps()
                        },
                    )
                    DropdownMenuItem(text = { Text("Скачать файл") }, onClick = {
                        menu = false
                        onDownload()
                    })
                    if (onRerun != null) {
                        DropdownMenuItem(text = { Text("Распознать заново…") }, onClick = {
                            menu = false
                            onRerun()
                        })
                    }
                    DropdownMenuItem(text = { Text("Удалить") }, onClick = {
                        menu = false
                        onDelete()
                    })
                }
            }
        }
    }
}

@Composable
private fun TranscriptBody(
    t: Transcript,
    listState: LazyListState,
    chatView: Boolean,
    onChatView: (Boolean) -> Unit,
    showTimestamps: Boolean,
    currentSec: Double?,
    follow: Boolean,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onSeek: (Double) -> Unit,
    onSpeaker: (Int) -> Unit,
    onParagraph: (Paragraph) -> Unit,
    onShare: () -> Unit,
    onDownload: () -> Unit,
    onCopy: () -> Unit,
    focusParagraph: Int,
    textScale: Float,
    onTextScale: (Float) -> Unit,
) {
    val paragraphs = remember(t) { t.paragraphs() }
    LaunchedEffect(focusParagraph) {
        if (focusParagraph >= 0) listState.animateScrollToItem(focusParagraph + 1, scrollOffset = -80)
    }
    val current: Utterance? = remember(currentSec, t) {
        currentSec?.let { sec -> t.utterances.lastOrNull { it.start <= sec + 0.05 }?.takeIf { sec <= it.end + 1.0 } }
    }
    val talk = remember(t) {
        t.utterances.groupBy { it.speaker }.mapValues { (_, v) -> v.sumOf { it.end - it.start } }
    }
    val showSpeakers = t.hasSpeakers && talk.size > 1
    val twoSided = showSpeakers && talk.size == 2
    val useChat = showSpeakers && chatView

    // Keep the sentence being played on screen.
    val currentParagraph = remember(current, paragraphs) {
        if (current == null) -1 else paragraphs.indexOfFirst { p -> p.utterances.any { it === current } }
    }
    LaunchedEffect(currentParagraph, follow) {
        if (!follow || currentParagraph < 0) return@LaunchedEffect
        val item = currentParagraph + 1
        val visible = listState.layoutInfo.visibleItemsInfo
        val fully = visible.any { it.index == item && it.offset >= 0 &&
            it.offset + it.size <= listState.layoutInfo.viewportEndOffset - 200 }
        if (!fully) listState.animateScrollToItem(item, scrollOffset = -120)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding),
    ) {
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (paragraphs.isNotEmpty()) {
                    ShareBar(onShare, onDownload, onCopy)
                    Spacer(Modifier.height(14.dp))
                }
                if (showSpeakers) {
                    Participants(t, talk, onSpeaker)
                    Spacer(Modifier.height(12.dp))
                }
                if (paragraphs.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (showSpeakers) ViewToggle(chatView, onChatView)
                        Spacer(Modifier.weight(1f))
                        TextSizeControl(textScale, onTextScale)
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (paragraphs.isEmpty()) {
                    Text(
                        "Речь не обнаружена.",
                        modifier = Modifier.padding(vertical = 32.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        itemsIndexed(paragraphs, key = { i, p -> "$i-${p.start}" }) { _, p ->
            if (useChat) {
                ChatBubble(t, p, twoSided, showTimestamps, current, onSeek, onSpeaker) { onParagraph(p) }
            } else {
                DocParagraph(t, p, showSpeakers, showTimestamps, current, onSeek, onSpeaker) { onParagraph(p) }
            }
        }
        item {
            Text(
                "Нажмите на фразу, чтобы услышать её. Долгое нажатие — правка.\n" +
                    "Обработано на телефоне за ${formatDuration(t.processingSec)}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            )
        }
    }
}

@Composable
private fun Participants(t: Transcript, talk: Map<Int, Double>, onSpeaker: (Int) -> Unit) {
    val total = talk.values.sum().coerceAtLeast(0.001)
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        talk.keys.sorted().forEach { sp ->
            val color = speakerColor(sp)
            AppCard(
                Modifier.clip(RoundedCornerShape(18.dp)).clickable { onSpeaker(sp) },
                shape = RoundedCornerShape(18.dp),
            ) {
                Row(Modifier.padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SpeakerAvatar(t.speakerName(sp), color, 30.dp)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(t.speakerName(sp), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        Text(
                            "${((talk[sp] ?: 0.0) / total * 100).toInt()}% времени",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewToggle(chat: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(3.dp),
    ) {
        listOf(true to "Диалог", false to "Текст").forEach { (v, label) ->
            val sel = chat == v
            Box(
                Modifier
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (sel) LocalExtraColors.current.card else Color.Transparent)
                    .clickable { onChange(v) }
                    .padding(horizontal = 18.dp, vertical = 7.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChatBubble(
    t: Transcript,
    p: Paragraph,
    twoSided: Boolean,
    showTimestamps: Boolean,
    current: Utterance?,
    onSeek: (Double) -> Unit,
    onSpeaker: (Int) -> Unit,
    onLongPress: () -> Unit,
) {
    val right = twoSided && p.speaker % 2 == 1
    val color = speakerColor(p.speaker)
    val dark = LocalExtraColors.current.dark
    val name = t.speakerName(p.speaker)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = if (right) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!right) {
            Box(Modifier.padding(top = 20.dp).clip(CircleShape).clickable { onSpeaker(p.speaker) }) {
                SpeakerAvatar(name, color, 32.dp)
            }
            Spacer(Modifier.width(8.dp))
        }
        Column(
            Modifier.weight(1f, fill = false).widthIn(max = 340.dp),
            horizontalAlignment = if (right) Alignment.End else Alignment.Start,
        ) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                    modifier = Modifier.clickable { onSpeaker(p.speaker) },
                )
                if (showTimestamps) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatDuration(p.start),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { onSeek(p.start) },
                    )
                }
            }
            val shape = RoundedCornerShape(
                topStart = if (right) 20.dp else 6.dp,
                topEnd = if (right) 6.dp else 20.dp,
                bottomStart = 20.dp,
                bottomEnd = 20.dp,
            )
            Surface(shape = shape, color = color.copy(alpha = if (dark) 0.17f else 0.10f)) {
                UtteranceText(
                    p = p,
                    current = current,
                    style = MaterialTheme.typography.bodyLarge,
                    onSeek = onSeek,
                    onLongPress = onLongPress,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        if (right) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.padding(top = 20.dp).clip(CircleShape).clickable { onSpeaker(p.speaker) }) {
                SpeakerAvatar(name, color, 32.dp)
            }
        }
    }
}

@Composable
private fun DocParagraph(
    t: Transcript,
    p: Paragraph,
    showSpeaker: Boolean,
    showTimestamps: Boolean,
    current: Utterance?,
    onSeek: (Double) -> Unit,
    onSpeaker: (Int) -> Unit,
    onLongPress: () -> Unit,
) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
        if (showSpeaker || showTimestamps) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showSpeaker) {
                    val c = speakerColor(p.speaker)
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable { onSpeaker(p.speaker) }.padding(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(c))
                        Spacer(Modifier.width(6.dp))
                        Text(t.speakerName(p.speaker), style = MaterialTheme.typography.labelLarge, color = c)
                    }
                    Spacer(Modifier.width(10.dp))
                }
                if (showTimestamps) {
                    Text(
                        formatDuration(p.start),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onSeek(p.start) }.padding(2.dp),
                    )
                }
            }
        }
        UtteranceText(
            p = p,
            current = current,
            style = MaterialTheme.typography.bodyLarge,
            onSeek = onSeek,
            onLongPress = onLongPress,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/** Paragraph text where each sentence is tappable and the one being played is highlighted. */
@Composable
private fun UtteranceText(
    p: Paragraph,
    current: Utterance?,
    style: TextStyle,
    onSeek: (Double) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier,
) {
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val reading = LocalReading.current
    val found = Brand.Amber.copy(alpha = 0.55f)
    val q = TranscriptRepository.normalize(reading.find.trim())
    val ranges = ArrayList<IntRange>(p.utterances.size)
    val text: AnnotatedString = buildAnnotatedString {
        p.utterances.forEachIndexed { i, u ->
            if (i > 0) append(' ')
            val start = length
            if (current != null && u === current) {
                pushStyle(SpanStyle(background = highlight))
                append(u.text)
                pop()
            } else {
                append(u.text)
            }
            ranges += start until length
        }
        if (q.isNotEmpty()) {
            // Same text as appended above: sentences joined by single spaces.
            val norm = TranscriptRepository.normalize(p.utterances.joinToString(" ") { it.text })
            var i = norm.indexOf(q)
            while (i >= 0) {
                addStyle(SpanStyle(background = found), i, i + q.length)
                i = norm.indexOf(q, i + q.length)
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = text,
        style = style.copy(fontSize = style.fontSize * reading.scale, lineHeight = style.lineHeight * reading.scale),
        color = MaterialTheme.colorScheme.onSurface,
        onTextLayout = { layout = it },
        modifier = modifier.pointerInput(p) {
            detectTapGestures(
                onTap = { pos ->
                    val offset = layout?.getOffsetForPosition(pos) ?: return@detectTapGestures
                    val idx = ranges.indexOfFirst { offset in it.first..(it.last + 1) }
                    if (idx >= 0) onSeek(p.utterances[idx].start)
                },
                onLongPress = { onLongPress() },
            )
        },
    )
}

@Composable
private fun FloatingPlayer(
    playing: Boolean,
    positionMs: Int,
    durationMs: Int,
    speed: Float,
    onToggle: () -> Unit,
    onBack5: () -> Unit,
    onSeek: (Int) -> Unit,
    onSpeed: () -> Unit,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = Brand.Graphite800,
        shadowElevation = 14.dp,
    ) {
        Row(Modifier.padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(Brand.coralGradient)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playing) AppIcons.Pause else Icons.Filled.PlayArrow,
                    if (playing) "Пауза" else "Слушать",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            PlayerIcon(AppIcons.Replay5, "Назад на 5 секунд", onBack5)
            Column(Modifier.weight(1f)) {
                val dur = durationMs.coerceAtLeast(1)
                SeekBar(
                    fraction = positionMs.toFloat() / dur,
                    onSeek = { onSeek((it * dur).toInt()) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
                Row(Modifier.padding(horizontal = 4.dp)) {
                    val timeStyle = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
                    Text(formatDuration(positionMs / 1000.0), style = timeStyle, color = Color.White.copy(alpha = 0.7f))
                    Spacer(Modifier.weight(1f))
                    Text(formatDuration(durationMs / 1000.0), style = timeStyle, color = Color.White.copy(alpha = 0.7f))
                }
            }
            Box(
                Modifier
                    .padding(start = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    .clickable(onClick = onSpeed)
                    .padding(horizontal = 9.dp, vertical = 6.dp),
            ) {
                Text(
                    (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()) + "×",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun PlayerIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, label, tint = Color.White.copy(alpha = 0.85f)) }
}

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    singleLine: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 4,
                maxLines = if (singleLine) 1 else 12,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun SpeakerDialog(
    t: Transcript,
    speaker: Int,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onMerge: (Int) -> Unit,
) {
    var name by remember { mutableStateOf(t.speakerNames[speaker] ?: "") }
    val others = remember(t) { t.utterances.map { it.speaker }.distinct().filter { it != speaker }.sorted() }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { SpeakerAvatar(t.speakerName(speaker), speakerColor(speaker), 44.dp) },
        title = { Text("Спикер") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Имя") },
                    placeholder = { Text(t.speakerName(speaker)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (others.isNotEmpty()) {
                    Text(
                        "Это тот же человек, что и:",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    others.forEach { o -> SpeakerRow(t, o) { onMerge(o) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRename(name) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun ParagraphDialog(
    t: Transcript,
    paragraph: Paragraph,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onSetSpeaker: (Int) -> Unit,
    onEdit: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    if (editing) {
        TextInputDialog(
            title = "Правка текста",
            initial = paragraph.text,
            singleLine = false,
            onDismiss = onDismiss,
            onConfirm = onEdit,
        )
        return
    }
    val speakers = remember(t) {
        val used = t.utterances.map { it.speaker }.distinct().sorted()
        used + ((used.maxOrNull() ?: -1) + 1)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Абзац") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ActionRow("Редактировать текст", Icons.Filled.Edit) { editing = true }
                ActionRow("Копировать", AppIcons.Copy, onCopy)
                Text(
                    "Кто это говорит:",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                speakers.forEach { sp ->
                    SpeakerRow(t, sp, selected = sp == paragraph.speaker) { onSetSpeaker(sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun ActionRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Text(label)
    }
}

@Composable
private fun SpeakerRow(t: Transcript, speaker: Int, selected: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeakerAvatar(t.speakerName(speaker), speakerColor(speaker), 28.dp)
        Spacer(Modifier.width(10.dp))
        Text(t.speakerName(speaker), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun SeekBar(fraction: Float, onSeek: (Float) -> Unit, modifier: Modifier) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val seek by rememberUpdatedState(onSeek)
    val shown = (if (dragging) dragFraction else fraction).coerceIn(0f, 1f)
    Canvas(
        modifier
            .height(26.dp)
            .pointerInput(Unit) {
                detectTapGestures { pos -> seek((pos.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        dragFraction = (it.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        dragging = false
                        seek(dragFraction)
                    },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            },
    ) {
        val h = 4.dp.toPx()
        val cy = size.height / 2
        val r = CornerRadius(h / 2, h / 2)
        drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(0f, cy - h / 2), Size(size.width, h), r)
        drawRoundRect(Brand.coralGradient, Offset(0f, cy - h / 2), Size(size.width * shown, h), r)
        drawCircle(Color.White, radius = (if (dragging) 8.dp else 6.dp).toPx(), center = Offset(size.width * shown, cy))
    }
}

private enum class FileAction { SHARE, DOWNLOAD }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileSheet(
    t: Transcript,
    action: FileAction,
    timestamps: Boolean,
    onTimestamps: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSaved: (Uri, String, ExportFormat) -> Unit,
) {
    val context = LocalContext.current
    // Android 8–9 have no shared Downloads API without a storage permission: let the user pick a place.
    val pickers = ExportFormat.entries.associateWith { format ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mime)) { uri ->
            if (uri != null) {
                val ok = runCatching { Exporter.saveTo(context, t, format, timestamps, uri) }.isSuccess
                if (ok) onSaved(uri, Exporter.fileName(t, format), format)
                else Toast.makeText(context, "Не удалось сохранить файл", Toast.LENGTH_SHORT).show()
            }
            onDismiss()
        }
    }

    fun run(format: ExportFormat) {
        when (action) {
            FileAction.SHARE -> {
                runCatching { Exporter.share(context, t, format, timestamps) }
                    .onFailure { Toast.makeText(context, "Не удалось подготовить файл", Toast.LENGTH_SHORT).show() }
                onDismiss()
            }

            FileAction.DOWNLOAD -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { Exporter.saveToDownloads(context, t, format, timestamps) }
                    .onSuccess { (uri, name) -> onSaved(uri, name, format) }
                    .onFailure { Toast.makeText(context, "Не удалось сохранить: ${it.message}", Toast.LENGTH_LONG).show() }
                onDismiss()
            } else {
                pickers.getValue(format).launch(Exporter.fileName(t, format))
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LocalExtraColors.current.card,
    ) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            Text(
                if (action == FileAction.SHARE) "Поделиться" else "Скачать",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                if (action == FileAction.SHARE) {
                    "Выберите формат — откроется список приложений: MAX, VK, Telegram, почта, облако."
                } else {
                    "Файл сохранится на телефон в папку «Загрузки/${Exporter.DOWNLOAD_FOLDER}»."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            ExportFormat.entries.forEach { f ->
                val (icon, tile) = when (f) {
                    ExportFormat.DOCX -> AppIcons.Doc to Color(0xFF2B5CB8)
                    ExportFormat.TXT -> AppIcons.Doc to Brand.Graphite600
                    ExportFormat.SRT -> AppIcons.Subtitles to Brand.CoralDeep
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { run(f) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(tile),
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.label, style = MaterialTheme.typography.titleMedium)
                        Text(f.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(
                        if (action == FileAction.SHARE) Icons.Filled.Share else AppIcons.Download,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { onTimestamps(!timestamps) }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Время реплик", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Метки вида 1:23 перед абзацами",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = timestamps, onCheckedChange = onTimestamps)
            }
        }
    }
}

/** Prominent actions right under the title: the main things people do with a finished transcript. */
@Composable
private fun ShareBar(onShare: () -> Unit, onDownload: () -> Unit, onCopy: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier
                .weight(1f)
                .height(50.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brand.coralGradient)
                .clickable(onClick = onShare),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.Share, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Поделиться", style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
        }
        Row(
            Modifier
                .weight(1f)
                .height(50.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onDownload),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(AppIcons.Download, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Скачать", style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
        Box(
            Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onCopy),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Copy, "Копировать текст", modifier = Modifier.size(20.dp))
        }
    }
}

private val shareChoices = listOf(
    ShareContent.FileOf(ExportFormat.DOCX) to "Word",
    ShareContent.Message to "Текстом",
    ShareContent.FileOf(ExportFormat.TXT) to "TXT",
    ShareContent.FileOf(ExportFormat.SRT) to "SRT",
)

private fun shareKey(c: ShareContent) = when (c) {
    ShareContent.Message -> "text"
    is ShareContent.FileOf -> c.format.ext
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSheet(
    app: TranscribApp,
    t: Transcript,
    timestamps: Boolean,
    onTimestamps: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var content by remember {
        mutableStateOf(shareChoices.firstOrNull { shareKey(it.first) == app.settings.shareFormat }?.first
            ?: shareChoices.first().first)
    }
    val targets = remember(content) { ShareTargets.available(context, content) }
    val textLength = remember(t, timestamps) { Exporter.plainText(t, timestamps).length }

    fun send(pkg: String?) {
        app.settings.shareFormat = shareKey(content)
        runCatching { ShareTargets.send(context, t, content, timestamps, pkg) }
            .onFailure { Toast.makeText(context, "Не удалось отправить: ${it.message}", Toast.LENGTH_LONG).show() }
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LocalExtraColors.current.card) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            Text("Поделиться", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(14.dp))
            Text("Как отправить", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                shareChoices.forEach { (c, label) ->
                    val sel = shareKey(c) == shareKey(content)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable { content = c },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                when (val c = content) {
                    ShareContent.Message -> if (textLength > 4000) {
                        "Текст вставится в сообщение. Он длинный — мессенджер может разбить его на части, " +
                            "удобнее отправить файлом Word."
                    } else {
                        "Текст расшифровки вставится прямо в сообщение."
                    }

                    is ShareContent.FileOf -> when (c.format) {
                        ExportFormat.DOCX -> "Документ Word: спикеры, время реплик — удобно читать и печатать."
                        ExportFormat.TXT -> "Простой текстовый файл."
                        ExportFormat.SRT -> "Субтитры для видеоплееров и монтажа."
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )

            Spacer(Modifier.height(18.dp))
            Text("Куда", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                targets.forEach { target ->
                    val bitmap = remember(target.packageName) {
                        runCatching { target.icon.toBitmap(144, 144).asImageBitmap() }.getOrNull()
                    }
                    AppTile(target.label, { send(target.packageName) }) {
                        if (bitmap != null) {
                            Image(bitmap, null, Modifier.fillMaxSize())
                        } else {
                            Icon(Icons.Filled.Share, null, tint = Color.White)
                        }
                    }
                }
                AppTile("Ещё…", { send(null) }) {
                    Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) { Icon(AppIcons.More, null, tint = MaterialTheme.colorScheme.onSurface) }
                }
            }
            if (targets.isEmpty()) {
                Text(
                    "MAX, VK и Telegram не найдены — нажмите «Ещё…», чтобы выбрать приложение.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { onTimestamps(!timestamps) }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Время реплик", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Метки вида 1:23 перед абзацами",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = timestamps, onCheckedChange = onTimestamps)
            }
        }
    }
}

@Composable
private fun AppTile(label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(
        Modifier
            .width(68.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** Reading preferences passed down to every paragraph. */
private data class Reading(val scale: Float, val find: String)

private val LocalReading = staticCompositionLocalOf { Reading(1f, "") }

private val textScales = listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f)

@Composable
private fun TextSizeControl(scale: Float, onChange: (Float) -> Unit) {
    val i = textScales.indexOfFirst { kotlin.math.abs(it - scale) < 0.01f }.let { if (it < 0) 1 else it }
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .clickable(enabled = i > 0) { onChange(textScales[i - 1]) }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text("A", style = MaterialTheme.typography.labelMedium,
                color = if (i > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
        }
        Box(
            Modifier
                .clickable(enabled = i < textScales.size - 1) { onChange(textScales[i + 1]) }
                .padding(horizontal = 14.dp, vertical = 5.dp),
        ) {
            Text("A", style = MaterialTheme.typography.titleLarge,
                color = if (i < textScales.size - 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun FindBar(
    query: String,
    onQuery: (String) -> Unit,
    position: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("Найти в тексте") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            suffix = { if (query.isNotBlank()) Text("$position/$total", style = MaterialTheme.typography.labelMedium) },
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        IconButton(onClick = onPrev, enabled = total > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Предыдущее") }
        IconButton(onClick = onNext, enabled = total > 0) { Icon(Icons.Filled.KeyboardArrowDown, "Следующее") }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Закрыть поиск") }
    }
}

@Composable
private fun RerunDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Распознать заново") },
        text = {
            Text(
                "Расшифровка будет пересчитана из сохранённой записи. " +
                    "Ручные правки текста и имена спикеров сбросятся.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Распознать") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
