package org.zzssg.llmchatapp.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.zzssg.llmchatapp.data.AppSettings
import org.zzssg.llmchatapp.data.MtpDecision
import org.zzssg.llmchatapp.data.MtpMode
import org.zzssg.llmchatapp.llm.SamplingConfig
import org.zzssg.llmchatapp.llm.ThinkingMode
import org.zzssg.llmchatapp.ui.theme.MinTouchTarget
import org.zzssg.llmchatapp.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * Generation settings.
 *
 * Organised by what the user is trying to change rather than by which layer of
 * the engine the knob belongs to: how the assistant behaves, how it writes, and
 * how it runs. The advanced sampling parameters start collapsed -- they matter to
 * perhaps one user in twenty, and putting five sliders in front of everyone else
 * buries the two settings they actually came for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: AppSettings,
    canToggleThinking: Boolean,
    mtpDecision: MtpDecision?,
    onApply: (AppSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf(settings) }
    var showAdvanced by remember { mutableStateOf(false) }

    // Any change reopens the model, so the notice and the button follow whether
    // anything changed at all rather than trying to guess which knobs are cheap.
    val hasChanges = draft != settings

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)

            // -- Behaviour ---------------------------------------------------
            SettingsGroup(icon = Icons.Outlined.Psychology, title = "Assistant") {
                OutlinedTextField(
                    value = draft.systemPrompt,
                    onValueChange = { draft = draft.copy(systemPrompt = it) },
                    label = { Text("System prompt") },
                    supportingText = { Text("Standing instructions sent before every conversation.") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp),
                    shape = RoundedCornerShape(16.dp),
                    minLines = 3,
                    maxLines = 6,
                )

                if (canToggleThinking) {
                    Spacer(Modifier.height(Spacing.md))
                    SettingLabel(
                        title = "Reasoning",
                        description = "Reasoning models can work through a problem before " +
                            "answering. It costs time and tokens on every reply.",
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    ThinkingSelector(
                        mode = draft.sampling.thinking,
                        onChange = { draft = draft.withSampling { copy(thinking = it) } },
                    )
                }
            }

            // -- Output shape -------------------------------------------------
            SettingsGroup(icon = Icons.Outlined.Tune, title = "Replies") {
                SettingSlider(
                    label = "Creativity",
                    valueLabel = "%.2f".format(draft.sampling.temperature),
                    description = "Lower is focused and repetitive; higher is varied and less " +
                        "predictable. 0 makes replies deterministic.",
                    value = draft.sampling.temperature,
                    range = 0f..1.5f,
                    steps = 29,
                    onChange = { draft = draft.withSampling { copy(temperature = it) } },
                )
                SettingSlider(
                    label = "Length limit",
                    valueLabel = "${draft.sampling.maxTokens} tokens",
                    description = "How much the model may write before it is cut off.",
                    value = draft.sampling.maxTokens.toFloat(),
                    range = 64f..2048f,
                    steps = 30,
                    onChange = { draft = draft.withSampling { copy(maxTokens = it.roundToInt()) } },
                )
                SettingSlider(
                    label = "Repetition penalty",
                    valueLabel = "%.2f".format(draft.sampling.repeatPenalty),
                    description = "Raise this if the model keeps repeating itself.",
                    value = draft.sampling.repeatPenalty,
                    range = 1f..1.5f,
                    steps = 9,
                    onChange = { draft = draft.withSampling { copy(repeatPenalty = it) } },
                )

                Spacer(Modifier.height(Spacing.sm))
                DisclosureHeader(
                    expanded = showAdvanced,
                    label = "Advanced sampling",
                    onToggle = { showAdvanced = !showAdvanced },
                )
                AnimatedVisibility(visible = showAdvanced) {
                    Column {
                        SettingSlider(
                            label = "Top-p",
                            valueLabel = "%.2f".format(draft.sampling.topP),
                            description = "Considers only the most likely tokens whose " +
                                "probabilities add up to this share.",
                            value = draft.sampling.topP,
                            range = 0.1f..1f,
                            steps = 17,
                            onChange = { draft = draft.withSampling { copy(topP = it) } },
                        )
                        SettingSlider(
                            label = "Top-k",
                            valueLabel = if (draft.sampling.topK == 0) "off" else draft.sampling.topK.toString(),
                            description = "Hard cap on how many candidate tokens are considered.",
                            value = draft.sampling.topK.toFloat(),
                            range = 0f..100f,
                            steps = 19,
                            onChange = { draft = draft.withSampling { copy(topK = it.roundToInt()) } },
                        )
                    }
                }
            }

            // -- Runtime -------------------------------------------------------
            SettingsGroup(icon = Icons.Outlined.Memory, title = "Performance") {
                SettingSlider(
                    label = "Context window",
                    valueLabel = "${draft.contextSize} tokens",
                    description = "How much conversation the model can see at once. Larger " +
                        "uses more memory and slows the first reply.",
                    value = draft.contextSize.toFloat(),
                    range = 512f..8192f,
                    steps = 14,
                    onChange = { draft = draft.copy(contextSize = it.roundToInt() / 256 * 256) },
                )
                SettingSlider(
                    label = "Threads",
                    valueLabel = if (draft.threads == 0) "automatic" else draft.threads.toString(),
                    description = "Automatic uses every core in the fastest tier, which is the " +
                        "right answer on nearly all phones.",
                    value = draft.threads.toFloat(),
                    range = 0f..8f,
                    steps = 7,
                    onChange = { draft = draft.copy(threads = it.roundToInt()) },
                )

                Spacer(Modifier.height(Spacing.md))
                SettingLabel(
                    title = "Speculative decoding",
                    description = "A small extra head guesses the next few tokens and the " +
                        "model confirms them in one pass. Faster, but it holds about " +
                        "700 MB more memory.",
                )
                Spacer(Modifier.height(Spacing.sm))
                MtpSelector(
                    mode = draft.mtp,
                    onChange = { draft = draft.copy(mtp = it) },
                )
                mtpDecision?.let { DecisionNote(it) }

                // Only shown once a reload is actually pending, so it reads as a
                // consequence of what was just changed rather than a standing warning.
                AnimatedVisibility(visible = hasChanges) {
                    ReloadNotice()
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                TextButton(
                    onClick = { draft = AppSettings(lastModelId = draft.lastModelId) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = MinTouchTarget),
                ) { Text("Reset") }

                Button(
                    onClick = {
                        onApply(draft)
                        onDismiss()
                    },
                    enabled = hasChanges,
                    modifier = Modifier
                        .weight(2f)
                        .heightIn(min = MinTouchTarget),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(if (hasChanges) "Apply and reload" else "Apply") }
            }
        }
    }
}

/** A titled card. Grouping is what turns eight sliders into three decisions. */
@Composable
private fun SettingsGroup(
    icon: ImageVector,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(Spacing.sm))
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(Spacing.md))
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThinkingSelector(mode: ThinkingMode, onChange: (ThinkingMode) -> Unit) {
    val options = listOf(
        ThinkingMode.ON to "Always",
        ThinkingMode.AUTO to "Model default",
        ThinkingMode.OFF to "Never",
    )

    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {
                    // Default icon slot draws a check; keep it for the selected
                    // item only so the row stays quiet.
                    if (mode == value) SegmentedButtonDefaults.Icon(active = true)
                },
                label = {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
            )
        }
    }
}

/**
 * Auto is the default and the honest one: whether speculation pays off depends
 * on this phone's memory and this model's size, and the app knows both. On and
 * Off are there for anyone who wants to measure the difference themselves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MtpSelector(mode: MtpMode, onChange: (MtpMode) -> Unit) {
    val options = listOf(
        MtpMode.AUTO to "Auto",
        MtpMode.ON to "On",
        MtpMode.OFF to "Off",
    )

    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = { if (mode == value) SegmentedButtonDefaults.Icon(active = true) },
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** What the policy decided and why, so Auto is not a black box. */
@Composable
private fun DecisionNote(decision: MtpDecision) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm),
    ) {
        Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = if (decision.enabled) Icons.Outlined.Bolt else Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(Spacing.sm))
            Text(decision.reason, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DisclosureHeader(expanded: Boolean, label: String, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "disclosure")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = if (expanded) "Hide $label" else "Show $label", onClick = onToggle)
            .heightIn(min = MinTouchTarget)
            .padding(horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(20.dp)
                .rotate(rotation),
        )
    }
}

@Composable
private fun ReloadNotice() {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.md),
    ) {
        Row(
            Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Bolt,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(Spacing.sm))
            Text(
                text = "Applying this reopens the model. It takes a few seconds; your chat is kept.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SettingLabel(title: String, description: String) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingSlider(
    label: String,
    valueLabel: String,
    description: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = Spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            // A pill rather than bare text: the current value is the thing the
            // eye returns to while dragging.
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 3.dp),
                )
            }
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            // Screen readers would otherwise announce a bare number with no unit.
            modifier = Modifier.semantics { contentDescription = "$label, currently $valueLabel" },
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private inline fun AppSettings.withSampling(transform: SamplingConfig.() -> SamplingConfig) =
    copy(sampling = sampling.transform())
