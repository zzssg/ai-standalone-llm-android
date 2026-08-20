@file:JvmName("LlamaBridge")

package org.zzssg.llmchatapp.llm

/**
 * Raw JNI surface. Nothing outside [LlamaEngine] should call these directly.
 *
 * These are deliberately **top-level** functions with an explicit `@file:JvmName`.
 * A top-level external function is guaranteed to compile to a `public static
 * native` member of the named facade class, which is exactly what the
 * `Java_org_zzssg_llmchatapp_llm_LlamaBridge_*` symbols in llama_wrapper.cpp
 * expect. Declaring them inside an `object` with `@JvmStatic` leaves it up to the
 * compiler whether the native method ends up static or instance-bound, and the
 * two need different C++ signatures.
 *
 * They must also stay `public`: `internal` would mangle the JVM names and the
 * runtime link would fail.
 *
 * All of them except [nativeIsModelLoaded], [nativeStop] and [nativeContextSize]
 * block for as long as the underlying work takes, so they must not run on the
 * main thread.
 */

/**
 * Streaming sink for a single generation. Called on the thread that invoked
 * [nativeGenerate], never on the main thread.
 *
 * The native side resolves these methods by name and signature, so renaming one
 * requires a matching change in llama_wrapper.cpp and in proguard-rules.pro.
 */
interface TokenSink {
    /** A chunk of decoded text. Always a whole number of UTF-8 characters. */
    fun onToken(text: String)

    /** Generation finished normally, was stopped, or hit the token limit. */
    fun onDone(tokenCount: Int, elapsedMs: Long)

    /** Terminal failure. [message] is `CODE|human readable text`. */
    fun onError(message: String)
}

/** False when the shared library is missing, i.e. an unsupported ABI. */
val isNativeLibraryAvailable: Boolean by lazy {
    runCatching { System.loadLibrary("llama_wrapper") }.isSuccess
}

/** Returns `"OK"` or `"CODE|message"`. Unloads any previously loaded model first. */
external fun nativeLoadModel(
    path: String,
    threads: Int,
    ctxSize: Int,
    gpuLayers: Int,
): String

external fun nativeUnloadModel()

external fun nativeIsModelLoaded(): Boolean

external fun nativeContextSize(): Int

external fun nativeModelDescription(): String

external fun nativeSetSampling(
    temp: Float,
    topP: Float,
    topK: Int,
    minP: Float,
    repeatPenalty: Float,
    seed: Int,
)

/** True when the loaded model marks its reasoning with `<think>` blocks. */
external fun nativeSupportsThinking(): Boolean

/**
 * Applies the model's own chat template to a conversation.
 *
 * [thinkingMode] must match the ordinal of [ThinkingMode].
 */
external fun nativeFormatPrompt(
    roles: Array<String>,
    contents: Array<String>,
    thinkingMode: Int,
): String

external fun nativeGenerate(prompt: String, maxTokens: Int, sink: TokenSink)

/** Asks the running generation to stop after the current token. */
external fun nativeStop()

/** Clears the KV cache so the next turn starts from an empty context. */
external fun nativeResetSession()
