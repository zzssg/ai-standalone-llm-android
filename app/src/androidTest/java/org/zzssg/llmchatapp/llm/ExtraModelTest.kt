package org.zzssg.llmchatapp.llm

import android.util.Log
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Bring-your-own-model smoke test.
 *
 * `ConversationTest` deliberately uses a small model so the suite stays fast.
 * This one runs the same multi-turn check against whatever is placed at
 * [MODEL_PATH], which is how a newly supported architecture gets verified after
 * the vendored llama.cpp is updated:
 *
 *   adb push <model>.gguf /data/local/tmp/extra-model.gguf
 *   ./gradlew connectedDebugAndroidTest
 *
 * It skips when the file is absent, so it costs nothing on a normal run.
 */
class ExtraModelTest {

    private val engine = LlamaEngine()

    private val config = SamplingConfig(
        temperature = 0f,
        seed = 1234,
        // Large models on an emulator are slow; enough tokens to judge coherence,
        // few enough that the run finishes.
        maxTokens = 24,
    )

    @After
    fun tearDown() = runBlocking { engine.unload() }

    @Test
    fun extraModelLoadsAndHoldsAConversation() = runBlocking {
        val model = File(MODEL_PATH)
        assumeTrue("No model at $MODEL_PATH", model.isFile)
        assumeTrue("Native library unavailable on this ABI", engine.isAvailable)

        val loaded = engine.load(model, contextSize = 1024)
        Log.i(TAG, "loaded: ${loaded.description} ctx=${loaded.contextSize}")
        assertTrue("context size should be positive", loaded.contextSize > 0)

        engine.applySampling(config)

        val first = engine.reply(
            ChatTurn(ChatTurn.ROLE_SYSTEM, SYSTEM_PROMPT),
            ChatTurn(ChatTurn.ROLE_USER, "Name one colour."),
        )
        assertTrue("first reply should not be empty", first.isNotBlank())

        // The turn that used to break. It replays the assistant's answer the way
        // ChatViewModel does -- with the <think> block stripped -- which makes the
        // replayed history diverge from what is sitting in the KV cache. On a
        // recurrent or hybrid model that forces a rollback the cache cannot do,
        // and every second message of a conversation failed with E_DECODE.
        val second = engine.reply(
            ChatTurn(ChatTurn.ROLE_SYSTEM, SYSTEM_PROMPT),
            ChatTurn(ChatTurn.ROLE_USER, "Name one colour."),
            ChatTurn(ChatTurn.ROLE_ASSISTANT, first.withoutReasoning()),
            ChatTurn(ChatTurn.ROLE_USER, "Name a different one."),
        )
        assertTrue("second reply should not be empty", second.isNotBlank())
        assertNotEquals(
            "second reply should not repeat the first verbatim",
            first.trim(),
            second.trim(),
        )
    }

    /** Mirrors ChatViewModel: reasoning traces are not replayed to the model. */
    private fun String.withoutReasoning(): String {
        val open = indexOf("<think>")
        if (open < 0) return this
        val close = indexOf("</think>", open)
        return if (close < 0) substring(0, open).trim()
        else (substring(0, open) + substring(close + "</think>".length)).trim()
    }

    private suspend fun LlamaEngine.reply(vararg turns: ChatTurn): String {
        val text = generate(turns.toList(), config)
            .toList()
            .filterIsInstance<GenerationEvent.Token>()
            .joinToString("") { it.text }
        Log.i(TAG, "turns=${turns.size} reply=<<<$text>>>")
        return text
    }

    private companion object {
        const val MODEL_PATH = "/data/local/tmp/extra-model.gguf"
        const val SYSTEM_PROMPT = "You are a helpful assistant. Answer in one short sentence."
        const val TAG = "ExtraModelTest"
    }
}
