package org.zzssg.llmchatapp.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import org.zzssg.llmchatapp.R

/**
 * The app's face: a squirrel with a magnifying glass.
 *
 * The joke is the product. A squirrel is small and clever, which is the same
 * claim the app makes about running a language model on a phone, and the glass
 * is the part that looks closely at things. Every screen the user meets before
 * they have a model shows it, so the first impression is a character rather than
 * a grey chip icon.
 */
@Composable
fun Mascot(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    contentDescription: String? = null,
) {
    Image(
        painter = painterResource(R.drawable.ic_squirrel),
        contentDescription = contentDescription,
        modifier = modifier
            .size(size)
            .then(if (contentDescription == null) Modifier.clearAndSetSemantics { } else Modifier),
    )
}

/**
 * The mascot on a soft halo, for the two places it is the hero of the screen.
 *
 * The halo does the work a drop shadow would: it lifts the character off a flat
 * background without adding an elevation the rest of the design does not use.
 */
@Composable
fun MascotBadge(
    modifier: Modifier = Modifier,
    size: Dp = 132.dp,
    breathing: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    // Only while something is actually happening. A mascot that pulses at rest
    // is decoration competing with the content it sits above.
    val scale = if (breathing && !LocalInspectionMode.current) {
        val transition = rememberInfiniteTransition(label = "mascot")
        val value by transition.animateFloat(
            initialValue = 0.97f,
            targetValue = 1.03f,
            animationSpec = infiniteRepeatable(
                animation = tween(1600),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "breath",
        )
        value
    } else {
        1f
    }

    Box(
        modifier = modifier
            .size(size)
            .background(
                brush = Brush.radialGradient(
                    listOf(
                        scheme.primaryContainer,
                        scheme.primaryContainer.copy(alpha = 0f),
                    )
                ),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Mascot(size = size * 0.72f, modifier = Modifier.scale(scale))
    }
}

/**
 * An icon on its own tinted tile.
 *
 * A bare 20dp glyph in the body text colour is what made every list in the app
 * read as a settings screen. Giving it a container turns it into an object with
 * a colour of its own, which is most of the difference between a utility and a
 * product.
 */
@Composable
fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    size: Dp = 40.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(container, RoundedCornerShape(percent = 30)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = content,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/** The same tile in the colour used for anything the app has worked out. */
@Composable
fun LensTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    size: Dp = 40.dp,
) = IconTile(
    icon = icon,
    modifier = modifier,
    contentDescription = contentDescription,
    container = MaterialTheme.colorScheme.secondaryContainer,
    content = MaterialTheme.colorScheme.onSecondaryContainer,
    size = size,
)

/** And in the colour reserved for measurements and confirmations. */
@Composable
fun LeafTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    size: Dp = 40.dp,
) = IconTile(
    icon = icon,
    modifier = modifier,
    contentDescription = contentDescription,
    container = MaterialTheme.colorScheme.tertiaryContainer,
    content = MaterialTheme.colorScheme.onTertiaryContainer,
    size = size,
)

/** A small mascot tinted to the current content colour, for dense rows. */
@Composable
fun MascotGlyph(modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Image(
        painter = painterResource(R.drawable.ic_notification),
        contentDescription = null,
        colorFilter = ColorFilter.tint(LocalContentColor.current),
        modifier = modifier
            .size(size)
            .clearAndSetSemantics { },
    )
}
