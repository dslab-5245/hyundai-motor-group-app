package io.drivesymphony.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.drivesymphony.engine.theory.Genre

/** One palette: every colour the screens use, >= 4.5:1 for text on its own background. */
class DsPalette(
    val bg: Color, val surface: Color, val surfaceHi: Color,
    val text: Color, val muted: Color,
    val good: Color, val warn: Color,
    val lofi: Color, val jazz: Color, val ambient: Color,
)

/**
 * Night (default, Pleos UI guide: dark first) and Day palettes. The car switches the system night mode with
 * headlights / time; [DriveSymphonyTheme] follows it and re-keys the whole tree, so screens can keep reading
 * [DsColors.x] as plain values. The share card always renders the night palette (it is a brand image).
 */
object DsColors {
    val night = DsPalette(
        bg = Color(0xFF0B0F14), surface = Color(0xFF151B23), surfaceHi = Color(0xFF1E2731),
        text = Color(0xFFF2F4F7), muted = Color(0xFFA7B1BE),
        good = Color(0xFF8AE3B3), warn = Color(0xFFFFB38A),
        lofi = Color(0xFFE8A860), jazz = Color(0xFF8C9BFF), ambient = Color(0xFF5FD1C2),
    )
    val day = DsPalette(
        bg = Color(0xFFF4F6F8), surface = Color(0xFFFFFFFF), surfaceHi = Color(0xFFE4EAF0),
        text = Color(0xFF0E1420), muted = Color(0xFF4A5666),
        good = Color(0xFF1B7047), warn = Color(0xFFA84A08),
        lofi = Color(0xFF8F520C), jazz = Color(0xFF3442B8), ambient = Color(0xFF0D7368),
    )

    @Volatile var palette: DsPalette = night

    val bg: Color get() = palette.bg
    val surface: Color get() = palette.surface
    val surfaceHi: Color get() = palette.surfaceHi
    val text: Color get() = palette.text
    val muted: Color get() = palette.muted
    val good: Color get() = palette.good
    val warn: Color get() = palette.warn
    val lofi: Color get() = palette.lofi
    val jazz: Color get() = palette.jazz
    val ambient: Color get() = palette.ambient

    fun accent(genre: Genre): Color = when (genre) { Genre.LOFI -> lofi; Genre.JAZZ -> jazz; Genre.AMBIENT -> ambient }
}

private fun schemeFor(p: DsPalette, dark: Boolean) = if (dark) darkColorScheme(
    primary = p.lofi, onPrimary = p.bg, background = p.bg, onBackground = p.text,
    surface = p.surface, onSurface = p.text, surfaceVariant = p.surfaceHi, onSurfaceVariant = p.muted, secondary = p.ambient,
) else lightColorScheme(
    primary = p.lofi, onPrimary = p.surface, background = p.bg, onBackground = p.text,
    surface = p.surface, onSurface = p.text, surfaceVariant = p.surfaceHi, onSurfaceVariant = p.muted, secondary = p.ambient,
)

/** Car-sized type: nothing below 24 sp on the driving screen (Pleos review: readable at a glance). */
private val carTypography = Typography(
    displayLarge = TextStyle(fontSize = 96.sp, fontWeight = FontWeight.Light, lineHeight = 100.sp),
    headlineLarge = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.SemiBold, lineHeight = 50.sp),
    headlineMedium = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold, lineHeight = 40.sp),
    titleLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Medium, lineHeight = 34.sp),
    titleMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium, lineHeight = 30.sp),
    bodyLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp),
    bodyMedium = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
    labelLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun DriveSymphonyTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) DsColors.night else DsColors.day
    DsColors.palette = palette
    // re-key the tree on a day/night change so every DsColors read is refreshed (it happens a few times a day, not per frame)
    key(dark) {
        MaterialTheme(colorScheme = schemeFor(palette, dark), typography = carTypography, content = content)
    }
}
