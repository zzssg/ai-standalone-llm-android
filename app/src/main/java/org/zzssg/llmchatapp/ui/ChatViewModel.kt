package org.zzssg.llmchatapp.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.app.ActivityManager
import android.content.Context
import org.zzssg.llmchatapp.data.AppSettings
import org.zzssg.llmchatapp.data.ChatStore
import org.zzssg.llmchatapp.data.ChatSummary
import org.zzssg.llmchatapp.data.StoredChat
import org.zzssg.llmchatapp.data.StoredMessage
import org.zzssg.llmchatapp.data.ImportProgress
import org.zzssg.llmchatapp.data.ModelFile
import org.zzssg.llmchatapp.data.ModelStore
import org.zzssg.llmchatapp.data.MtpDecision
import org.zzssg.llmchatapp.data.MtpPolicy
import org.zzssg.llmchatapp.data.SettingsStore
import org.zzssg.llmchatapp.llm.ChatTurn
import org.zzssg.llmchatapp.llm.GenerationEvent
import org.zzssg.llmchatapp.llm.LlamaEngine
import org.zzssg.llmchatapp.llm.LlamaException
import org.zzssg.llmchatapp.llm.LoadedModel
import org.zzssg.llmchatapp.llm.ThinkingMode
import org.zzssg.llmchatapp.ui.components.THINK_CLOSE
import org.zzssg.llmchatapp.ui.components.THINK_OPEN
import java.util.Locale
import java.util.UUID

/** One bubble in the transcript. */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String,
    /** True while tokens are still arriving for this message. */
    val streaming: Boolean = false,
    /** Set on the assistant message once generation ends. */
    val stats: GenerationStats? = null,
) {
    val isUser: Boolean get() = role == ChatTurn.ROLE_USER
}

data class GenerationStats(
    val tokenCount: Int,
    val elapsedMs: Long,
    val drafted: Int = 0,
    val accepted: Int = 0,
) {
    /** Share of drafted tokens the model kept; null when speculation did not run. */
    val acceptance: Double? get() = if (drafted > 0) accepted.toDouble() / drafted else null

    val tokensPerSecond: Double =
        if (elapsedMs > 0) tokenCount * 1000.0 / elapsedMs else 0.0

    /**
     * How long the reply took, in the coarsest unit that still reads precisely:
     * sub-second in milliseconds, minutes once a reply runs that long. On-device
     * generation regularly crosses all three ranges depending on model size.
     */
    val formattedDuration: String
        get() = when {
            elapsedMs < 1_000 -> "$elapsedMs ms"
            elapsedMs < 60_000 -> String.format(Locale.US, "%.1f s", elapsedMs / 1000.0)
            else -> String.format(
                Locale.US,
                "%d:%02d min",
                elapsedMs / 60_000,
                (elapsedMs % 60_000) / 1000,
            )
        }
}

/** What the model manager sheet is currently doing. */
sealed interface ModelUiState {
    data object Idle : ModelUiState
    data class Importing(val fraction: Float?, val copied: Long, val total: Long) : ModelUiState

    /**
     * The model is being opened. [reason] explains why, because a reload the user
     * did not ask for -- triggered by a settings change -- needs to say so.
     */
    data class Loading(val name: String, val reason: LoadReason = LoadReason.OPENING) : ModelUiState
}

enum class LoadReason { OPENING, SETTINGS_CHANGED }

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val models: List<ModelFile> = emptyList(),
    val activeModel: LoadedModel? = null,
    val modelState: ModelUiState = ModelUiState.Idle,
    val isGenerating: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val error: UserFacingError? = null,
    val nativeAvailable: Boolean = true,
    val chats: List<ChatSummary> = emptyList(),
    val activeChatId: String? = null,
    val mtpDecision: MtpDecision? = null,
    val totalRamBytes: Long = 0,
) {
    val isReady: Boolean get() = activeModel != null
    val isBusy: Boolean get() = modelState != ModelUiState.Idle
    val isReloading: Boolean get() = modelState is ModelUiState.Loading

    /** A thinking toggle only makes sense for a model that reasons. */
    val canToggleThinking: Boolean get() = activeModel?.supportsThinking == true
}

/** An error phrased for a person, with an optional recovery action. */
data class UserFacingError(
    val title: String,
    val detail: String,
    val retryable: Boolean = false,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = LlamaEngine()
    private val modelStore = ModelStore(app)
    private val settingsStore = SettingsStore(app)
    private val chatStore = ChatStore(app)

    /**
     * Physical RAM, one of the two inputs to the speculation decision. Read once:
     * it cannot change while the app runs.
     */
    private val totalRamBytes: Long = run {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    }

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var generationJob: Job? = null

    init {
        val settings = settingsStore.load()
        _state.update {
            it.copy(settings = settings, nativeAvailable = engine.isAvailable, totalRamBytes = totalRamBytes)
        }
        refreshModels(autoLoadId = settings.lastModelId)
        refreshChats()
    }

    // -- Conversation history -----------------------------------------------

    fun refreshChats() {
        viewModelScope.launch {
            _state.update { it.copy(chats = chatStore.list()) }
        }
    }

    /** Opens a stored conversation, replacing whatever is on screen. */
    fun openChat(id: String) {
        if (_state.value.activeChatId == id) return
        viewModelScope.launch {
            stopGeneration()
            val stored = chatStore.load(id) ?: return@launch
            // The KV cache holds the previous conversation; the new one has to be
            // processed from scratch.
            engine.resetSession()
            _state.update { state ->
                state.copy(
                    activeChatId = stored.id,
                    messages = stored.messages.map { it.toUiMessage() },
                    error = null,
                )
            }
        }
    }

    fun deleteChat(id: String) {
        viewModelScope.launch {
            chatStore.delete(id)
            if (_state.value.activeChatId == id) {
                startNewChat()
            }
            _state.update { it.copy(chats = chatStore.list()) }
        }
    }

    /** Clears the screen for a fresh conversation. Nothing is written until it has content. */
    fun startNewChat() {
        viewModelScope.launch {
            stopGeneration()
            engine.resetSession()
            _state.update { it.copy(activeChatId = null, messages = emptyList(), error = null) }
        }
    }

    /**
     * Writes the current conversation to disk.
     *
     * Called once a turn completes rather than on every token: a chat is only
     * worth keeping when it has an answer in it, and rewriting the file per token
     * would be pointless I/O.
     */
    private fun persistCurrentChat() {
        val state = _state.value
        val messages = state.messages.filter { it.text.isNotBlank() }
        if (messages.isEmpty()) return

        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val id = state.activeChatId ?: UUID.randomUUID().toString()
            val existing = state.activeChatId?.let { chatStore.load(it) }

            chatStore.save(
                StoredChat(
                    id = id,
                    title = existing?.title?.takeIf { it.isNotBlank() }
                        ?: ChatStore.deriveTitle(messages.firstOrNull { it.isUser }?.text.orEmpty()),
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                    modelId = state.activeModel?.file?.nameWithoutExtension.orEmpty(),
                    messages = messages.map { it.toStored() },
                )
            )
            _state.update { it.copy(activeChatId = id, chats = chatStore.list()) }
        }
    }

    // -- Model library ------------------------------------------------------

    fun refreshModels(autoLoadId: String? = null) {
        viewModelScope.launch {
            val models = modelStore.list()
            _state.update { it.copy(models = models) }

            // Reopen the model from last session so returning users land straight
            // in a usable chat instead of on a picker.
            if (autoLoadId != null && _state.value.activeModel == null) {
                models.firstOrNull { it.id == autoLoadId }?.let { activate(it) }
            }
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            try {
                modelStore.import(uri).collect { progress ->
                    when (progress) {
                        is ImportProgress.Copying -> _state.update {
                            it.copy(
                                modelState = ModelUiState.Importing(
                                    progress.fraction,
                                    progress.bytesCopied,
                                    progress.totalBytes,
                                )
                            )
                        }

                        is ImportProgress.Finished -> {
                            _state.update { it.copy(modelState = ModelUiState.Idle) }
                            refreshModels()
                            activate(progress.model)
                        }
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        modelState = ModelUiState.Idle,
                        error = UserFacingError("Could not import the model", e.readableMessage()),
                    )
                }
            }
        }
    }

    /** Loads [model] into the engine and makes it the active one. */
    fun activate(model: ModelFile, reason: LoadReason = LoadReason.OPENING) {
        if (_state.value.isBusy) return
        viewModelScope.launch {
            stopGeneration()
            _state.update {
                it.copy(
                    modelState = ModelUiState.Loading(model.displayName, reason),
                    error = null,
                )
            }
            try {
                val settings = _state.value.settings

                // The policy needs the model's parameter count, which is only
                // known once it is open. Load without speculation first, decide,
                // and reload only when the answer is yes -- reloading costs
                // seconds, so it is not done speculatively itself.
                var loaded = engine.load(
                    file = model.file,
                    threads = settings.threads,
                    contextSize = settings.contextSize,
                )

                val decision = MtpPolicy.decide(
                    mode = settings.mtp,
                    totalRamBytes = totalRamBytes,
                    modelParams = loaded.params,
                    modelHasMtpBlock = loaded.hasMtpBlock,
                )
                if (decision.enabled) {
                    loaded = engine.load(
                        file = model.file,
                        threads = settings.threads,
                        contextSize = settings.contextSize,
                        mtpDraft = decision.draft,
                    )
                }
                engine.applySampling(settings.sampling)

                val updatedSettings = settings.copy(lastModelId = model.id)
                settingsStore.save(updatedSettings)

                _state.update {
                    it.copy(
                        activeModel = loaded,
                        mtpDecision = decision,
                        modelState = ModelUiState.Idle,
                        settings = updatedSettings,
                        // Reopening the same model for a settings change keeps the
                        // conversation; switching models does not, because a
                        // different tokenizer and template make it unreplayable.
                        messages = if (reason == LoadReason.SETTINGS_CHANGED) it.messages else emptyList(),
                        activeChatId = if (reason == LoadReason.SETTINGS_CHANGED) it.activeChatId else null,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        modelState = ModelUiState.Idle,
                        activeModel = null,
                        error = UserFacingError(
                            // An unsupported architecture is not a transient
                            // failure, so it gets its own title rather than the
                            // generic one -- retrying would only fail again.
                            title = if ((e as? LlamaException)?.code == "E_ARCH") {
                                "This model is not supported"
                            } else {
                                "Could not open the model"
                            },
                            detail = e.readableMessage(),
                        ),
                    )
                }
            }
        }
    }

    fun deleteModel(model: ModelFile) {
        viewModelScope.launch {
            if (_state.value.activeModel?.file == model.file) {
                stopGeneration()
                engine.unload()
                _state.update { it.copy(activeModel = null, messages = emptyList()) }
            }
            modelStore.delete(model)
            refreshModels()
        }
    }

    // -- Conversation -------------------------------------------------------

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _state.value.isGenerating || !_state.value.isReady) return

        val userMessage = ChatMessage(role = ChatTurn.ROLE_USER, text = prompt)
        val placeholder = ChatMessage(role = ChatTurn.ROLE_ASSISTANT, text = "", streaming = true)

        _state.update {
            it.copy(
                messages = it.messages + userMessage + placeholder,
                isGenerating = true,
                error = null,
            )
        }

        val turns = buildTurns()
        val config = _state.value.settings.sampling

        generationJob = viewModelScope.launch {
            val builder = StringBuilder()
            try {
                engine.generate(turns, config).collect { event ->
                    when (event) {
                        is GenerationEvent.Token -> {
                            builder.append(event.text)
                            updateMessage(placeholder.id) { it.copy(text = builder.toString()) }
                        }

                        is GenerationEvent.Done -> updateMessage(placeholder.id) {
                            it.copy(
                                text = builder.toString(),
                                streaming = false,
                                stats = GenerationStats(
                                    tokenCount = event.tokenCount,
                                    elapsedMs = event.elapsedMs,
                                    drafted = event.drafted,
                                    accepted = event.accepted,
                                ),
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                // The user pressed Stop, or the ViewModel is going away. Not an
                // error -- and it must be rethrown so the coroutine really ends.
                updateMessage(placeholder.id) { it.copy(text = builder.toString(), streaming = false) }
                throw e
            } catch (e: Exception) {
                // Keep whatever streamed in before the failure; discarding it
                // loses work the user already watched appear.
                updateMessage(placeholder.id) { it.copy(streaming = false) }
                if (builder.isEmpty()) {
                    _state.update { s -> s.copy(messages = s.messages.filterNot { it.id == placeholder.id }) }
                }
                _state.update {
                    it.copy(
                        error = UserFacingError(
                            title = "The model could not answer",
                            detail = e.readableMessage(),
                            retryable = true,
                        )
                    )
                }
            } finally {
                _state.update { it.copy(isGenerating = false) }
                persistCurrentChat()
            }
        }
    }

    /** Stops the in-flight reply, keeping the partial text. */
    fun stopGeneration() {
        engine.stop()
        generationJob?.cancel()
        generationJob = null
        _state.update { state ->
            state.copy(
                isGenerating = false,
                messages = state.messages.map { if (it.streaming) it.copy(streaming = false) else it },
            )
        }
    }

    /** Re-runs the last user message. Available after an error or a stop. */
    fun retryLast() {
        val lastUser = _state.value.messages.lastOrNull { it.isUser } ?: return
        _state.update { state ->
            val cutoff = state.messages.indexOfLast { it.id == lastUser.id }
            state.copy(messages = state.messages.take(cutoff), error = null)
        }
        send(lastUser.text)
    }

    fun newConversation() = startNewChat()

    // -- Settings -----------------------------------------------------------

    /**
     * Applies settings and reopens the model.
     *
     * Every change reloads, not just the ones that strictly require it. Context
     * size and thread count are baked into the llama_context, and speculative
     * decoding decides how many contexts exist at all -- but sampling could in
     * principle be applied live. Reloading uniformly is still the right call:
     * partial application is what let the speculative-decoding setting look like
     * it had taken effect while the engine carried on with the old one, and a
     * settings screen that sometimes applies and sometimes does not is worse
     * than one that always costs a few seconds.
     */
    fun updateSettings(settings: AppSettings) {
        settingsStore.save(settings)
        _state.update { it.copy(settings = settings) }

        viewModelScope.launch {
            runCatching { engine.applySampling(settings.sampling) }

            val active = _state.value.activeModel ?: return@launch
            _state.value.models
                .firstOrNull { it.id == active.file.name }
                ?.let { activate(it, LoadReason.SETTINGS_CHANGED) }
        }
    }

    /**
     * Thinking mode is a per-question choice, so it applies immediately and is
     * persisted -- no reload, because it only changes the prompt suffix.
     */
    fun setThinkingMode(mode: ThinkingMode) {
        val updated = _state.value.settings.let {
            it.copy(sampling = it.sampling.copy(thinking = mode))
        }
        settingsStore.save(updated)
        _state.update { it.copy(settings = updated) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    // -- Internals ----------------------------------------------------------

    /**
     * The conversation as the model should see it: the system prompt plus every
     * completed turn. The empty streaming placeholder is excluded so the template
     * ends with the assistant-turn opener rather than a blank assistant message.
     */
    private fun buildTurns(): List<ChatTurn> = buildList {
        val systemPrompt = _state.value.settings.systemPrompt.trim()
        if (systemPrompt.isNotEmpty()) {
            add(ChatTurn(ChatTurn.ROLE_SYSTEM, systemPrompt))
        }
        _state.value.messages
            .map { it.role to if (it.isUser) it.text else it.text.withoutReasoning() }
            .filter { (_, text) -> text.isNotBlank() }
            .forEach { (role, text) -> add(ChatTurn(role, text)) }
    }

    private fun updateMessage(id: String, transform: (ChatMessage) -> ChatMessage) {
        _state.update { state ->
            state.copy(messages = state.messages.map { if (it.id == id) transform(it) else it })
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Releasing here rather than in Activity.onDestroy is what makes a screen
        // rotation survivable: the ViewModel outlives the Activity, so a model
        // that took thirty seconds to load is no longer thrown away on rotate.
        engine.releaseAsync()
    }
}

/**
 * Drops `<think>` blocks from an assistant turn before it is replayed.
 *
 * Reasoning models are trained to see only their own *answers* in the history,
 * not their scratchpads -- their own chat templates strip prior thinking. Sending
 * it back wastes context and degrades the next reply.
 */
private fun String.withoutReasoning(): String {
    if (!contains(THINK_OPEN)) return this

    val out = StringBuilder()
    var index = 0
    while (index < length) {
        val open = indexOf(THINK_OPEN, index)
        if (open < 0) {
            out.append(this, index, length)
            break
        }
        out.append(this, index, open)
        val close = indexOf(THINK_CLOSE, open + THINK_OPEN.length)
        // An unterminated block means the whole tail is reasoning.
        if (close < 0) break
        index = close + THINK_CLOSE.length
    }
    return out.toString().trim()
}

private fun StoredMessage.toUiMessage() = ChatMessage(
    role = role,
    text = text,
    stats = if (tokenCount > 0) {
        GenerationStats(tokenCount, elapsedMs, drafted, accepted)
    } else {
        null
    },
)

private fun ChatMessage.toStored() = StoredMessage(
    role = role,
    text = text,
    tokenCount = stats?.tokenCount ?: 0,
    elapsedMs = stats?.elapsedMs ?: 0,
    drafted = stats?.drafted ?: 0,
    accepted = stats?.accepted ?: 0,
)

private fun Throwable.readableMessage(): String = when (this) {
    is LlamaException -> message ?: "Unknown engine error ($code)."
    else -> message ?: this::class.java.simpleName
}
