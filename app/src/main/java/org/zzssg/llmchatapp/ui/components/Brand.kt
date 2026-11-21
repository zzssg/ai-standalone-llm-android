package org.zzssg.llmchatapp.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
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

/** Below this, [Mascot] draws the head crop rather than the whole figure. */
private val FIGURE_MIN_SIZE = 40.dp

/**
 * The app's face: a squirrel with a magnifying glass.
 *
 * The joke is the product. A squirrel is small and clever, which is the same
 * claim the app makes about running a language model on a phone, and the glass
 * is the part that looks closely at things. Every screen the user meets before
 * they have a model shows it, so the first impression is a character rather than
 * a grey chip icon.
 *
 * It is drawn from two crops of the same geometry, picked by the size asked for.
 * The whole animal is worth having where it is the hero of the screen, and below
 * about forty density pixels -- the avatar beside every assistant message -- the
 * body and the tail are two smudges under the head, so the head is all that gets
 * drawn there.
 */
@Composable
fun Mascot(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    contentDescription: String? = null,
) {
    Image(
        painter = painterResource(
            if (size < FIGURE_MIN_SIZE) R.drawable.ic_squirrel_head else R.drawable.ic_squirrel
        ),
        contentDescription = contentDescription,
        modifier = modifier
            .size(size)
            .then(if (contentDescription == null) Modifier.clearAndSetSemantics { } else Modifier),
    )
}

/**
 * The mascot in a framed tile, for the two places it is the hero of the screen.
 *
 * The framed tile makes the character feel like an app mark rather than a
 * sticker. It uses the same warm hierarchy as the launcher icon without
 * introducing elevation or a fake drop shadow.
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
            .background(scheme.primaryContainer, RoundedCornerShape(percent = 36))
            .border(
                width = 1.dp,
                color = scheme.outlineVariant,
                shape = RoundedCornerShape(percent = 36),
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
