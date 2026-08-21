package org.zzssg.llmchatapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.zzssg.llmchatapp.data.ModelSizeAdvice
import org.zzssg.llmchatapp.data.formatBytes
import org.zzssg.llmchatapp.ui.ModelUiState
import org.zzssg.llmchatapp.ui.theme.Spacing

/**
 * First-run screen.
 *
 * The old version offered "Select GGUF Model File" and "Use Mock Mode (No LLM)"
 * as two equal buttons with no explanation of either. Mock mode is a developer
 * affordance and is gone; what replaces it is an explanation of what the app
 * needs and why, so the file picker is not the first thing a new user meets.
 */
@Composable
fun WelcomeScreen(
    modelState: ModelUiState,
    nativeAvailable: Boolean,
    hasImportedModels: Boolean,
    totalRamBytes: Long,
    onImport: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Memory,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(Spacing.lg))

        Text(
            text = "Chat with a model on your phone",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(Spacing.sm))

        Text(
            text = "Everything runs on this device. Nothing you type is sent anywhere.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 380.dp),
        )

        Spacer(Modifier.height(Spacing.xl))

        if (!nativeAvailable) {
            UnsupportedDeviceNotice()
            return@Column
        }

        when (modelState) {
            is ModelUiState.Importing -> ImportingCard(modelState)
            is ModelUiState.Loading -> LoadingCard(modelState.name)
            ModelUiState.Idle -> {
                Button(
                    onClick = onImport,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 380.dp)
                        .height(52.dp),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, Modifier.size(20.dp))
                    Spacer(Modifier.size(Spacing.sm))
                    Text("Add a model")
                }

                // Reached when a previously imported model failed to reopen, or
                // after the active one was deleted. Without this the only way
                // back to an existing model would be to import it again.
                if (hasImportedModels) {
                    Spacer(Modifier.height(Spacing.sm))
                    TextButton(
                        onClick = onOpenModels,
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 380.dp),
                    ) {
                        Text("Choose from imported models")
                    }
                }

                Spacer(Modifier.height(Spacing.xl))
                GuidanceCard(totalRamBytes, onOpenGuide)
            }
        }
    }
}

@Composable
private fun GuidanceCard(totalRamBytes: Long, onOpenGuide: () -> Unit) {
    val maxBytes = ModelSizeAdvice.recommendedMaxBytes(totalRamBytes)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.widthIn(max = 380.dp),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = "What you need",
                style = MaterialTheme.typography.titleSmall,
            )
            GuidanceRow(
                icon = Icons.Outlined.Memory,
                title = "A .gguf model file",
                body = "Download one in your browser, then pick it here. Names containing " +
                    "Instruct or Chat are the ones that answer questions.",
            )
            GuidanceRow(
                icon = Icons.Outlined.Speed,
                // The number, not the platitude: the old text said 1-3 B suited
                // most phones, which undersold a 12 GB device and oversold a
                // 4 GB one. The phone knows its own memory.
                title = if (maxBytes > 0) "Up to about ${formatBytes(maxBytes)}" else "Start small",
                body = if (maxBytes > 0) {
                    "That is what fits on this phone with room for the conversation. " +
                        "Roughly a ${ModelSizeAdvice.recommendedParameterRange(totalRamBytes)} " +
                        "model at Q4_K_M."
                } else {
                    "A 3B model at Q4_K_M, around 2 GB, is a safe first choice."
                },
            )
            GuidanceRow(
                icon = Icons.Outlined.CloudOff,
                title = "Works offline",
                body = "Once a model is imported there is no network traffic at all.",
            )

            TextButton(
                onClick = onOpenGuide,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("How to find and download one")
            }
        }
    }
}

@Composable
private fun GuidanceRow(icon: ImageVector, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ImportingCard(state: ModelUiState.Importing) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.widthIn(max = 380.dp),
    ) {
        Text("Copying the model to this app", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(Spacing.md))

        // A determinate bar whenever the picker told us the file size. Copying a
        // multi-gigabyte model takes minutes, and the previous build showed
        // nothing at all while it happened.
        val fraction = state.fraction
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = if (state.total > 0) {
                "${formatBytes(state.copied)} of ${formatBytes(state.total)}"
            } else {
                formatBytes(state.copied)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoadingCard(name: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(Spacing.md))
        Text("Opening $name", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "The first load reads the whole file, so it can take a moment.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun UnsupportedDeviceNotice() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.widthIn(max = 380.dp),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Text("This device is not supported", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "The inference library could not be loaded. This build targets " +
                    "64-bit ARM devices (arm64-v8a).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
