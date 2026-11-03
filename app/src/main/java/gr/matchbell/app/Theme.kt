package gr.matchbell.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Mint = Color(0xFF4DE0C4)
val Ink = Color(0xFF0B1020)
val Coral = Color(0xFFFFA877)
private val NightColors = darkColorScheme(
    primary = Mint, onPrimary = Ink, primaryContainer = Color(0xFF173A37), onPrimaryContainer = Mint,
    secondary = Coral, onSecondary = Ink, secondaryContainer = Color(0xFF422D25),
    background = Ink, onBackground = Color(0xFFF3F6FF), surface = Color(0xFF141C30),
    onSurface = Color(0xFFF3F6FF), surfaceVariant = Color(0xFF202A40), onSurfaceVariant = Color(0xFF9BAAC5),
    outline = Color(0xFF344158), outlineVariant = Color(0xFF263248),
    error = Color(0xFFFFB4AB)
)
private val DayColors = lightColorScheme(
    primary = Color(0xFF007B68), onPrimary = Color.White,
    primaryContainer = Color(0xFFC3F4E7), onPrimaryContainer = Color(0xFF005142),
    secondary = Color(0xFFA84D16), secondaryContainer = Color(0xFFFFE1CE),
    background = Color(0xFFF4F7FB), surface = Color.White, surfaceVariant = Color(0xFFE8EEF5),
    onBackground = Ink, onSurface = Ink, onSurfaceVariant = Color(0xFF53627A),
    outlineVariant = Color(0xFFD9E2EE)
)
@Composable fun MatchMateTheme(mode: String, content: @Composable () -> Unit) {
    val dark = mode == "dark" || (mode == "system" && isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (dark) NightColors else DayColors,
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp),
            headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
            titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp)
        ), content = content)
}
