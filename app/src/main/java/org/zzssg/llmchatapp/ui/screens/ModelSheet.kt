package org.zzssg.llmchatapp.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import org.zzssg.llmchatapp.data.ModelFile
import org.zzssg.llmchatapp.data.formatBytes
import org.zzssg.llmchatapp.ui.ChatUiState
import org.zzssg.llmchatapp.ui.ModelUiState
import org.zzssg.llmchatapp.ui.theme.Spacing

/**
 * The model library: what is imported, which one is open, and how to change that.
 * The previous build had no equivalent -- a model could only be chosen once, at
 * startup, and there was no way to see or remove what had been imported.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(
    state: ChatUiState,
    onImport: () -> Unit,
    onActivate: (ModelFile) -> Unit,
    onDelete: (ModelFile) -> Unit,
    onOpenGuide: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pendingDeletion by remember { mutableStateOf<ModelFile?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Models",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                // Reachable from here and not only from the first-run screen:
                // the question "which file do I download" comes back every time
                // the user goes looking for a better model.
                IconButton(onClick = onOpenGuide) {
                    Icon(
                        imageVector = Icons.Outlined.HelpOutline,
                        contentDescription = "How to add a model",
                    )
                }
            }
            Text(
                text = "Stored inside this app. Deleting one frees the space immediately.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Spacing.lg))

            when (val modelState = state.modelState) {
                is ModelUiState.Importing -> BusyRow(
                    label = if (modelState.total > 0) {
                        "Copying ${formatBytes(modelState.copied)} of ${formatBytes(modelState.total)}"
                    } else {
                        "Copying ${formatBytes(modelState.copied)}"
                    },
                    fraction = modelState.fraction,
                )

                is ModelUiState.Loading -> BusyRow(
                    label = "Opening ${modelState.name}",
                    fraction = null,
                )

                ModelUiState.Idle -> Unit
            }

            if (state.models.isEmpty() && state.modelState == ModelUiState.Idle) {
                Column(Modifier.padding(vertical = Spacing.lg)) {
                    Text(
                        text = "Nothing imported yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    TextButton(onClick = onOpenGuide, contentPadding = PaddingValues(0.dp)) {
                        Text("How do I get one?")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(state.models, key = { it.id }) { model ->
                        ModelRow(
                            model = model,
                            isActive = state.activeModel?.file == model.file,
                            enabled = !state.isBusy,
                            onActivate = { onActivate(model) },
                            onDelete = { pendingDeletion = model },
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.lg))

            OutlinedButton(
                onClick = onImport,
                enabled = !state.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(Spacing.sm))
                Text("Import a .gguf file")
            }
        }
    }

    pendingDeletion?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text("Delete ${model.displayName}?") },
            text = {
                Text(
                    "This removes ${formatBytes(model.sizeBytes)} from this device. " +
                        "You would have to import the file again to use it."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(model)
                    pendingDeletion = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ModelRow(
    model: ModelFile,
    isActive: Boolean,
    enabled: Boolean,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        },
        contentColor = if (isActive) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled && !isActive, onClick = onActivate),
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.lg, top = Spacing.md, end = Spacing.sm, bottom = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = model.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                )
                Text(
                    text = if (isActive) "${formatBytes(model.sizeBytes)} · open" else formatBytes(model.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (isActive) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = "Currently open",
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(Spacing.sm))
            }

            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete ${model.displayName}")
            }
        }
    }
}

@Composable
private fun BusyRow(label: String, fraction: Float?) {
    Column(Modifier.padding(bottom = Spacing.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(Spacing.md))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(Spacing.sm))
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
