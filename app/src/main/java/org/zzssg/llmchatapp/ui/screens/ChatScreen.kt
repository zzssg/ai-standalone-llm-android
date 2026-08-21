package org.zzssg.llmchatapp.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.zzssg.llmchatapp.llm.ThinkingMode
import org.zzssg.llmchatapp.ui.ChatUiState
import org.zzssg.llmchatapp.ui.LoadReason
import org.zzssg.llmchatapp.ui.ModelUiState
import org.zzssg.llmchatapp.ui.UserFacingError
import org.zzssg.llmchatapp.ui.components.MessageBubble
import org.zzssg.llmchatapp.ui.theme.MinTouchTarget
import org.zzssg.llmchatapp.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenSettings: () -> Unit,
    onThinkingChange: (ThinkingMode) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Survives rotation, unlike the plain `remember` the old screen used.
    var draft by rememberSaveable { mutableStateOf("") }

    val isAtBottom by remember { derivedStateOf { listState.isScrolledToBottom() } }

    // Whether new tokens should pull the view along.
    //
    // Position alone is not enough to decide this: while a reply streams in, the
    // message can be several screens tall, and a reader scrolling back through it
    // is still "inside" the last item. Touching the list hands control to the
    // user; returning to the bottom hands it back.
    var followStream by remember { mutableStateOf(true) }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            // Only user-driven drags count. Programmatic scrolls do not emit
            // these, so following does not switch itself off.
            if (interaction is DragInteraction.Start || interaction is PressInteraction.Press) {
                followStream = false
            }
        }
    }

    LaunchedEffect(isAtBottom) {
        if (isAtBottom) followStream = true
    }

    // A message the user just sent always pulls the view down, whatever they were
    // reading a moment ago.
    LaunchedEffect(state.messages.size) {
        followStream = true
    }

    LaunchedEffect(state.messages.lastOrNull()?.text, state.messages.size, followStream) {
        if (followStream && state.messages.isNotEmpty()) {
            // Instant rather than animated: a new frame arrives every token, and
            // animations would queue up and fight each other.
            listState.scrollToItemEnd(state.messages.lastIndex)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = state.activeModel?.file?.nameWithoutExtension ?: "No model",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        state.activeModel?.let {
                            Text(
                                text = "${it.contextSize} token context",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenMenu) {
                        Icon(Icons.Outlined.Menu, contentDescription = "Chats and settings")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Tune, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                draft = draft,
                onDraftChange = { draft = it },
                state = state,
                onThinkingChange = onThinkingChange,
                onSend = {
                    val text = draft
                    draft = ""
                    onSend(text)
                },
                onStop = onStop,
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (state.messages.isEmpty()) {
                EmptyTranscript(Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = Spacing.sm, bottom = Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageBubble(message = message, onCopy = onCopy)
                    }
                }
            }

            state.error?.let { error ->
                ErrorBanner(
                    error = error,
                    onRetry = onRetry,
                    onDismiss = onDismissError,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(Spacing.lg),
                )
            }

            AnimatedVisibility(
                visible = !isAtBottom && state.messages.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Spacing.lg),
            ) {
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            listState.scrollToItemEnd(state.messages.lastIndex)
                            followStream = true
                        }
                    },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(Icons.Outlined.ArrowDownward, contentDescription = "Scroll to latest")
                }
            }
        }
    }

    // Reopening the model takes seconds and makes the whole screen inoperable, so
    // it gets a scrim rather than a silent freeze. The previous build showed
    // nothing here and the app simply stopped responding to Send.
    AnimatedVisibility(
        visible = state.isReloading,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        (state.modelState as? ModelUiState.Loading)?.let { ReloadOverlay(it) }
    }
}

@Composable
private fun ReloadOverlay(loading: ModelUiState.Loading) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
            // Swallow taps so nothing underneath reacts while the engine is down.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 6.dp,
            modifier = Modifier.padding(Spacing.xl),
        ) {
            Column(
                modifier = Modifier.padding(Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 3.dp)
                Spacer(Modifier.height(Spacing.lg))
                Text(
                    text = when (loading.reason) {
                        LoadReason.SETTINGS_CHANGED -> "Applying settings"
                        LoadReason.OPENING -> "Opening model"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = when (loading.reason) {
                        // Say why the wait is happening. A reload the user did not
                        // ask for otherwise reads as the app hanging.
                        LoadReason.SETTINGS_CHANGED ->
                            "Context size and thread count are fixed when the model " +
                                "opens, so ${loading.name} is being reloaded. Your chat is kept."

                        LoadReason.OPENING ->
                            "Reading ${loading.name} into memory. The first load is the slow one."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 260.dp),
                )
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    state: ChatUiState,
    onThinkingChange: (ThinkingMode) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        ) {
            // Shown for every model, disabled for the ones that cannot reason.
            // Hiding it made the control appear and disappear as models were
            // switched, which reads as a bug; a greyed chip that says why is
            // both steadier and more informative.
            ThinkingChip(
                mode = state.settings.sampling.thinking,
                supported = state.canToggleThinking,
                enabled = state.canToggleThinking && !state.isGenerating,
                onChange = onThinkingChange,
            )
            Spacer(Modifier.height(Spacing.sm))

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .weight(1f)
                        // Grows with the message but stops before it swallows the
                        // transcript.
                        .heightIn(min = MinTouchTarget, max = 140.dp),
                    placeholder = { Text("Message") },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    enabled = state.isReady && !state.isReloading,
                )

                if (state.isGenerating) {
                    FilledIconButton(
                        onClick = onStop,
                        modifier = Modifier.size(MinTouchTarget),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Icon(Icons.Outlined.Stop, contentDescription = "Stop generating")
                    }
                } else {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = state.isReady && !state.isReloading && draft.isNotBlank(),
                        modifier = Modifier.size(MinTouchTarget),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

/**
 * Thinking mode, one tap from the composer.
 *
 * It lives here rather than only in settings because it is a per-question choice:
 * reasoning is worth the wait on a hard problem and pure overhead on "what time
 * is it in Tokyo". The cost is stated next to it, since on a phone the difference
 * is tens of seconds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThinkingChip(
    mode: ThinkingMode,
    supported: Boolean,
    enabled: Boolean,
    onChange: (ThinkingMode) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A model with no <think> block in its template will not reason whatever
        // the setting says, so the chip reads as off rather than showing a state
        // the reply will not honour.
        val thinkingOn = supported && mode != ThinkingMode.OFF

        FilterChip(
            selected = thinkingOn,
            onClick = { onChange(if (thinkingOn) ThinkingMode.OFF else ThinkingMode.ON) },
            enabled = enabled,
            label = { Text(if (thinkingOn) "Thinking on" else "Thinking off") },
            leadingIcon = {
                Icon(
                    imageVector = if (thinkingOn) Icons.Outlined.Psychology else Icons.Outlined.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            },
        )

        Text(
            text = when {
                !supported -> "This model does not reason"
                thinkingOn -> "Reasons first, slower"
                else -> "Answers directly, faster"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyTranscript(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.Chat,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.md))
        Text("Ask anything", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "Replies are generated on this device, so the first tokens take " +
                "a few seconds to appear.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp),
        )
    }
}

/**
 * Errors are shown inline and stay until dismissed. Toasts, which the old build
 * used, vanish after seconds and cannot carry a retry action.
 */
@Composable
private fun ErrorBanner(
    error: UserFacingError,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        tonalElevation = 6.dp,
    ) {
        Column(
            Modifier.padding(
                start = Spacing.lg,
                top = Spacing.md,
                end = Spacing.sm,
                bottom = Spacing.sm,
            )
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(error.title, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(Spacing.xs))
                    Text(error.detail, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Dismiss",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (error.retryable) {
                TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) {
                    Text("Try again")
                }
            }
        }
    }
}
