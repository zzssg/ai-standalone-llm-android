package org.zzssg.llmchatapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.zzssg.llmchatapp.ui.ChatViewModel
import org.zzssg.llmchatapp.ui.screens.ChatScreen
import org.zzssg.llmchatapp.ui.screens.ModelSheet
import org.zzssg.llmchatapp.ui.screens.SettingsSheet
import org.zzssg.llmchatapp.ui.screens.WelcomeScreen
import org.zzssg.llmchatapp.ui.theme.LlmChatTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            LlmChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    App()
                }
            }
        }
    }
}

@Composable
private fun App(viewModel: ChatViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showModels by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        // OpenDocument rather than GetContent: it returns a stable document URI
        // and lets us filter by MIME type. GGUF has no registered type, so the
        // filter stays broad and ModelStore checks the extension.
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importModel) }

    val openPicker = remember(picker) { { picker.launch(arrayOf("*/*")) } }

    val copyToClipboard: (String) -> Unit = remember(context) {
        { text ->
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Reply", text))
            // Android 13+ shows its own copy confirmation, so a toast would double up.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (state.isReady) {
        ChatScreen(
            state = state,
            onSend = viewModel::send,
            onStop = viewModel::stopGeneration,
            onRetry = viewModel::retryLast,
            onDismissError = viewModel::dismissError,
            onNewChat = viewModel::newConversation,
            onOpenModels = { showModels = true },
            onOpenSettings = { showSettings = true },
            onCopy = copyToClipboard,
        )
    } else {
        WelcomeScreen(
            modelState = state.modelState,
            nativeAvailable = state.nativeAvailable,
            hasImportedModels = state.models.isNotEmpty(),
            onImport = openPicker,
            onOpenModels = { showModels = true },
        )
    }

    if (showModels) {
        ModelSheet(
            state = state,
            onImport = openPicker,
            onActivate = viewModel::activate,
            onDelete = viewModel::deleteModel,
            onDismiss = { showModels = false },
        )
    }

    if (showSettings) {
        SettingsSheet(
            settings = state.settings,
            onApply = viewModel::updateSettings,
            onDismiss = { showSettings = false },
        )
    }
}
