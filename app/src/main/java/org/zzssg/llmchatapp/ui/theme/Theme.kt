package org.zzssg.llmchatapp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// A warm honey-and-rose palette on ivory neutrals.
//
// Chosen against the grain of the category: on-device AI tooling defaults to cool
// slate-and-teal, which reads as infrastructure. This app is something you talk
// to, so the neutrals carry a warm bias rather than being pure grey, and the
// accent is a honey amber with a rose secondary for the quieter surfaces.
//
// Both themes are defined together and share the same role structure, so a
// component styled through the tokens is correct in either.
private val LightColors = lightColorScheme(
    primary = Color(0xFF8F5A00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDDB0),
    onPrimaryContainer = Color(0xFF2D1600),

    secondary = Color(0xFF8C4A5F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD9E2),
    onSecondaryContainer = Color(0xFF3A0A1C),

    tertiary = Color(0xFF4F6354),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD1E8D5),
    onTertiaryContainer = Color(0xFF0C1F13),

    background = Color(0xFFFFF8F3),
    onBackground = Color(0xFF211A14),
    surface = Color(0xFFFFF8F3),
    onSurface = Color(0xFF211A14),
    surfaceVariant = Color(0xFFF2E0D0),
    onSurfaceVariant = Color(0xFF51443A),
    surfaceContainerHighest = Color(0xFFF6E7DA),

    outline = Color(0xFF84766A),
    outlineVariant = Color(0xFFD6C3B4),

    error = Color(0xFFA4302A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD5),
    onErrorContainer = Color(0xFF410100),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB95C),
    onPrimary = Color(0xFF4A2800),
    primaryContainer = Color(0xFF6B3D00),
    onPrimaryContainer = Color(0xFFFFDDB0),

    secondary = Color(0xFFFFB1C5),
    onSecondary = Color(0xFF54233A),
    secondaryContainer = Color(0xFF6F3950),
    onSecondaryContainer = Color(0xFFFFD9E2),

    tertiary = Color(0xFFB5CCBA),
    onTertiary = Color(0xFF213528),
    tertiaryContainer = Color(0xFF374B3D),
    onTertiaryContainer = Color(0xFFD1E8D5),

    background = Color(0xFF19120C),
    onBackground = Color(0xFFEDE0D6),
    surface = Color(0xFF19120C),
    onSurface = Color(0xFFEDE0D6),
    surfaceVariant = Color(0xFF51443A),
    onSurfaceVariant = Color(0xFFD6C3B4),
    surfaceContainerHighest = Color(0xFF2A211A),

    outline = Color(0xFF9E8E81),
    outlineVariant = Color(0xFF51443A),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        // Chat is a reading surface, so body styles get a taller line height than
        // the Material default.
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

/** Monospace style shared by inline code and fenced blocks. */
val CodeTextStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 19.sp,
    fontWeight = FontWeight.Normal,
)

/**
 * A 4dp-based spacing scale.
 *
 * Section spacing uses the larger steps so hierarchy comes from rhythm rather
 * than from arbitrary per-component padding.
 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Minimum comfortable touch target on Android. */
val MinTouchTarget = 48.dp

@Composable
fun LlmChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
