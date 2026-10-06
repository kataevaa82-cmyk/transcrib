package ru.transcrib.app.ui

import android.content.Intent
import android.Manifest
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ru.transcrib.app.data.SearchHit
import ru.transcrib.app.data.TranscriptRepository
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.transcrib.app.TranscribApp
import ru.transcrib.app.engine.Stage
import ru.transcrib.app.model.Transcript
import ru.transcrib.app.model.TranscriptMeta
import ru.transcrib.app.service.ActiveJob
import ru.transcrib.app.service.RecorderService

@Composable
fun HomeScreen(
    app: TranscribApp,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
    onRecord: () -> Unit,
    onAbout: () -> Unit,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var headerHeight by remember { mutableIntStateOf(0) }
    val items by app.repository.items.collectAsStateWithLifecycle()
    val jobs by app.jobs.state.collectAsStateWithLifecycle()
    val recorder by RecorderService.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var toDelete by remember { mutableStateOf<TranscriptMeta?>(null) }

    // Search
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>?>(null) }
    LaunchedEffect(query, items) {
        if (query.isBlank()) {
            hits = null
            return@LaunchedEffect
        }
        delay(250)
        hits = withContext(Dispatchers.IO) { app.repository.search(query) }
    }

    // Things that silently break long jobs: notifications off, aggressive battery savers.
    var notificationsOk by remember { mutableStateOf(true) }
    var batteryOk by remember { mutableStateOf(true) }
    var batteryDismissed by remember { mutableStateOf(app.settings.batteryHintDismissed) }
    LifecycleResumeEffect(Unit) {
        notificationsOk = NotificationManagerCompat.from(context).areNotificationsEnabled()
        batteryOk = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        onPauseOrDispose { }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsOk = granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!notificationsOk) openNotificationSettings(context)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            app.jobs.enqueue(uri)
        }
    }

    LaunchedEffect(jobs.lastCompletedId) {
        val id = jobs.lastCompletedId ?: return@LaunchedEffect
        app.jobs.consumeCompleted()
        // Open the result right away unless a batch is still being processed.
        if (jobs.active == null && jobs.queued.isEmpty()) onOpen(id)
    }
    LaunchedEffect(jobs.lastError) {
        val err = jobs.lastError ?: return@LaunchedEffect
        app.jobs.consumeError()
        snackbar.showSnackbar("Ошибка: $err")
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { Box(Modifier.onSizeChanged { headerHeight = it.height }) { Header(onAbout) } }
            item {
                ActionTiles(
                    recording = recorder.recording,
                    recordingMs = recorder.elapsedMs,
                    onPick = { picker.launch(arrayOf("audio/*", "video/*", "application/ogg")) },
                    onRecord = onRecord,
                    modifier = Modifier.offset(y = (-56).dp).padding(horizontal = 16.dp),
                )
            }

            if (!notificationsOk) {
                item(key = "notif") {
                    HintCard(
                        icon = Icons.Filled.Notifications,
                        title = "Уведомления выключены",
                        text = "Без них вы не узнаете, что расшифровка готова, если приложение свёрнуто.",
                        action = "Включить",
                        onAction = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                openNotificationSettings(context)
                            }
                        },
                        modifier = Modifier.offset(y = (-24).dp).padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            if (!batteryOk && !batteryDismissed && (items.isNotEmpty() || jobs.active != null)) {
                item(key = "battery") {
                    HintCard(
                        icon = AppIcons.Battery,
                        title = "Разрешите работу в фоне",
                        text = "Иначе система может остановить долгую расшифровку, когда экран выключен " +
                            "(особенно на Huawei, Honor, Xiaomi).",
                        action = "Разрешить",
                        onAction = { requestIgnoreBatteryOptimizations(context) },
                        onDismiss = {
                            batteryDismissed = true
                            app.settings.batteryHintDismissed = true
                        },
                        modifier = Modifier.offset(y = (-24).dp).padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            items(jobs.interrupted, key = { "i" + it.id }) { job ->
                HintCard(
                    icon = Icons.Filled.Refresh,
                    title = "Обработка прервана",
                    text = "«${job.title}» — система остановила приложение. Запустить заново?",
                    action = "Повторить",
                    onAction = { app.jobs.retry(job) },
                    onDismiss = { app.jobs.dismissInterrupted(job.id, deleteRecording = true) },
                    modifier = Modifier.offset(y = (-24).dp).padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            jobs.active?.let { active ->
                item(key = "active") {
                    JobCard(
                        job = active,
                        queued = jobs.queued.map { it.title },
                        onCancel = { app.jobs.cancelActive() },
                        modifier = Modifier.offset(y = (-24).dp).padding(horizontal = 16.dp),
                    )
                }
            }
            if (jobs.active == null && jobs.queued.isNotEmpty()) {
                items(jobs.queued, key = { "q" + it.id }) { q ->
                    QueuedRow(q.title, { app.jobs.removeQueued(q.id) }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }

            if (items.isNotEmpty()) {
                item(key = "historyHeader") {
                    HistoryHeader(
                        count = items.size,
                        searchOpen = searchOpen,
                        query = query,
                        onQuery = { query = it },
                        onOpenSearch = { searchOpen = true },
                        onCloseSearch = {
                            searchOpen = false
                            query = ""
                        },
                    )
                }
                val shown = hits
                if (shown != null) {
                    if (shown.isEmpty()) {
                        item(key = "noHits") {
                            Text(
                                "Ничего не найдено",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        }
                    }
                    items(shown, key = { "h" + it.meta.id }) { hit ->
                        HistoryCard(
                            hit.meta,
                            onClick = { onOpen(hit.meta.id) },
                            onShare = { onShare(hit.meta.id) },
                            onDelete = { toDelete = hit.meta },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                            snippet = hit.snippet,
                            highlight = query,
                        )
                    }
                } else {
                    items(items, key = { it.id }) { meta ->
                        HistoryCard(
                            meta,
                            onClick = { onOpen(meta.id) },
                            onShare = { onShare(meta.id) },
                            onDelete = { toDelete = meta },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                        )
                    }
                }
            } else if (jobs.active == null) {
                item { EmptyState() }
            }
            item { Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) }
        }
        // Once the dark header scrolls away, keep the status bar readable.
        val density = LocalDensity.current
        val statusBarPx = WindowInsets.statusBars.getTop(density)
        val overlapPx = with(density) { 56.dp.roundToPx() }
        val scrolled by remember(headerHeight, statusBarPx) {
            derivedStateOf {
                listState.firstVisibleItemIndex > 0 ||
                    (headerHeight > 0 && listState.firstVisibleItemScrollOffset > headerHeight - overlapPx - statusBarPx)
            }
        }
        SystemBarsIcons(lightIcons = !scrolled || LocalExtraColors.current.dark)
        if (scrolled) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(MaterialTheme.colorScheme.background),
            )
        }
        AppSnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars),
        )
    }

    toDelete?.let { meta ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить расшифровку?") },
            text = { Text("«${meta.title}» будет удалена вместе с аудио.") },
            confirmButton = {
                TextButton(onClick = {
                    app.repository.delete(meta.id)
                    toDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun Header(onAbout: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
            .background(Brand.graphiteGradient),
    ) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 88.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoMark(34.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Транскрибатор",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onAbout) {
                    Icon(Icons.Outlined.Info, "О приложении", tint = Color.White.copy(alpha = 0.7f))
                }
            }
            Spacer(Modifier.height(26.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Речь в текст", style = MaterialTheme.typography.displaySmall, color = Color.White)
                WaveDecoration(
                    Modifier.padding(start = 14.dp, end = 12.dp).weight(1f).height(40.dp),
                    bars = 14,
                    alpha = 0.9f,
                )
            }
            Text("без интернета", style = MaterialTheme.typography.displaySmall, color = Brand.Coral)
            Spacer(Modifier.height(10.dp))
            Text(
                "Пунктуация, предложения и спикеры.\nФайлы не покидают телефон.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.65f),
            )
        }
    }
}

@Composable
private fun ActionTiles(
    recording: Boolean,
    recordingMs: Long,
    onPick: () -> Unit,
    onRecord: () -> Unit,
    modifier: Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val shape = RoundedCornerShape(26.dp)
        Box(
            Modifier
                .weight(1f)
                .height(150.dp)
                .shadow(16.dp, shape, spotColor = Color(0x40000000), ambientColor = Color(0x22000000))
                .clip(shape)
                .background(LocalExtraColors.current.card)
                .clickable(onClick = onPick),
        ) {
            TileContent(
                icon = AppIcons.Folder,
                iconBg = Brand.coralGradient,
                title = "Выбрать файл",
                subtitle = "Аудио или видео",
                titleColor = MaterialTheme.colorScheme.onSurface,
                subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier
                .weight(1f)
                .height(150.dp)
                .shadow(16.dp, shape, spotColor = Brand.Coral, ambientColor = Brand.Coral.copy(alpha = 0.4f))
                .clip(shape)
                .background(Brand.coralGradient)
                .clickable(onClick = onRecord),
        ) {
            TileContent(
                icon = if (recording) AppIcons.Stop else AppIcons.Mic,
                iconBg = SolidColor(Color.White.copy(alpha = 0.22f)),
                title = "Диктофон",
                subtitle = if (recording) "Запись · ${formatDuration(recordingMs / 1000.0)}" else "Запись с микрофона",
                titleColor = Color.White,
                subtitleColor = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun TileContent(
    icon: ImageVector,
    iconBg: Brush,
    title: String,
    subtitle: String,
    titleColor: Color,
    subtitleColor: Color,
) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(iconBg),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.weight(1f))
        Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor)
        Text(
            subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun JobCard(job: ActiveJob, queued: List<String>, onCancel: () -> Unit, modifier: Modifier) {
    val progress by animateFloatAsState(job.progress, label = "progress")
    AppCard(modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(62.dp), contentAlignment = Alignment.Center) {
                    ProgressRing(progress, Modifier.fillMaxSize(), 6.dp, MaterialTheme.colorScheme.surfaceContainerHigh)
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(job.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            job.cancelling -> "Останавливаем…"
                            job.etaSec != null -> "${job.stage.title} · осталось ${etaText(job.etaSec)}"
                            else -> job.stage.title
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    StageSteps(job.stage)
                }
                IconButton(onClick = onCancel, enabled = !job.cancelling) {
                    Icon(Icons.Filled.Close, "Отменить", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                if (queued.isEmpty()) "Можно свернуть приложение — работа продолжится в фоне."
                else "Далее: " + queued.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun StageSteps(stage: Stage) {
    val steps = listOf("Звук", "Текст", "Спикеры")
    val current = when (stage) {
        Stage.DECODING -> 0
        Stage.LOADING, Stage.RECOGNIZING -> 1
        Stage.DIARIZING -> 2
        Stage.SAVING -> steps.size
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, s ->
            val done = i < current
            val active = i == current
            val bg = when {
                active -> MaterialTheme.colorScheme.primary
                done -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            }
            val fg = when {
                active -> MaterialTheme.colorScheme.onPrimary
                done -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box(Modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 3.dp)) {
                Text((if (done) "✓ " else "") + s, style = MaterialTheme.typography.labelSmall, color = fg)
            }
        }
    }
}

@Composable
private fun QueuedRow(title: String, onRemove: () -> Unit, modifier: Modifier) {
    AppCard(modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("В очереди", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Убрать") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryCard(
    meta: TranscriptMeta,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier,
    snippet: String? = null,
    highlight: String = "",
) {
    val context = LocalContext.current
    val date = remember(meta.createdAt) {
        DateUtils.formatDateTime(
            context, meta.createdAt,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
        )
    }
    val shape = RoundedCornerShape(22.dp)
    AppCard(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onDelete),
        shape = shape,
    ) {
        Row(Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 4.dp)) {
            val mic = meta.source == Transcript.SOURCE_MIC
            Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        when {
                            mic -> Brand.coralGradient
                            LocalExtraColors.current.dark -> SolidColor(Brand.Graphite500)
                            else -> Brand.graphiteGradient
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (mic) AppIcons.Mic else Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(meta.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MetaText(date)
                    MetaDot()
                    MetaText(formatDuration(meta.durationSec))
                    if (meta.numSpeakers > 1) {
                        MetaDot()
                        MetaText(speakersLabel(meta.numSpeakers))
                    }
                }
                val body = snippet ?: meta.preview
                if (body.isNotBlank()) {
                    Text(
                        highlighted(body, highlight, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            IconButton(onClick = onShare, modifier = Modifier.padding(top = 0.dp)) {
                Icon(Icons.Filled.Share, "Поделиться", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun MetaText(s: String) =
    Text(s, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)

@Composable
private fun MetaDot() = Box(
    Modifier
        .padding(horizontal = 6.dp)
        .size(3.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.outline),
)

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyIllustration(Modifier.width(200.dp).height(90.dp))
        Spacer(Modifier.height(16.dp))
        Text("Здесь появятся расшифровки", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "Совет: в диктофоне или мессенджере нажмите «Поделиться» и выберите «Транскрибатор» — " +
                "файл сразу уйдёт в работу.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HistoryHeader(
    count: Int,
    searchOpen: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
) {
    if (!searchOpen) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Расшифровки", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            ) { Text(count.toString(), style = MaterialTheme.typography.labelMedium) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenSearch) { Icon(Icons.Filled.Search, "Поиск по расшифровкам") }
        }
    } else {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("Слово, фраза или имя спикера") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { IconButton(onClick = onCloseSearch) { Icon(Icons.Filled.Close, "Закрыть поиск") } },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp)
                .focusRequester(focus),
        )
    }
}

@Composable
private fun HintCard(
    icon: ImageVector,
    title: String,
    text: String,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    AppCard(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 10.dp, end = 8.dp)) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Brand.coralGradient),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
                Row(Modifier.padding(top = 4.dp).offset(x = (-12).dp)) {
                    TextButton(onClick = onAction) { Text(action) }
                    if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Не сейчас") }
                }
            }
        }
    }
}

/** Text with every occurrence of [query] highlighted (case- and ё-insensitive). */
fun highlighted(text: String, query: String, color: Color): AnnotatedString {
    val q = TranscriptRepository.normalize(query.trim())
    if (q.isEmpty()) return AnnotatedString(text)
    val norm = TranscriptRepository.normalize(text)
    return buildAnnotatedString {
        append(text)
        var i = norm.indexOf(q)
        while (i >= 0) {
            addStyle(SpanStyle(background = color), i, i + q.length)
            i = norm.indexOf(q, i + q.length)
        }
    }
}

private fun etaText(sec: Long): String = when {
    sec < 60 -> "меньше минуты"
    sec < 3600 -> "~${(sec + 30) / 60} мин"
    else -> "~${sec / 3600} ч ${(sec % 3600) / 60} мин"
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
}

@android.annotation.SuppressLint("BatteryLife")
private fun requestIgnoreBatteryOptimizations(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
    runCatching { context.startActivity(direct) }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
}
