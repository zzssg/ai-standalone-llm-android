package org.zzssg.llmchatapp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
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

/*
 * The palette comes from the mascot: a squirrel with a magnifying glass, small
 * but clever, which is the same joke as running a small model on a phone.
 * Everything follows from those two objects -- fur orange for anything the user
 * acts on, lens teal for anything the app has worked out, leaf green for the
 * measurements it reports -- and the neutrals are warm rather than grey, so the
 * app reads as something you talk to and not as infrastructure.
 *
 * Orange this saturated cannot carry white text at body size, so onPrimary is a
 * dark warm ink instead. That is the deliberate trade: the colour stays vivid
 * and the label still clears 5:1, where a white-on-orange button would have sat
 * at 2.8:1 and failed.
 */

/** Squirrel fur. Every primary action in the app is this colour. */
private val FurOrange = Color(0xFFEA6A0A)
private val FurInk = Color(0xFF2B1A10)

/** The lens: what the app has figured out -- reasoning, model info, stats. */
private val LensTeal = Color(0xFF0F7C96)

/** Leaf. Reserved for the positive/measured, so it never competes with the CTA. */
private val LeafGreen = Color(0xFF3F7A22)

private val LightColors = lightColorScheme(
    primary = FurOrange,
    onPrimary = FurInk,
    primaryContainer = Color(0xFFFFE3CC),
    onPrimaryContainer = Color(0xFF5C2A00),

    secondary = LensTeal,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDEEF5),
    onSecondaryContainer = Color(0xFF00323F),

    tertiary = LeafGreen,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD6F2C4),
    onTertiaryContainer = Color(0xFF123D00),

    background = Color(0xFFFFF8F2),
    onBackground = Color(0xFF3A2A1E),
    surface = Color(0xFFFFF8F2),
    onSurface = Color(0xFF3A2A1E),
    surfaceVariant = Color(0xFFFAE7D8),
    onSurfaceVariant = Color(0xFF7A6252),
    // The reply card. Plain white on the cream ground is what separates the
    // two speakers; the previous pairing put a tinted card next to a tinted
    // bubble and both read as the same voice.
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFDF0E6),
    surfaceContainerHigh = Color(0xFFF9E9DC),
    surfaceContainerHighest = Color(0xFFF6E3D2),

    outline = Color(0xFFA07B60),
    outlineVariant = Color(0xFFEBD5C3),

    error = Color(0xFFC1272D),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD7),
    onErrorContainer = Color(0xFF470005),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFA766),
    onPrimary = Color(0xFF4A1F00),
    primaryContainer = Color(0xFF7A3400),
    onPrimaryContainer = Color(0xFFFFE3CC),

    secondary = Color(0xFF5CD3EA),
    onSecondary = Color(0xFF00323F),
    secondaryContainer = Color(0xFF00505F),
    onSecondaryContainer = Color(0xFFCDEEF5),

    tertiary = Color(0xFF9CD97A),
    onTertiary = Color(0xFF16330A),
    tertiaryContainer = Color(0xFF2C5417),
    onTertiaryContainer = Color(0xFFD6F2C4),

    background = Color(0xFF17110D),
    onBackground = Color(0xFFF5EAE1),
    surface = Color(0xFF17110D),
    onSurface = Color(0xFFF5EAE1),
    surfaceVariant = Color(0xFF3A2C23),
    onSurfaceVariant = Color(0xFFD2BCA9),
    surfaceContainerLow = Color(0xFF211913),
    surfaceContainer = Color(0xFF231A14),
    surfaceContainerHigh = Color(0xFF2A201A),
    surfaceContainerHighest = Color(0xFF352921),

    outline = Color(0xFF9C8371),
    outlineVariant = Color(0xFF3A2C23),

    error = Color(0xFFFF9A93),
    onError = Color(0xFF5C0009),
    errorContainer = Color(0xFF8C1A20),
    onErrorContainer = Color(0xFFFFDAD7),
)

/**
 * Rounder than Material's defaults, all the way up the scale.
 *
 * The recommended direction for a mascot-led product is claymorphism: soft,
 * inflated, tactile. Radius is most of that, and it has to be applied as a scale
 * rather than per component or the shapes stop rhyming with each other.
 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        // Chat is a reading surface, so body styles get a taller line height than
        // the Material default.
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),

        // Headings carry the friendliness the recommended typeface would have
        // carried. Without a rounded face to bundle, weight and tighter tracking
        // are what separate a warm heading from a system-default one.
        headlineSmall = base.headlineSmall.copy(
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp,
        ),
        titleLarge = base.titleLarge.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
        ),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Bold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Bold),
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
    /**
     * Off by default, which is the whole point of having a palette.
     *
     * Material You derives colours from the wallpaper, and on most devices that
     * lands somewhere in blue-grey -- so the brand palette below was defined,
     * shipped, and then never seen by anyone on Android 12 or later. A product
     * with a mascot has an identity to keep; the opt-in stays for anyone who
     * prefers their system colours.
     */
    dynamicColor: Boolean = false,
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
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
