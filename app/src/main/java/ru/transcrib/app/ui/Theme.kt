package ru.transcrib.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.transcrib.app.R

/** Brand: graphite + coral. */
object Brand {
    val Graphite900 = Color(0xFF0F1115)
    val Graphite800 = Color(0xFF15181E)
    val Graphite700 = Color(0xFF1D2129)
    val Graphite600 = Color(0xFF272C36)
    val Graphite500 = Color(0xFF3A404C)
    val Coral = Color(0xFFFF6A45)
    val CoralDeep = Color(0xFFE5492C)
    val Orange = Color(0xFFFF9B3D)
    val Amber = Color(0xFFFFC05C)

    val coralGradient = Brush.linearGradient(listOf(Coral, Orange))
    val coralGradientVertical = Brush.verticalGradient(listOf(Orange, Coral))
    val graphiteGradient = Brush.verticalGradient(listOf(Graphite700, Graphite900))
}

/** Onest is a variable font: each weight must set the wght axis explicitly. */
@OptIn(ExperimentalTextApi::class)
private fun onest(weight: FontWeight) = Font(
    resId = R.font.onest,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

private val Onest = FontFamily(
    onest(FontWeight.Normal),
    onest(FontWeight.Medium),
    onest(FontWeight.SemiBold),
    onest(FontWeight.Bold),
    onest(FontWeight.ExtraBold),
)

private fun style(size: Int, line: Int, weight: FontWeight, spacing: Double = 0.0) = TextStyle(
    fontFamily = Onest,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = spacing.sp,
)

private val AppTypography = Typography(
    displayLarge = style(56, 60, FontWeight.Bold, -1.5),
    displayMedium = style(44, 50, FontWeight.Bold, -1.0),
    displaySmall = style(36, 42, FontWeight.Bold, -0.5),
    headlineLarge = style(32, 38, FontWeight.Bold, -0.5),
    headlineMedium = style(26, 32, FontWeight.Bold, -0.3),
    headlineSmall = style(22, 28, FontWeight.Bold, -0.2),
    titleLarge = style(20, 26, FontWeight.SemiBold),
    titleMedium = style(17, 22, FontWeight.SemiBold),
    titleSmall = style(15, 20, FontWeight.SemiBold),
    bodyLarge = style(17, 27, FontWeight.Normal),
    bodyMedium = style(15, 22, FontWeight.Normal),
    bodySmall = style(13, 18, FontWeight.Normal),
    labelLarge = style(15, 20, FontWeight.SemiBold),
    labelMedium = style(13, 16, FontWeight.Medium),
    labelSmall = style(11, 14, FontWeight.Medium, 0.2),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val Light = lightColorScheme(
    primary = Brand.CoralDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3DA),
    onPrimaryContainer = Color(0xFF3A0C00),
    secondary = Brand.Graphite600,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E8EE),
    onSecondaryContainer = Brand.Graphite800,
    tertiary = Color(0xFFB86E00),
    background = Color(0xFFF4F5F7),
    onBackground = Color(0xFF14171C),
    surface = Color(0xFFF4F5F7),
    onSurface = Color(0xFF14171C),
    surfaceVariant = Color(0xFFE8EAEF),
    onSurfaceVariant = Color(0xFF5B616D),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFEEEFF3),
    surfaceContainerHigh = Color(0xFFE8EAEF),
    surfaceContainerHighest = Color(0xFFE1E4EA),
    outline = Color(0xFFC9CDD6),
    outlineVariant = Color(0xFFE1E4EA),
)

private val Dark = darkColorScheme(
    primary = Brand.Coral,
    onPrimary = Color(0xFF2A0800),
    primaryContainer = Color(0xFF5A1D0E),
    onPrimaryContainer = Color(0xFFFFDBD0),
    secondary = Color(0xFFC5CAD4),
    onSecondary = Brand.Graphite800,
    secondaryContainer = Brand.Graphite600,
    onSecondaryContainer = Color(0xFFE3E6EC),
    tertiary = Brand.Amber,
    background = Brand.Graphite900,
    onBackground = Color(0xFFECEEF2),
    surface = Brand.Graphite900,
    onSurface = Color(0xFFECEEF2),
    surfaceVariant = Brand.Graphite600,
    onSurfaceVariant = Color(0xFF9DA4B0),
    surfaceContainerLowest = Color(0xFF0B0D10),
    surfaceContainerLow = Brand.Graphite800,
    surfaceContainer = Brand.Graphite700,
    surfaceContainerHigh = Brand.Graphite600,
    surfaceContainerHighest = Brand.Graphite500,
    outline = Color(0xFF4A505C),
    outlineVariant = Brand.Graphite600,
)

@Immutable
data class ExtraColors(val card: Color, val cardBorder: Color, val dark: Boolean)

val LocalExtraColors = staticCompositionLocalOf { ExtraColors(Color.White, Color(0x14000000), false) }

@Composable
fun TranscribTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val extra = if (dark) {
        ExtraColors(card = Brand.Graphite800, cardBorder = Color(0x14FFFFFF), dark = true)
    } else {
        ExtraColors(card = Color.White, cardBorder = Color(0x0F14171C), dark = false)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalExtraColors provides extra) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            typography = AppTypography,
            shapes = AppShapes,
        ) {
            // Screens draw their own backgrounds, so set the default text color explicitly.
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                content = content,
            )
        }
    }
}

/** Distinct speaker colors that sit well next to coral (no purple). */
private val speakerLight = listOf(
    0xFF2F6FEB, 0xFFE5492C, 0xFF0F9F8F, 0xFFD48806, 0xFFD6336C, 0xFF2B9A48, 0xFF0B8BC4, 0xFF8A5A2B,
)
private val speakerDark = listOf(
    0xFF6FA2FF, 0xFFFF8A6B, 0xFF3FD2BE, 0xFFFFC05C, 0xFFFF7EB0, 0xFF6BD68A, 0xFF5CC8F5, 0xFFD4A373,
)

@Composable
fun speakerColor(speaker: Int): Color {
    val list = if (LocalExtraColors.current.dark) speakerDark else speakerLight
    return Color(list[((speaker % list.size) + list.size) % list.size])
}
