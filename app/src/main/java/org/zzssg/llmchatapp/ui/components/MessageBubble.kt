package org.zzssg.llmchatapp.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.zzssg.llmchatapp.ui.ChatMessage
import org.zzssg.llmchatapp.ui.theme.Spacing

@Composable
fun MessageBubble(
    message: ChatMessage,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isUser = message.isUser

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            // A width fraction rather than the old fixed 300.dp cap, which was
            // nearly full width on a small phone and a narrow column on a tablet.
            modifier = Modifier.fillMaxWidth(if (isUser) 0.85f else 1f),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
            contentColor = if (isUser) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ) {
            Column(Modifier.padding(Spacing.md)) {
                if (message.text.isEmpty() && message.streaming) {
                    TypingIndicator()
                } else {
                    MarkdownText(
                        text = message.text,
                        startsInReasoning = message.startsInReasoning,
                        onCopyCode = onCopy,
                    )
                }
            }
        }

        // The footer only appears once a reply is complete, so it never shifts
        // the layout while text is streaming in.
        if (!isUser && !message.streaming && message.text.isNotEmpty()) {
            // Copies the answer, not the scratchpad: reasoning is shown because
            // it is interesting to watch, but it is not what the model said.
            // A reply stopped mid-thought has no answer yet, so there the button
            // is greyed rather than silently putting an empty string on the
            // clipboard.
            val answer = answerOnly(message.text, message.startsInReasoning)
            MessageFooter(
                message = message,
                canCopy = answer.isNotBlank(),
                onCopy = { onCopy(answer) },
            )
        }
    }
}

@Composable
private fun MessageFooter(message: ChatMessage, canCopy: Boolean, onCopy: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier.padding(top = Spacing.xs),
    ) {
        IconButton(onClick = onCopy, enabled = canCopy, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = "Copy reply",
                modifier = Modifier.size(16.dp),
            )
        }
        message.stats?.let { stats ->
            // Speed belongs in the chrome, not in the message body. The previous
            // build appended "[stats] avg_ms=... tps=..." to the reply text
            // itself, so the user read debug output as part of the answer.
            Text(
                text = buildString {
                    append(
                        "%d tokens · %s · %.1f tok/s".format(
                            stats.tokenCount,
                            stats.formattedDuration,
                            stats.tokensPerSecond,
                        )
                    )
                    // Only present when speculative decoding ran. It is the number
                    // that says whether the extra memory paid for itself.
                    stats.acceptance?.let { append(" · %.0f%% drafted".format(it * 100)) }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Three pulsing dots shown while the first token is still being computed. */
@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")

    Row(
        modifier = modifier
            .padding(vertical = Spacing.xs)
            .semantics { contentDescription = "The model is writing a reply" },
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = index * 160, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Surface(
                modifier = Modifier
                    .size(7.dp)
                    .alpha(alpha)
                    .clearAndSetSemantics { },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {}
        }
    }
}
