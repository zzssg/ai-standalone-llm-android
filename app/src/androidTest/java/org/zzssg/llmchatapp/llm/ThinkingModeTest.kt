package org.zzssg.llmchatapp.llm

import android.util.Log
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Thinking mode is expressed in the prompt, so it is verifiable end to end:
 * turning it off must make the model skip the `<think>` block entirely.
 *
 * Needs a reasoning model at [MODEL_PATH]:
 *   adb push <reasoning-model>.gguf /data/local/tmp/extra-model.gguf
 */
class ThinkingModeTest {

    private val engine = LlamaEngine()

    private val base = SamplingConfig(temperature = 0f, seed = 1234, maxTokens = 24)

    @After
    fun tearDown() = runBlocking { engine.unload() }

    @Test
    fun thinkingCanBeTurnedOffForAReasoningModel() = runBlocking {
        val model = File(MODEL_PATH)
        assumeTrue("No model at $MODEL_PATH", model.isFile)
        assumeTrue("Native library unavailable on this ABI", engine.isAvailable)

        val loaded = engine.load(model, contextSize = 1024)
        assumeTrue("Model is not a reasoning model", loaded.supportsThinking)
        engine.applySampling(base)

        val off = engine.reply(base.copy(thinking = ThinkingMode.OFF))
        Log.i(TAG, "OFF -> <<<$off>>>")
        assertFalse(
            "with thinking off the model should not open a think block, got: $off",
            off.contains("<think>"),
        )
        assertTrue("reply should not be empty", off.isNotBlank())
    }

    @Test
    fun reasoningModelsAreDetected() = runBlocking {
        val model = File(MODEL_PATH)
        assumeTrue("No model at $MODEL_PATH", model.isFile)
        assumeTrue("Native library unavailable on this ABI", engine.isAvailable)

        val loaded = engine.load(model, contextSize = 512)
        Log.i(TAG, "supportsThinking=${loaded.supportsThinking} for ${loaded.description}")
        assertTrue("qwen35 marks reasoning with <think>", loaded.supportsThinking)
    }

    private suspend fun LlamaEngine.reply(config: SamplingConfig): String =
        generate(
            listOf(
                ChatTurn(ChatTurn.ROLE_SYSTEM, "You are a helpful assistant. Answer in one short sentence."),
                ChatTurn(ChatTurn.ROLE_USER, "Name one colour."),
            ),
            config,
        ).toList().filterIsInstance<GenerationEvent.Token>().joinToString("") { it.text }

    private companion object {
        const val MODEL_PATH = "/data/local/tmp/extra-model.gguf"
        const val TAG = "ThinkingModeTest"
    }
}
