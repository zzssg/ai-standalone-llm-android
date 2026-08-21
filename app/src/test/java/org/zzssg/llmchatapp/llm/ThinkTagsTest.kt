package org.zzssg.llmchatapp.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prompt is the only place that knows whether a reply will begin inside a
 * reasoning block, and all three thinking modes express the answer in the same
 * prompt text -- which is why this is read off the finished prompt instead of
 * being re-derived from the setting that produced it.
 */
class ThinkTagsTest {

    /** Thinking on: the opener is left hanging for the model to fill. */
    @Test
    fun `an open block at the end of the prompt starts the reply in reasoning`() {
        assertTrue(promptOpensReasoning("<|im_start|>assistant\n<think>\n"))
    }

    /** Thinking off: an empty block is pre-filled so the model skips to the answer. */
    @Test
    fun `a closed block does not`() {
        assertFalse(promptOpensReasoning("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
    }

    /** Model default, or a model that does not reason at all. */
    @Test
    fun `a prompt with no tags does not`() {
        assertFalse(promptOpensReasoning("<|im_start|>assistant\n"))
    }

    /**
     * History carries the earlier turns' tags. Only the last pair decides, or a
     * chat whose first reply reasoned would mark every later one as reasoning.
     */
    @Test
    fun `earlier closed blocks in the history do not confuse it`() {
        val prompt = "assistant: <think>a</think> hi\nuser: again\nassistant: <think>\n"

        assertTrue(promptOpensReasoning(prompt))
    }

    @Test
    fun `a history of closed blocks with a plain opener at the end is closed`() {
        val prompt = "assistant: <think>a</think> hi\nuser: again\nassistant: "

        assertFalse(promptOpensReasoning(prompt))
    }
}
