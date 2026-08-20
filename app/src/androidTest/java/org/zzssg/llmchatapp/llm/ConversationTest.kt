package org.zzssg.llmchatapp.llm

import android.util.Log
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * End-to-end checks against a real GGUF model.
 *
 * These are the tests the old build could not have had: its instrumented test
 * only asserted that a model file loads. The interesting failures were all in
 * what happens on the *second* turn, so that is what this exercises.
 *
 * The model is not committed (GGUF files are hundreds of megabytes). Push one to
 * the device before running:
 *
 *   adb push model.gguf /data/local/tmp/test-model.gguf
 *   ./gradlew connectedDebugAndroidTest
 *
 * Without it every test here is skipped rather than failed, so the suite stays
 * green on a machine that has no model.
 */
class ConversationTest {

    private lateinit var engine: LlamaEngine
    private lateinit var modelFile: File

    private val config = SamplingConfig(
        // Deterministic: a fixed seed plus greedy decoding means a repeated run
        // produces identical output, so a failure here is a real regression and
        // not sampling noise.
        temperature = 0f,
        seed = 1234,
        maxTokens = 48,
    )

    @Before
    fun setUp() {
        modelFile = File(MODEL_PATH)
        assumeTrue(
            "No model at $MODEL_PATH -- push one with `adb push <model>.gguf $MODEL_PATH`",
            modelFile.isFile,
        )
        engine = LlamaEngine()
        assumeTrue("Native library unavailable on this ABI", engine.isAvailable)
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) runBlocking { engine.unload() }
    }

    @Test
    fun modelLoadsAndReportsItsContext() = runBlocking {
        val loaded = engine.load(modelFile, contextSize = 1024)

        assertTrue("context size should be positive", loaded.contextSize > 0)
        assertTrue("context should be clamped to 1024", loaded.contextSize <= 1024)
        assertTrue("engine should report a model as loaded", engine.isModelLoaded)
    }

    @Test
    fun singleTurnProducesText() = runBlocking {
        engine.load(modelFile, contextSize = 1024)
        engine.applySampling(config)

        val reply = engine.reply(
            ChatTurn(ChatTurn.ROLE_SYSTEM, SYSTEM_PROMPT),
            ChatTurn(ChatTurn.ROLE_USER, "Name one colour."),
        )

        assertTrue("reply should not be empty", reply.text.isNotBlank())
        assertTrue("at least one token should be generated", reply.tokenCount > 0)
    }

    /**
     * The regression test for the KV-cache bug.
     *
     * The old build never cleared or reused the cache between turns, so the
     * second turn decoded onto positions the first turn still occupied. The
     * first reply looked fine and everything after it was garbage. Asserting
     * only on turn one would have passed on the broken build.
     */
    @Test
    fun secondTurnIsCoherentAfterTheFirst() = runBlocking {
        engine.load(modelFile, contextSize = 1024)
        engine.applySampling(config)

        val first = engine.reply(
            ChatTurn(ChatTurn.ROLE_SYSTEM, SYSTEM_PROMPT),
            ChatTurn(ChatTurn.ROLE_USER, "Name one colour."),
        )
        assertTrue("first reply should not be empty", first.text.isNotBlank())

        val second = engine.reply(
            ChatTurn(ChatTurn.ROLE_SYSTEM, SYSTEM_PROMPT),
            ChatTurn(ChatTurn.ROLE_USER, "Name one colour."),
            ChatTurn(ChatTurn.ROLE_ASSISTANT, first.text),
            ChatTurn(ChatTurn.ROLE_USER, "Name a different one."),
        )

        assertTrue("second reply should not be empty", second.text.isNotBlank())
        assertTrue("second turn should generate tokens", second.tokenCount > 0)
        assertTrue(
            "second reply should be readable text, got: ${second.text.take(120)}",
            second.text.isReadableText(),
        )
        assertNotEquals(
            "second reply should not repeat the first verbatim",
            first.text.trim(),
            second.text.trim(),
        )
    }

    /** A third turn goes further into the prefix-reuse path than the second. */
    @Test
    fun thirdTurnStillProducesReadableText() = runBlocking {
        engine.load(modelFile, contextSize = 1024)
        engine.applySampling(config)

        val turns = mutableListOf(
            ChatTurn(ChatTurn.ROLE_SYSTEM, SYSTEM_PROMPT),
            ChatTurn(ChatTurn.ROLE_USER, "Name one colour."),
        )

        repeat(3) { index ->
            val reply = engine.reply(*turns.toTypedArray())
            assertTrue(
                "turn ${index + 1} should be readable, got: ${reply.text.take(120)}",
                reply.text.isNotBlank() && reply.text.isReadableText(),
            )
            turns += ChatTurn(ChatTurn.ROLE_ASSISTANT, reply.text)
            turns += ChatTurn(ChatTurn.ROLE_USER, "Name another one.")
        }
    }

    /**
     * Same conversation twice with a fixed seed and temperature 0 must give the
     * same answer. If the KV cache leaked state between conversations, the
     * second run would diverge.
     */
    @Test
    fun resettingTheSessionMakesGenerationRepeatable() = runBlocking {
        engine.load(modelFile, contextSize = 1024)
        engine.applySampling(config)

        val prompt = arrayOf(
            ChatTurn(ChatTurn.ROLE_SYSTEM, SYSTEM_PROMPT),
            ChatTurn(ChatTurn.ROLE_USER, "Name one colour."),
        )

        val first = engine.reply(*prompt)
        engine.resetSession()
        val second = engine.reply(*prompt)

        assertEquals(
            "a reset session with a fixed seed should reproduce the reply",
            first.text,
            second.text,
        )
    }

    @Test
    fun generatingWithoutAModelFails() = runBlocking {
        val error = runCatching {
            engine.reply(ChatTurn(ChatTurn.ROLE_USER, "hello"))
        }.exceptionOrNull()

        assertTrue("expected a LlamaException, got $error", error is LlamaException)
        assertEquals("E_NOT_LOADED", (error as LlamaException).code)
    }

    @Test
    fun replyLimitIsRespected() = runBlocking {
        engine.load(modelFile, contextSize = 1024)
        engine.applySampling(config)

        // maxTokens is consumed by generate(), not by applySampling(), so it has
        // to travel with the call rather than being set on the engine.
        val reply = engine.reply(
            ChatTurn(ChatTurn.ROLE_SYSTEM, SYSTEM_PROMPT),
            ChatTurn(ChatTurn.ROLE_USER, "Count from one to fifty."),
            with = config.copy(maxTokens = 8),
        )

        assertTrue(
            "generated ${reply.tokenCount} tokens, limit was 8",
            reply.tokenCount <= 8,
        )
    }

    // -- helpers ------------------------------------------------------------

    private data class Reply(val text: String, val tokenCount: Int)

    private suspend fun LlamaEngine.reply(
        vararg turns: ChatTurn,
        with: SamplingConfig = config,
    ): Reply {
        val events = generate(turns.toList(), with).toList()
        val text = events.filterIsInstance<GenerationEvent.Token>()
            .joinToString("") { it.text }
        val done = events.filterIsInstance<GenerationEvent.Done>().lastOrNull()

        // Logged so a failing assertion can be diagnosed from logcat without
        // re-running, and so a human can eyeball what the model actually said --
        // the readability assertions are heuristics, not a judgement of content.
        Log.i(TAG, "turns=${turns.size} tokens=${done?.tokenCount} reply=<<<$text>>>")

        return Reply(text, done?.tokenCount ?: 0)
    }

    private companion object {
        const val MODEL_PATH = "/data/local/tmp/test-model.gguf"
        const val SYSTEM_PROMPT = "You are a helpful assistant. Answer in one short sentence."
        const val TAG = "ConversationTest"
    }
}

/**
 * A weak but useful signal that output is text rather than corrupted decoding.
 *
 * A broken KV cache does not usually yield empty output; it yields punctuation
 * soup, repeated control fragments or replacement characters. Checking that most
 * of the reply is letters, digits, spaces or ordinary punctuation catches that
 * without asserting anything about what the model actually said.
 */
private fun String.isReadableText(): Boolean {
    val trimmed = trim()
    if (trimmed.length < 2) return false
    if (trimmed.contains('�')) return false

    val sane = trimmed.count { it.isLetterOrDigit() || it.isWhitespace() || it in ",.!?;:'\"-()" }
    return sane.toDouble() / trimmed.length >= 0.8
}
