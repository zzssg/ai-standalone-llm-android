package org.zzssg.llmchatapp.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import org.zzssg.llmchatapp.data.ModelSizeAdvice
import org.zzssg.llmchatapp.data.formatBytes
import org.zzssg.llmchatapp.ui.theme.Spacing

/** Where GGUF files actually live. Shown as text as well as opened, so it is readable. */
private const val CATALOGUE_URL = "https://huggingface.co/models?library=gguf&sort=downloads"
private const val CATALOGUE_LABEL = "huggingface.co"

/**
 * How to get a model onto the phone, for someone who has never done it.
 *
 * The app asks for something unusual before it does anything at all: a
 * multi-gigabyte file, in one specific format, chosen from thousands of
 * near-identically named options. Nothing in the interface explained any of
 * that -- the file picker was the whole of the instructions -- so this is the
 * missing half of the first-run experience.
 *
 * Written for someone who does not know what a quantisation is and should not
 * have to. Every number here is one the reader can act on, and the size advice
 * is computed from [totalRamBytes] rather than being the same sentence on a
 * 4 GB phone and a 16 GB one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelGuideSheet(totalRamBytes: Long, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    val openCatalogue = {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, CATALOGUE_URL.toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            // No browser installed. The address is printed below the button for
            // exactly this case, so there is nothing to report.
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Column {
                Text("Adding a model", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "This app has no model built in. You download one once, " +
                        "and then it works offline forever.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            GuideStep(
                number = 1,
                icon = Icons.Rounded.Description,
                title = "One file, ending in .gguf",
                body = "GGUF is the only format this app reads. A model published as " +
                    "several .safetensors or .bin files, or as a folder, will not work — " +
                    "search for a GGUF version of the same model instead. Most popular " +
                    "models have one.",
            )

            GuideStep(
                number = 2,
                icon = Icons.Rounded.Download,
                title = "Download it in your browser",
                body = "Hugging Face is where these are published. Search for a model " +
                    "name followed by GGUF, open the Files tab, and download one file.",
            ) {
                Spacer(Modifier.height(Spacing.sm))
                OutlinedButton(onClick = openCatalogue) {
                    Icon(Icons.Rounded.OpenInNew, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.size(Spacing.sm))
                    Text("Browse GGUF models")
                }
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "Opens $CATALOGUE_LABEL in your browser. The app itself has no " +
                        "internet access at all — the download happens outside it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            GuideStep(
                number = 3,
                icon = Icons.Rounded.Straighten,
                title = "Pick the right size",
                body = "File names carry two numbers. The B number is how big the model is " +
                    "— bigger answers better and runs slower. The Q number is how hard the " +
                    "file was squeezed to fit; Q4_K_M is the usual choice and the one to " +
                    "take unless you have a reason not to.",
            ) {
                Spacer(Modifier.height(Spacing.sm))
                DeviceAdvice(totalRamBytes)
            }

            GuideStep(
                number = 4,
                icon = Icons.Rounded.Widgets,
                title = "Choose one built for chatting",
                body = "Look for Instruct, Chat or IT in the name. Models without it are " +
                    "trained to continue text rather than answer questions, and will " +
                    "ramble instead of replying.",
            )

            GuideStep(
                number = 5,
                icon = Icons.Rounded.Download,
                title = "Import it here",
                body = "Tap Import and pick the downloaded file. The app copies it into its " +
                    "own storage, so you will briefly need room for two copies, and can " +
                    "delete the download afterwards.",
            )

            TroubleshootingCard()
        }
    }
}

/** The size advice, in this phone's numbers rather than in general terms. */
@Composable
private fun DeviceAdvice(totalRamBytes: Long) {
    val maxBytes = ModelSizeAdvice.recommendedMaxBytes(totalRamBytes)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.md)) {
            if (totalRamBytes <= 0) {
                Text("On most phones", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "A 3B model at Q4_K_M, around 2 GB, is a safe first choice.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }

            Text("On this phone", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = if (maxBytes <= 0) {
                    "With ${formatBytes(totalRamBytes)} of memory there is not enough room " +
                        "left over to run a model comfortably. Anything you try is likely " +
                        "to be closed by the system part way through an answer."
                } else {
                    "${formatBytes(totalRamBytes)} of memory, so stay under about " +
                        "${formatBytes(maxBytes)} per file. That is roughly a " +
                        "${ModelSizeAdvice.recommendedParameterRange(totalRamBytes)} model " +
                        "at Q4_K_M."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The three failures a first-time import actually hits. */
@Composable
private fun TroubleshootingCard() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row {
                Icon(
                    imageVector = Icons.Rounded.Error,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(Spacing.sm))
                Text("If it will not open", style = MaterialTheme.typography.titleSmall)
            }
            Problem(
                symptom = "Not a readable GGUF file",
                fix = "The download did not finish, or the file is a different format " +
                    "renamed. Download it again and check the size matches the site.",
            )
            Problem(
                symptom = "Architecture not supported",
                fix = "The model is newer than this build of the app. Pick a different " +
                    "model, or wait for an update.",
            )
            Problem(
                symptom = "The app closes while answering",
                fix = "The model is too large for this phone. Take the same model at a " +
                    "smaller Q number, or fewer parameters.",
            )
        }
    }
}

@Composable
private fun Problem(symptom: String, fix: String) {
    Column {
        Text(
            text = symptom,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = fix,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A numbered step.
 *
 * Numbered because these genuinely are a sequence -- the file has to be found
 * before it can be sized, and downloaded before it can be imported -- rather
 * than as decoration on an unordered list.
 */
@Composable
private fun GuideStep(
    number: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    extra: @Composable (() -> Unit)? = null,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Surface(
            shape = RoundedCornerShape(percent = 50),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(24.dp),
        ) {
            Column(
                Modifier.heightIn(min = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) {
                Text("$number", style = MaterialTheme.typography.labelMedium)
            }
        }

        Column(Modifier.fillMaxWidth()) {
            Row {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(Spacing.sm))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            extra?.invoke()
        }
    }
}
