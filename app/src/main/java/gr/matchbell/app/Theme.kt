package gr.matchbell.app

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF0B1020)
val LocalTxt = staticCompositionLocalOf { GreekTxt }

/** One accent family, defined for dark and light backgrounds. */
data class Palette(
    val id: String, val nameEl: String, val nameEn: String,
    val darkPrimary: Color, val darkPrimaryContainer: Color, val darkSecondary: Color, val darkSecondaryContainer: Color,
    val lightPrimary: Color, val lightPrimaryContainer: Color, val lightOnPrimaryContainer: Color,
    val lightSecondary: Color, val lightSecondaryContainer: Color
)

val Palettes: List<Palette> = listOf(
    Palette("mint", "Μέντα", "Mint", Color(0xFF4DE0C4), Color(0xFF173A37), Color(0xFFFFA877), Color(0xFF422D25),
        Color(0xFF007B68), Color(0xFFC3F4E7), Color(0xFF005142), Color(0xFFA84D16), Color(0xFFFFE1CE)),
    Palette("ocean", "Ωκεανός", "Ocean", Color(0xFF6CB8FF), Color(0xFF12304F), Color(0xFFFFD166), Color(0xFF41361A),
        Color(0xFF0B5FA5), Color(0xFFD3E7FF), Color(0xFF053A66), Color(0xFF8A6300), Color(0xFFFFEDB8)),
    Palette("sunset", "Ηλιοβασίλεμα", "Sunset", Color(0xFFFF8A65), Color(0xFF4A2018), Color(0xFFFFC857), Color(0xFF3F3315),
        Color(0xFFC2410C), Color(0xFFFFDBCF), Color(0xFF7A2808), Color(0xFF8A5A00), Color(0xFFFFE7B3)),
    Palette("violet", "Βιολετί", "Violet", Color(0xFFB79CFF), Color(0xFF2E2350), Color(0xFF5EE6D0), Color(0xFF173B37),
        Color(0xFF6440D8), Color(0xFFE6DEFF), Color(0xFF3B1F96), Color(0xFF00796B), Color(0xFFC8F2EA)),
    Palette("pitch", "Γήπεδο", "Pitch", Color(0xFF7BE07B), Color(0xFF1D3A1F), Color(0xFFFFE066), Color(0xFF3C3618),
        Color(0xFF2E7D32), Color(0xFFD5F0D3), Color(0xFF1B5E20), Color(0xFF7A5D00), Color(0xFFFFF0B3)),
    Palette("gold", "Χρυσό", "Gold", Color(0xFFF2C14E), Color(0xFF3D3215), Color(0xFF8EC5FF), Color(0xFF1C2F45),
        Color(0xFF8A6100), Color(0xFFFFE8A8), Color(0xFF5A3F00), Color(0xFF1F5E99), Color(0xFFD6E8FF)),
)
val DynamicSupported: Boolean get() = Build.VERSION.SDK_INT >= 31
fun paletteOf(id: String): Palette = Palettes.firstOrNull { it.id == id } ?: Palettes.first()

private fun darkScheme(p: Palette, amoled: Boolean): ColorScheme {
    // Backgrounds carry a faint tint of the accent so each palette feels distinct.
    val bg = if (amoled) Color.Black else lerp(Ink, p.darkPrimary, 0.035f)
    val surface = if (amoled) Color(0xFF0E0E10) else lerp(Color(0xFF141C30), p.darkPrimary, 0.04f)
    val variant = if (amoled) Color(0xFF1B1B1F) else lerp(Color(0xFF202A40), p.darkPrimary, 0.05f)
    return darkColorScheme(
        primary = p.darkPrimary, onPrimary = Ink, primaryContainer = p.darkPrimaryContainer, onPrimaryContainer = p.darkPrimary,
        secondary = p.darkSecondary, onSecondary = Ink, secondaryContainer = p.darkSecondaryContainer, onSecondaryContainer = p.darkSecondary,
        tertiary = p.darkSecondary, background = bg, onBackground = Color(0xFFF3F6FF), surface = surface,
        onSurface = Color(0xFFF3F6FF), surfaceVariant = variant, onSurfaceVariant = Color(0xFF9BAAC5),
        surfaceContainer = surface, surfaceContainerHigh = variant, surfaceContainerLow = surface,
        outline = if (amoled) Color(0xFF2E2E34) else Color(0xFF344158),
        outlineVariant = if (amoled) Color(0xFF222226) else Color(0xFF263248),
        error = Color(0xFFFFB4AB), errorContainer = Color(0xFF5C1A16), onErrorContainer = Color(0xFFFFDAD6)
    )
}
private fun lightScheme(p: Palette): ColorScheme = lightColorScheme(
    primary = p.lightPrimary, onPrimary = Color.White,
    primaryContainer = p.lightPrimaryContainer, onPrimaryContainer = p.lightOnPrimaryContainer,
    secondary = p.lightSecondary, onSecondary = Color.White, secondaryContainer = p.lightSecondaryContainer,
    tertiary = p.lightSecondary, background = lerp(Color(0xFFF4F7FB), p.lightPrimaryContainer, 0.18f), surface = Color.White,
    surfaceVariant = Color(0xFFE8EEF5), surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFF1F4F9),
    onBackground = Ink, onSurface = Ink, onSurfaceVariant = Color(0xFF53627A),
    outlineVariant = Color(0xFFD9E2EE)
)

/** Every colour animates, so changing theme / palette in Settings morphs the whole UI live. */
@Composable private fun ColorScheme.animated(): ColorScheme {
    val spec = tween<Color>(450)
    @Composable fun a(c: Color): Color { val v by animateColorAsState(c, spec, label = "theme"); return v }
    return copy(
        primary = a(primary), onPrimary = a(onPrimary), primaryContainer = a(primaryContainer),
        onPrimaryContainer = a(onPrimaryContainer), secondary = a(secondary), onSecondary = a(onSecondary),
        secondaryContainer = a(secondaryContainer), onSecondaryContainer = a(onSecondaryContainer),
        tertiary = a(tertiary), background = a(background), onBackground = a(onBackground), surface = a(surface),
        onSurface = a(onSurface), surfaceVariant = a(surfaceVariant), onSurfaceVariant = a(onSurfaceVariant),
        surfaceContainer = a(surfaceContainer), surfaceContainerHigh = a(surfaceContainerHigh),
        surfaceContainerLow = a(surfaceContainerLow), outline = a(outline), outlineVariant = a(outlineVariant)
    )
}

@Composable fun MatchMateTheme(prefs: Preferences, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (prefs.theme) { "light" -> false; "system" -> systemDark; else -> true }
    val amoled = prefs.theme == "amoled"
    val context = LocalContext.current
    // Inline SDK check (not the DynamicSupported getter) so Android Lint can verify the API 31 call.
    val base = if (prefs.palette == "dynamic" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context).let { if (amoled) it.copy(background = Color.Black, surface = Color(0xFF0E0E10)) else it }
        else dynamicLightColorScheme(context)
    } else {
        val p = paletteOf(prefs.palette)
        if (dark) darkScheme(p, amoled) else lightScheme(p)
    }
    val txt = remember(prefs.language) { txtFor(prefs.language) }
    CompositionLocalProvider(LocalTxt provides txt) {
        MaterialTheme(colorScheme = base.animated(), typography = AppTypography, content = content)
    }
}

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp)
)

/** Small accent per sport, for the card's left stripe. */
fun sportAccent(key: String): Color = when (key) {
    "podosfairo", "podosfairo_salas" -> Color(0xFF4CAF50)
    "basket" -> Color(0xFFFF8A3D)
    "tennis" -> Color(0xFFCDDC39)
    "volley" -> Color(0xFFFFC107)
    "handball" -> Color(0xFF29B6F6)
    "polo" -> Color(0xFF26C6DA)
    "f1" -> Color(0xFFE53935)
    "motogp" -> Color(0xFFFF7043)
    "mixanokinitos-athlitismos" -> Color(0xFFEF5350)
    "stivos" -> Color(0xFFAB47BC)
    "polemikes-texnes" -> Color(0xFF8D6E63)
    else -> Color(0xFF90A4AE)
}
