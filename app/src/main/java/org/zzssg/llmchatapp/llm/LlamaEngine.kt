package org.zzssg.llmchatapp.llm

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One turn of a conversation as the model sees it. */
data class ChatTurn(val role: String, val content: String) {
    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

/**
 * Whether the model should reason before answering.
 *
 * Reasoning models emit a `<think>` block; the choice is expressed in the prompt
 * itself, so it costs nothing to change and takes effect on the very next turn.
 * The ordinals are shared with the native side.
 */
enum class ThinkingMode {
    /** Leave the template alone and let the model decide. */
    AUTO,

    /** Reason first. Better on hard questions, slower and more tokens. */
    ON,

    /** Answer directly. Noticeably faster on a phone. */
    OFF,
}

/** Knobs exposed in the settings sheet. */
data class SamplingConfig(
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    /** Negative means "reseed on every turn". */
    val seed: Int = -1,
    val maxTokens: Int = 512,
    val thinking: ThinkingMode = ThinkingMode.AUTO,
)

/** What the engine knows about the model that is currently resident. */
data class LoadedModel(
    val file: File,
    val description: String,
    val contextSize: Int,
    /** Whether a thinking toggle is meaningful for this model. */
    val supportsThinking: Boolean,
)

/** A failure with a stable code, so the UI can react without matching on prose. */
class LlamaException(val code: String, message: String) : Exception(message)

/** Emitted while a response streams in. */
sealed interface GenerationEvent {
    data class Token(val text: String) : GenerationEvent
    data class Done(val tokenCount: Int, val elapsedMs: Long) : GenerationEvent
}

/**
 * Coroutine-friendly facade over the JNI bridge.
 *
 * Exactly one model is resident at a time and exactly one generation may run at
 * a time; the native layer enforces both and reports `E_BUSY` on violation.
 */
class LlamaEngine(private val io: CoroutineDispatcher = Dispatchers.IO) {

    val isAvailable: Boolean get() = isNativeLibraryAvailable

    val isModelLoaded: Boolean
        get() = isNativeLibraryAvailable && nativeIsModelLoaded()

    /**
     * Loads [file] into memory, replacing whatever was loaded before.
     *
     * @param threads 0 lets the native side pick based on the big-core count.
     * @param contextSize clamped down to the model's training context.
     */
    suspend fun load(
        file: File,
        threads: Int = 0,
        contextSize: Int = 4096,
        gpuLayers: Int = 0,
    ): LoadedModel = withContext(io) {
        requireAvailable()
        if (!file.isFile) {
            throw LlamaException("E_MISSING", "The model file is no longer on this device.")
        }

        val result = nativeLoadModel(file.absolutePath, threads, contextSize, gpuLayers)
        if (result != "OK") throw result.toLlamaException()

        LoadedModel(
            file = file,
            description = nativeModelDescription(),
            contextSize = nativeContextSize(),
            supportsThinking = nativeSupportsThinking(),
        )
    }

    suspend fun unload() = withContext(io) {
        if (isNativeLibraryAvailable) nativeUnloadModel()
    }

    suspend fun applySampling(config: SamplingConfig) = withContext(io) {
        requireAvailable()
        nativeSetSampling(
            config.temperature,
            config.topP,
            config.topK,
            config.minP,
            config.repeatPenalty,
            if (config.seed < 0) -1 else config.seed,
        )
    }

    /** Forgets the KV cache. Call when the user starts a new conversation. */
    suspend fun resetSession() = withContext(io) {
        if (isNativeLibraryAvailable) nativeResetSession()
    }

    /**
     * Streams the assistant's reply to [turns].
     *
     * The conversation is rendered with the model's own chat template, which is
     * what makes instruct models answer instead of rambling. Collecting the flow
     * runs the generation; cancelling the collection stops it at the next token.
     */
    fun generate(turns: List<ChatTurn>, config: SamplingConfig): Flow<GenerationEvent> =
        callbackFlow {
            requireAvailable()

            // Check this before formatting. nativeFormatPrompt returns an empty
            // string when no model is loaded, which would otherwise surface as
            // "could not build a prompt" -- a misleading message for what is
            // really a missing model.
            if (!nativeIsModelLoaded()) {
                throw LlamaException("E_NOT_LOADED", "No model is loaded.")
            }

            val prompt = nativeFormatPrompt(
                turns.map { it.role }.toTypedArray(),
                turns.map { it.content }.toTypedArray(),
                config.thinking.ordinal,
            )
            if (prompt.isEmpty()) {
                throw LlamaException("E_PROMPT", "Could not build a prompt for this model.")
            }

            val sink = object : TokenSink {
                override fun onToken(text: String) {
                    trySend(GenerationEvent.Token(text))
                }

                override fun onDone(tokenCount: Int, elapsedMs: Long) {
                    trySend(GenerationEvent.Done(tokenCount, elapsedMs))
                    close()
                }

                override fun onError(message: String) {
                    close(message.toLlamaException())
                }
            }

            // nativeGenerate blocks until the reply is complete, so it runs in its
            // own coroutine. That keeps this block free to reach awaitClose, which
            // is what turns collector cancellation into a native stop.
            launch(io) { nativeGenerate(prompt, config.maxTokens, sink) }

            awaitClose { nativeStop() }
        }
            // Unbounded so a slow collector can never make us drop a token: the
            // default callbackFlow buffer is 64 and trySend silently fails past it.
            .buffer(Channel.UNLIMITED)
            .flowOn(io)

    /** Stops the in-flight generation. Safe to call when nothing is running. */
    fun stop() {
        if (isNativeLibraryAvailable) nativeStop()
    }

    /**
     * Fire-and-forget teardown for use from `ViewModel.onCleared`, where
     * viewModelScope is already cancelled and there is nowhere left to suspend.
     * Unloading a multi-gigabyte mmap is not instant, so it must not block the
     * main thread.
     */
    fun releaseAsync() {
        if (!isNativeLibraryAvailable) return
        nativeStop()
        Thread({ nativeUnloadModel() }, "llama-release").apply {
            isDaemon = true
            start()
        }
    }

    private fun requireAvailable() {
        if (!isNativeLibraryAvailable) {
            throw LlamaException(
                "E_NO_NATIVE",
                "This device is not supported: the inference library could not be loaded.",
            )
        }
    }
}

/** Native errors arrive as `CODE|message`. */
internal fun String.toLlamaException(): LlamaException {
    val separator = indexOf('|')
    return if (separator > 0) {
        LlamaException(substring(0, separator), substring(separator + 1))
    } else {
        LlamaException("E_UNKNOWN", this)
    }
}
