package ru.transcrib.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.transcrib.app.TranscribApp
import ru.transcrib.app.service.RecorderService
import java.util.Locale
import kotlin.math.sqrt

private const val HISTORY = 72

@Composable
fun RecorderScreen(app: TranscribApp, onBack: () -> Unit) {
    SystemBarsIcons(lightIcons = true)
    val context = LocalContext.current
    val state by RecorderService.state.collectAsStateWithLifecycle()
    val speakers by app.settings.speakersFlow.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val levels = remember { mutableStateListOf<Float>() }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) RecorderService.send(context, RecorderService.ACTION_START)
    }

    fun startRecording() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) RecorderService.send(context, RecorderService.ACTION_START)
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(state.elapsedMs, state.level) {
        if (state.recording && !state.paused) {
            levels.add(sqrt(state.level).coerceIn(0.04f, 1f))
            while (levels.size > HISTORY) levels.removeAt(0)
        }
    }
    LaunchedEffect(state.recording) {
        if (!state.recording) levels.clear()
    }
    LaunchedEffect(state.error) {
        val err = state.error ?: return@LaunchedEffect
        RecorderService.clearError()
        snackbar.showSnackbar(err)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Brand.Graphite700, Brand.Graphite900, Color(0xFF0A0B0E)))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White)
                }
                Text("Диктофон", style = MaterialTheme.typography.titleLarge, color = Color.White)
            }

            Spacer(Modifier.weight(0.7f))
            StatusChip(recording = state.recording, paused = state.paused)
            Spacer(Modifier.height(14.dp))
            Text(
                formatMs(state.elapsedMs),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 64.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum",
                ),
                color = Color.White,
            )
            Spacer(Modifier.height(28.dp))
            LiveWave(
                levels = levels,
                active = state.recording && !state.paused,
                modifier = Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 20.dp),
            )
            Spacer(Modifier.weight(1f))

            Row(
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(
                    icon = Icons.Filled.Delete,
                    label = "Удалить запись",
                    enabled = state.recording,
                ) { RecorderService.send(context, RecorderService.ACTION_DISCARD) }

                RecordButton(
                    recording = state.recording,
                    level = if (state.paused) 0f else (levels.lastOrNull() ?: 0f),
                ) {
                    if (state.recording) {
                        RecorderService.send(context, RecorderService.ACTION_STOP)
                        onBack()
                    } else {
                        startRecording()
                    }
                }

                RoundButton(
                    icon = if (state.paused) Icons.Filled.PlayArrow else AppIcons.Pause,
                    label = if (state.paused) "Продолжить" else "Пауза",
                    enabled = state.recording,
                ) {
                    RecorderService.send(
                        context,
                        if (state.paused) RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE,
                    )
                }
            }
            Text(
                when {
                    !state.recording -> "Нажмите, чтобы начать запись"
                    else -> "Стоп — сохранить и расшифровать"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
            Spacer(Modifier.height(28.dp))
            Box(
                Modifier
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(14.dp),
            ) {
                SpeakerSelector(speakers, { app.settings.speakers = it }, onDark = true)
            }
            Spacer(Modifier.height(16.dp))
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing))
    }
}

@Composable
private fun StatusChip(recording: Boolean, paused: Boolean) {
    val t = rememberInfiniteTransition(label = "blink")
    val blink by t.animateFloat(
        1f, 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "blinkA",
    )
    val (text, dot) = when {
        !recording -> "Готов к записи" to Color.White.copy(alpha = 0.4f)
        paused -> "Пауза" to Brand.Amber
        else -> "Запись" to Brand.Coral
    }
    Row(
        Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dot.copy(alpha = if (recording && !paused) blink else 1f)),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.9f))
    }
}

/** Scrolling mirrored level bars, newest on the right; older bars fade out. */
@Composable
private fun LiveWave(levels: List<Float>, active: Boolean, modifier: Modifier) {
    val idle = rememberInfiniteTransition(label = "idle")
    val phase by idle.animateFloat(
        0f, 6.2832f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "idlePhase",
    )
    Canvas(modifier) {
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (HISTORY - 1)) / HISTORY
        val mid = size.height / 2
        for (i in 0 until HISTORY) {
            val idx = levels.size - HISTORY + i
            val v = if (idx >= 0) levels[idx]
            else 0.04f + 0.03f * kotlin.math.sin(phase + i * 0.35f).toFloat().coerceAtLeast(0f)
            val h = (size.height * v).coerceAtLeast(4.dp.toPx())
            val fade = 0.25f + 0.75f * (i / HISTORY.toFloat())
            drawRoundRect(
                brush = if (idx >= 0) Brand.coralGradientVertical else Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.25f), Color.White.copy(alpha = 0.25f)),
                ),
                topLeft = Offset(i * (w + gap), mid - h / 2),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2, w / 2),
                alpha = if (idx >= 0) fade else 1f,
            )
        }
        if (!active && levels.isNotEmpty()) {
            drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
        }
    }
}

@Composable
private fun RecordButton(recording: Boolean, level: Float, onClick: () -> Unit) {
    val pulse by animateFloatAsState(if (recording) 1f + level * 0.55f else 1f, tween(120), label = "pulse")
    val t = rememberInfiniteTransition(label = "ring")
    val ring by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "ringP")
    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        if (recording) {
            Box(
                Modifier
                    .size(96.dp)
                    .scale(pulse * 1.18f)
                    .clip(CircleShape)
                    .background(Brand.Coral.copy(alpha = 0.18f)),
            )
        } else {
            // Idle: a soft ring expands to invite a tap.
            Box(
                Modifier
                    .size(96.dp)
                    .scale(1f + ring * 0.5f)
                    .clip(CircleShape)
                    .background(Brand.Coral.copy(alpha = 0.25f * (1f - ring))),
            )
        }
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Brand.coralGradient)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (recording) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(Color.White))
            } else {
                Icon(AppIcons.Mic, "Начать запись", tint = Color.White, modifier = Modifier.size(42.dp))
            }
        }
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    size: Dp = 58.dp,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.12f else 0.05f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White.copy(alpha = if (enabled) 1f else 0.3f))
    }
}

private fun formatMs(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    else String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
}
