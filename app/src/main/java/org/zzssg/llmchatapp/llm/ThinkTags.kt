package org.zzssg.llmchatapp.llm

/**
 * The markers reasoning models wrap their scratchpad in.
 *
 * They belong to the model's chat template rather than to the UI, which is why
 * they live next to the engine: the prompt builder writes them, the renderer
 * reads them, and both have to agree on the same two strings.
 */
const val THINK_OPEN = "<think>"
const val THINK_CLOSE = "</think>"

/**
 * True when [prompt] hands the model an open reasoning block to fill in.
 *
 * This is the fact the UI cannot guess. With thinking switched on, the *opening*
 * tag is part of the prompt, so the reply arrives already inside the block and
 * the only tag that ever streams in is the closing one -- which is why an
 * unfinished reply used to be shown as if the scratchpad were the answer. With
 * thinking off the prompt closes an empty block instead, and on "model default"
 * the template decides. Reading the finished prompt covers all three without
 * reproducing the rule that produced it.
 */
fun promptOpensReasoning(prompt: String): Boolean {
    val open = prompt.lastIndexOf(THINK_OPEN)
    return open >= 0 && prompt.lastIndexOf(THINK_CLOSE) < open
}
