package com.gabrydl99.gfc.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.gabrydl99.gfc.R
import com.gabrydl99.gfc.core.Kind

/** Colors of the Windows app: light canvas, white blocks, cobalt accent, solid type tiles. */
@Immutable
data class Palette(
    val canvas: Color,
    val block: Color,
    val sunk: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val cobalt: Color,
    val onCobalt: Color,
    val danger: Color,
    val success: Color,
)

val LightPalette = Palette(
    canvas = Color(0xFFE9EDF3), block = Color.White, sunk = Color(0xFFF3F5F9), ink = Color(0xFF11151C),
    muted = Color(0xFF5F6878), line = Color(0xFFD9DFE8), cobalt = Color(0xFF2346F5), onCobalt = Color.White,
    danger = Color(0xFFD93B3B), success = Color(0xFF0F9D58),
)

val DarkPalette = Palette(
    canvas = Color(0xFF0E1116), block = Color(0xFF171B22), sunk = Color(0xFF1F242D), ink = Color(0xFFE8ECF2),
    muted = Color(0xFF98A2B3), line = Color(0xFF2A313C), cobalt = Color(0xFF5B7BFF), onCobalt = Color.White,
    danger = Color(0xFFFF6B6B), success = Color(0xFF3DDC97),
)

fun kindColor(kind: Kind): Color = when (kind) {
    Kind.VIDEO -> Color(0xFF6D4DF2)
    Kind.IMAGE -> Color(0xFF0B9E8F)
    Kind.PDF -> Color(0xFFE0473E)
    Kind.AUDIO -> Color(0xFFE58A00)
    Kind.OFFICE -> Color(0xFF1F6FEB)
}

val LocalPalette = staticCompositionLocalOf { LightPalette }

val Display = FontFamily(
    Font(R.font.spacegrotesk_medium, FontWeight.Medium),
    Font(R.font.spacegrotesk_bold, FontWeight.Bold),
)
val TextFont = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)

@Composable
fun GfcTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) darkColorScheme(
        primary = p.cobalt, onPrimary = p.onCobalt, background = p.canvas, surface = p.block,
        onSurface = p.ink, onBackground = p.ink, surfaceVariant = p.sunk, onSurfaceVariant = p.muted,
        outline = p.line, outlineVariant = p.line, error = p.danger, surfaceContainerHigh = p.block,
        surfaceContainer = p.block, surfaceContainerLow = p.block, secondaryContainer = p.cobalt.copy(alpha = 0.25f),
        onSecondaryContainer = p.ink,
    ) else lightColorScheme(
        primary = p.cobalt, onPrimary = p.onCobalt, background = p.canvas, surface = p.block,
        onSurface = p.ink, onBackground = p.ink, surfaceVariant = p.sunk, onSurfaceVariant = p.muted,
        outline = p.line, outlineVariant = p.line, error = p.danger, surfaceContainerHigh = p.block,
        surfaceContainer = p.block, surfaceContainerLow = p.block, secondaryContainer = Color(0xFFDDE3FF),
        onSecondaryContainer = p.ink,
    )
    val base = Typography()
    val t = TextStyle(fontFamily = TextFont)
    val typography = Typography(
        displaySmall = base.displaySmall.copy(fontFamily = Display, fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 22.sp),
        titleLarge = base.titleLarge.copy(fontFamily = Display, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.merge(t).copy(fontWeight = FontWeight.Bold, fontSize = 16.sp),
        titleSmall = base.titleSmall.merge(t).copy(fontWeight = FontWeight.Bold),
        bodyLarge = base.bodyLarge.merge(t),
        bodyMedium = base.bodyMedium.merge(t),
        bodySmall = base.bodySmall.merge(t),
        labelLarge = base.labelLarge.merge(t).copy(fontWeight = FontWeight.Bold),
        labelMedium = base.labelMedium.merge(t).copy(fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.merge(t).copy(fontWeight = FontWeight.SemiBold),
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
