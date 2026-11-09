package io.drivesymphony.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * One palette, dark only: deep navy with a single ice-blue accent (the brand language of the film and the share card).
 * The car's day/night switch is ignored on purpose: a music app that changes colour with the headlights reads as two
 * apps, and every text/background pair here is >= 5.5:1 so the screen stays legible in daylight on the car display.
 * Positive events are mint, dips are amber, the developer REC mark is the only pure red (the demo-video sync detector
 * looks for it).
 */
object DsColors {
    val bg = Color(0xFF060A12)
    val surface = Color(0xFF0D131E)
    val surfaceHi = Color(0xFF172030)
    val line = Color(0xFF243247)
    val text = Color(0xFFEEF2F8)
    val muted = Color(0xFF8E9BB0)
    val accent = Color(0xFF8FB3FF)
    val good = Color(0xFF86DDB8)
    val warn = Color(0xFFFFB38A)
    val rec = Color(0xFFE5484D)
}

private val scheme = darkColorScheme(
    primary = DsColors.accent, onPrimary = DsColors.bg,
    secondary = DsColors.good, onSecondary = DsColors.bg,
    background = DsColors.bg, onBackground = DsColors.text,
    surface = DsColors.surface, onSurface = DsColors.text,
    surfaceVariant = DsColors.surfaceHi, onSurfaceVariant = DsColors.muted,
    outline = DsColors.line, outlineVariant = DsColors.line,
)

/** Car-sized type: nothing below 22 sp on the driving screen (Pleos review: readable at a glance). */
private val carTypography = Typography(
    displayLarge = TextStyle(fontSize = 96.sp, fontWeight = FontWeight.Light, lineHeight = 100.sp),
    headlineLarge = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Medium, lineHeight = 50.sp),
    headlineMedium = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Medium, lineHeight = 42.sp),
    titleLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Medium, lineHeight = 34.sp),
    titleMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium, lineHeight = 30.sp),
    bodyLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp),
    bodyMedium = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
    labelLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun DriveSymphonyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = carTypography, content = content)
}
