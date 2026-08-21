package org.zzssg.llmchatapp

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.zzssg.llmchatapp.ui.ChatViewModel
import org.zzssg.llmchatapp.ui.screens.ChatDrawer
import org.zzssg.llmchatapp.ui.screens.ChatScreen
import org.zzssg.llmchatapp.ui.screens.ModelGuideSheet
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
    val scope = rememberCoroutineScope()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showModelGuide by rememberSaveable { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        // OpenDocument rather than GetContent: it returns a stable document URI
        // and lets us filter by MIME type. GGUF has no registered type, so the
        // filter stays broad and ModelStore checks the extension.
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importModel) }

    // Asked for at the first reply rather than at launch: that is the moment the
    // notification is about to mean something, and generation is not blocked on
    // the answer -- the foreground service runs either way, the permission only
    // decides whether its ongoing notice, and its Stop button, are visible.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    var askedForNotifications by rememberSaveable { mutableStateOf(false) }

    val send: (String) -> Unit = { text ->
        if (!askedForNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            askedForNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.send(text)
    }

    val openPicker = remember(picker) { { picker.launch(arrayOf("*/*")) } }
    val closeDrawer = remember(drawerState, scope) { { scope.launch { drawerState.close() } } }

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
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ChatDrawer(
                    chats = state.chats,
                    activeChatId = state.activeChatId,
                    onNewChat = {
                        viewModel.startNewChat()
                        closeDrawer()
                    },
                    onOpenChat = {
                        viewModel.openChat(it)
                        closeDrawer()
                    },
                    onDeleteChat = viewModel::deleteChat,
                    onOpenModels = {
                        closeDrawer()
                        showModels = true
                    },
                    onOpenSettings = {
                        closeDrawer()
                        showSettings = true
                    },
                )
            },
        ) {
            ChatScreen(
                state = state,
                onSend = send,
                onStop = viewModel::stopGeneration,
                onRetry = viewModel::retryLast,
                onDismissError = viewModel::dismissError,
                onOpenMenu = { scope.launch { drawerState.open() } },
                onOpenSettings = { showSettings = true },
                onThinkingChange = viewModel::setThinkingMode,
                onCopy = copyToClipboard,
            )
        }
    } else {
        WelcomeScreen(
            modelState = state.modelState,
            nativeAvailable = state.nativeAvailable,
            hasImportedModels = state.models.isNotEmpty(),
            totalRamBytes = state.totalRamBytes,
            onImport = openPicker,
            onOpenModels = { showModels = true },
            onOpenGuide = { showModelGuide = true },
        )
    }

    if (showModels) {
        ModelSheet(
            state = state,
            onImport = openPicker,
            onActivate = viewModel::activate,
            onDelete = viewModel::deleteModel,
            onOpenGuide = { showModelGuide = true },
            onDismiss = { showModels = false },
        )
    }

    if (showModelGuide) {
        ModelGuideSheet(
            totalRamBytes = state.totalRamBytes,
            onDismiss = { showModelGuide = false },
        )
    }

    if (showSettings) {
        SettingsSheet(
            settings = state.settings,
            canToggleThinking = state.canToggleThinking,
            mtpDecision = state.mtpDecision,
            onApply = viewModel::updateSettings,
            onDismiss = { showSettings = false },
        )
    }
}
