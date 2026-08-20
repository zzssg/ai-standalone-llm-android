package org.zzssg.llmchatapp.llm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * A GGUF file whose architecture this build cannot run must be reported as such.
 *
 * llama.cpp rejects an unknown architecture with an internal exception that it
 * logs and swallows, leaving the JNI layer with nothing but a NULL model. That is
 * indistinguishable from a corrupt file, and the app used to blame the file
 * ("not a readable GGUF model") for what is really a missing feature.
 *
 * Push an unsupported model to run this:
 *   adb push <model>.gguf /data/local/tmp/unsupported-model.gguf
 */
class UnsupportedModelTest {

    @Test
    fun unsupportedArchitectureIsNamedRatherThanBlamedOnTheFile() = runBlocking {
        val model = File("/data/local/tmp/unsupported-model.gguf")
        assumeTrue("No unsupported model pushed to ${model.path}", model.isFile)

        val engine = LlamaEngine()
        assumeTrue("Native library unavailable on this ABI", engine.isAvailable)

        val error = runCatching { engine.load(model, contextSize = 512) }.exceptionOrNull()

        assertTrue("expected a LlamaException, got $error", error is LlamaException)
        assertEquals("E_ARCH", (error as LlamaException).code)
        assertTrue(
            "the message should name the architecture, got: ${error.message}",
            error.message.orEmpty().contains("architecture"),
        )
    }
}
