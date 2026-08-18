package org.zzssg.llmchatapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.zzssg.llmchatapp.data.AppSettings
import org.zzssg.llmchatapp.llm.SamplingConfig
import org.zzssg.llmchatapp.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * Generation settings.
 *
 * The old UI put a single-line "system prompt" field permanently at the top of
 * the chat, where it ate vertical space and invited accidental edits mid-thread,
 * and offered no way at all to reach the sampling parameters that were already
 * plumbed through JNI. Both now live here, behind one tap, with plain-language
 * descriptions instead of bare parameter names.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: AppSettings,
    onApply: (AppSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf(settings) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = draft.systemPrompt,
                onValueChange = { draft = draft.copy(systemPrompt = it) },
                label = { Text("System prompt") },
                supportingText = { Text("Standing instructions sent before every conversation.") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp),
                minLines = 3,
                maxLines = 6,
            )

            HorizontalDivider()

            SettingSlider(
                label = "Creativity",
                valueLabel = "%.2f".format(draft.sampling.temperature),
                description = "Lower is focused and repetitive; higher is varied and " +
                    "less predictable. 0 makes replies deterministic.",
                value = draft.sampling.temperature,
                range = 0f..1.5f,
                steps = 29,
                onChange = { draft = draft.withSampling { copy(temperature = it) } },
            )

            SettingSlider(
                label = "Reply length limit",
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

            SettingSlider(
                label = "Top-p",
                valueLabel = "%.2f".format(draft.sampling.topP),
                description = "Considers only the most likely tokens whose probabilities " +
                    "add up to this share.",
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

            HorizontalDivider()

            Text("Performance", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Changing these reopens the model, which clears the current chat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SettingSlider(
                label = "Context window",
                valueLabel = "${draft.contextSize} tokens",
                description = "How much conversation the model can see at once. " +
                    "Larger uses more memory and slows the first reply.",
                value = draft.contextSize.toFloat(),
                range = 512f..8192f,
                steps = 14,
                onChange = { draft = draft.copy(contextSize = it.roundToInt() / 256 * 256) },
            )

            SettingSlider(
                label = "Threads",
                valueLabel = if (draft.threads == 0) "automatic" else draft.threads.toString(),
                description = "0 lets the app pick based on the number of fast cores.",
                value = draft.threads.toFloat(),
                range = 0f..8f,
                steps = 7,
                onChange = { draft = draft.copy(threads = it.roundToInt()) },
            )

            Spacer(Modifier.height(Spacing.sm))

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                TextButton(
                    onClick = { draft = AppSettings(lastModelId = draft.lastModelId) },
                    modifier = Modifier.weight(1f),
                ) { Text("Reset") }

                Button(
                    onClick = {
                        onApply(draft)
                        onDismiss()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) { Text("Apply") }
            }
        }
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
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            // Screen readers otherwise announce a bare number with no unit.
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
