package io.drivesymphony.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.drivesymphony.engine.theory.Genre

/** Dark, high-contrast palette tuned for a 12" car display at arm's length (>= 4.5:1 on text). */
object DsColors {
    val bg = Color(0xFF0B0F14)
    val surface = Color(0xFF151B23)
    val surfaceHi = Color(0xFF1E2731)
    val text = Color(0xFFF2F4F7)
    val muted = Color(0xFFA7B1BE)
    val good = Color(0xFF8AE3B3)
    val warn = Color(0xFFFFB38A)
    val lofi = Color(0xFFE8A860)
    val jazz = Color(0xFF8C9BFF)
    val ambient = Color(0xFF5FD1C2)

    fun accent(genre: Genre): Color = when (genre) { Genre.LOFI -> lofi; Genre.JAZZ -> jazz; Genre.AMBIENT -> ambient }
}

private val scheme = darkColorScheme(
    primary = DsColors.lofi,
    onPrimary = DsColors.bg,
    background = DsColors.bg,
    onBackground = DsColors.text,
    surface = DsColors.surface,
    onSurface = DsColors.text,
    surfaceVariant = DsColors.surfaceHi,
    onSurfaceVariant = DsColors.muted,
    secondary = DsColors.ambient,
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
    MaterialTheme(colorScheme = scheme, typography = carTypography, content = content)
}
