package org.zzssg.llmchatapp.llm

import android.util.Log
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The correctness contract for speculative decoding.
 *
 * A drafted token is only kept when the target model would have produced it
 * anyway, so at temperature 0 the output must be **identical** with MTP on and
 * off -- not merely similar. That makes the whole feature falsifiable: a bug in
 * the hidden-state carryover or the rollback shows up as diverging text rather
 * than as a crash, and nothing else in the test suite would catch it.
 *
 *   adb push <qwen3.5-class model>.gguf /data/local/tmp/extra-model.gguf
 */
class MtpEquivalenceTest {

    private val engine = LlamaEngine()

    private val config = SamplingConfig(
        // Greedy: the invariant only holds when the target is deterministic.
        temperature = 0f,
        seed = 1234,
        maxTokens = 32,
        thinking = ThinkingMode.OFF,
    )

    @After
    fun tearDown() = runBlocking { engine.unload() }

    @Test
    fun speculativeDecodingReproducesPlainOutput(): Unit = runBlocking {
        val model = File(MODEL_PATH)
        assumeTrue("No model at $MODEL_PATH", model.isFile)
        assumeTrue("Native library unavailable on this ABI", engine.isAvailable)

        val plain = generateWith(model, mtpDraft = 0)
        val probe = engine.load(model, contextSize = CTX, mtpDraft = DRAFT)
        assumeTrue("This model ships no MTP block", probe.mtpDraft > 0)
        engine.unload()

        val speculative = generateWith(model, mtpDraft = DRAFT)

        Log.i(TAG, "plain       = <<<$plain>>>")
        Log.i(TAG, "speculative = <<<$speculative>>>")

        assertTrue("plain output should not be empty", plain.isNotBlank())
        assertEquals(
            "speculative decoding must not change what the model says",
            plain,
            speculative,
        )
    }

    /** Two prompts, so a pass is not an accident of one short answer. */
    @Test
    fun equivalenceHoldsAcrossPrompts(): Unit = runBlocking {
        val model = File(MODEL_PATH)
        assumeTrue("No model at $MODEL_PATH", model.isFile)
        assumeTrue("Native library unavailable on this ABI", engine.isAvailable)

        val probe = engine.load(model, contextSize = CTX, mtpDraft = DRAFT)
        assumeTrue("This model ships no MTP block", probe.mtpDraft > 0)
        engine.unload()

        for (prompt in listOf("List three colours.", "What is 17 plus 25?")) {
            val plain = generateWith(model, mtpDraft = 0, prompt = prompt)
            val speculative = generateWith(model, mtpDraft = DRAFT, prompt = prompt)
            Log.i(TAG, "prompt=$prompt plain=<<<$plain>>> spec=<<<$speculative>>>")
            assertEquals("diverged on: $prompt", plain, speculative)
        }
    }

    /** A second turn exercises the carryover across a prompt boundary. */
    @Test
    fun equivalenceHoldsOnTheSecondTurn(): Unit = runBlocking {
        val model = File(MODEL_PATH)
        assumeTrue("No model at $MODEL_PATH", model.isFile)
        assumeTrue("Native library unavailable on this ABI", engine.isAvailable)

        val probe = engine.load(model, contextSize = CTX, mtpDraft = DRAFT)
        assumeTrue("This model ships no MTP block", probe.mtpDraft > 0)
        engine.unload()

        val plain = twoTurns(model, mtpDraft = 0)
        val speculative = twoTurns(model, mtpDraft = DRAFT)

        Log.i(TAG, "turn2 plain=<<<$plain>>> spec=<<<$speculative>>>")
        assertEquals("the second turn diverged", plain, speculative)
    }

    private suspend fun generateWith(
        model: File,
        mtpDraft: Int,
        prompt: String = "List three colours.",
    ): String {
        engine.unload()
        engine.load(model, contextSize = CTX, mtpDraft = mtpDraft)
        engine.applySampling(config)
        return engine.reply(
            ChatTurn(ChatTurn.ROLE_SYSTEM, SYSTEM_PROMPT),
            ChatTurn(ChatTurn.ROLE_USER, prompt),
        )
    }

    private suspend fun twoTurns(model: File, mtpDraft: Int): String {
        engine.unload()
        engine.load(model, contextSize = CTX, mtpDraft = mtpDraft)
        engine.applySampling(config)

        val first = engine.reply(
            ChatTurn(ChatTurn.ROLE_SYSTEM, SYSTEM_PROMPT),
            ChatTurn(ChatTurn.ROLE_USER, "Name one colour."),
        )
        return engine.reply(
            ChatTurn(ChatTurn.ROLE_SYSTEM, SYSTEM_PROMPT),
            ChatTurn(ChatTurn.ROLE_USER, "Name one colour."),
            ChatTurn(ChatTurn.ROLE_ASSISTANT, first),
            ChatTurn(ChatTurn.ROLE_USER, "Name a different one."),
        )
    }

    private suspend fun LlamaEngine.reply(vararg turns: ChatTurn): String =
        generate(turns.toList(), config)
            .toList()
            .filterIsInstance<GenerationEvent.Token>()
            .joinToString("") { it.text }

    private companion object {
        const val MODEL_PATH = "/data/local/tmp/extra-model.gguf"
        const val SYSTEM_PROMPT = "You are a helpful assistant. Answer in one short sentence."
        const val CTX = 1024
        const val DRAFT = 4
        const val TAG = "MtpEquivalenceTest"
    }
}
