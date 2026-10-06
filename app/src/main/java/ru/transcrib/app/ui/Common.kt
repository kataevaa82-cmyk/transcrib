package ru.transcrib.app.ui

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Snackbar host with the brand accent on the action button. */
@Composable
fun AppSnackbarHost(state: androidx.compose.material3.SnackbarHostState, modifier: Modifier = Modifier) {
    androidx.compose.material3.SnackbarHost(state, modifier) { data ->
        androidx.compose.material3.Snackbar(
            data,
            shape = RoundedCornerShape(16.dp),
            containerColor = Brand.Graphite700,
            contentColor = Color.White,
            actionColor = Brand.Coral,
        )
    }
}

/** Sets status/navigation bar icon color for the current screen. */
@Composable
fun SystemBarsIcons(lightIcons: Boolean) {
    val view = LocalView.current
    LaunchedEffect(lightIcons) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val c = WindowCompat.getInsetsController(window, view)
        c.isAppearanceLightStatusBars = !lightIcons
        c.isAppearanceLightNavigationBars = !lightIcons
    }
}

/** Default screen bar icons: dark on light theme, light on dark theme. */
@Composable
fun DefaultSystemBarsIcons() = SystemBarsIcons(lightIcons = LocalExtraColors.current.dark)

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(22.dp),
    color: Color = LocalExtraColors.current.card,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = color,
        border = BorderStroke(1.dp, LocalExtraColors.current.cardBorder),
        content = content,
    )
}

/** Brand mark: coral tile with sound bars. */
@Composable
fun LogoMark(size: Dp = 36.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(Brand.coralGradient),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.56f)) {
            val heights = listOf(0.35f, 0.75f, 1f, 0.55f)
            val w = this.size.width / (heights.size * 2 - 1)
            heights.forEachIndexed { i, h ->
                val bh = this.size.height * h
                drawRoundRect(
                    Color.White,
                    topLeft = Offset(i * 2 * w, (this.size.height - bh) / 2),
                    size = Size(w, bh),
                    cornerRadius = CornerRadius(w / 2, w / 2),
                )
            }
        }
    }
}

/** Decorative animated sound wave. */
@Composable
fun WaveDecoration(modifier: Modifier, bars: Int = 36, brush: Brush = Brand.coralGradient, alpha: Float = 1f) {
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(
        0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Canvas(modifier) {
        val gap = size.width / bars * 0.45f
        val w = size.width / bars - gap
        for (i in 0 until bars) {
            val x = i / bars.toFloat()
            val env = sin(PI * x).toFloat() // fade to the edges
            val v = 0.18f + 0.82f * env * (0.55f + 0.45f * sin(phase + i * 0.55f)) *
                (0.6f + 0.4f * abs(sin(i * 1.7f)))
            val h = size.height * v.coerceIn(0.08f, 1f)
            drawRoundRect(
                brush = brush,
                topLeft = Offset(i * (w + gap), (size.height - h) / 2),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2, w / 2),
                alpha = alpha,
            )
        }
    }
}

@Composable
fun ProgressRing(progress: Float, modifier: Modifier, stroke: Dp = 6.dp, track: Color) {
    Canvas(modifier) {
        val s = stroke.toPx()
        val inset = s / 2
        val arcSize = Size(size.width - s, size.height - s)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(s))
        drawArc(
            Brand.coralGradient, -90f, 360f * progress.coerceIn(0f, 1f), false,
            Offset(inset, inset), arcSize, style = Stroke(s, cap = StrokeCap.Round),
        )
    }
}

private val speakerOptions = listOf(0 to "Авто", 1 to "1", 2 to "2", 3 to "3", 4 to "4", 5 to "5", 6 to "6")

/** Segmented control for the expected number of speakers. */
@Composable
fun SpeakerSelector(value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier, onDark: Boolean = false) {
    val track = if (onDark) Color.White.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainer
    val idleText = if (onDark) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
    val hintColor = if (onDark) Color.White.copy(alpha = 0.55f) else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(track)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            speakerOptions.forEach { (v, label) ->
                val selected = value == v
                val bg by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, label = "seg",
                )
                val fg by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.onPrimary else idleText, label = "segText",
                )
                Box(
                    Modifier
                        .weight(if (v == 0) 1.6f else 1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(bg)
                        .clickable { onChange(v) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
                }
            }
        }
        Text(
            when (value) {
                0 -> "Число голосов определится само. Знаете точно — укажите, так надёжнее."
                1 -> "Один голос: только текст по предложениям, без разметки спикеров."
                else -> "В записи ${speakersLabel(value)} — каждая реплика будет подписана."
            },
            style = MaterialTheme.typography.bodySmall,
            color = hintColor,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp),
        )
    }
}

/** Round avatar with the speaker's initial. */
@Composable
fun SpeakerAvatar(name: String, color: Color, size: Dp = 32.dp) {
    val initial = name.trim().let { n ->
        if (n.startsWith("Спикер ")) n.removePrefix("Спикер ").take(2) else n.take(1).uppercase()
    }
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun EmptyIllustration(modifier: Modifier = Modifier) {
    val lines = MaterialTheme.colorScheme.outline
    Box(modifier, contentAlignment = Alignment.Center) {
        WaveDecoration(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp), bars = 22, alpha = 0.9f)
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height * 0.92f
            drawLine(lines, Offset(size.width * 0.2f, y), Offset(size.width * 0.8f, y), 4.dp.toPx(), StrokeCap.Round)
        }
    }
}

fun formatDuration(sec: Double): String {
    val s = sec.toLong()
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, ss)
    else String.format(Locale.US, "%d:%02d", m, ss)
}

fun speakersLabel(n: Int): String {
    if (n <= 1) return ""
    val word = when {
        n % 10 == 1 && n % 100 != 11 -> "спикер"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "спикера"
        else -> "спикеров"
    }
    return "$n $word"
}
