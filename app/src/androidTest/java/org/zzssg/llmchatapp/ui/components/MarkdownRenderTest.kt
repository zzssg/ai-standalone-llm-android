package org.zzssg.llmchatapp.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.zzssg.llmchatapp.ui.theme.LlmChatTheme
import java.io.File

/**
 * Renders a reply of the shape the model actually produces and captures it.
 *
 * The parser is covered by unit tests, but "parses correctly" and "looks right"
 * are different claims: a table that overflows its row or a heading that
 * collides with the bubble padding would pass every assertion and still be
 * wrong on screen. This draws the thing and writes a PNG so it can be looked at.
 */
class MarkdownRenderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rendersAStructuredReply() {
        compose.setContent {
            LlmChatTheme(dynamicColor = false) {
                Surface {
                    androidx.compose.foundation.layout.Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        MarkdownText(text = SAMPLE)
                    }
                }
            }
        }
        compose.waitForIdle()

        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        // The app's own external files dir: writable under scoped storage, and
        // still readable over adb. The logged path is how to find it.
        val dir = InstrumentationRegistry.getInstrumentation()
            .targetContext.getExternalFilesDir(null)
        val file = File(dir, "markdown-render.png")
        android.util.Log.i("MarkdownRenderTest", "wrote " + file.absolutePath)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        assertTrue("a bitmap should have been produced", file.length() > 0)
    }

    /**
     * The state the reasoning fix is about: thinking is on, the closing tag has
     * not arrived, and everything written so far is scratchpad. Before the fix
     * this drew as an ordinary answer for the whole time the model deliberated.
     */
    @Test
    fun rendersAReplyStillInsideItsReasoningBlock() {
        compose.setContent {
            LlmChatTheme(dynamicColor = false) {
                Surface {
                    androidx.compose.foundation.layout.Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        MarkdownText(text = MID_THOUGHT, startsInReasoning = true)
                    }
                }
            }
        }
        compose.waitForIdle()

        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = InstrumentationRegistry.getInstrumentation()
            .targetContext.getExternalFilesDir(null)
        val file = File(dir, "markdown-thinking.png")
        android.util.Log.i("MarkdownRenderTest", "wrote " + file.absolutePath)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        assertTrue("a bitmap should have been produced", file.length() > 0)
    }

    private companion object {
        /** Trimmed from a real reply, including the pre-filled closing think tag. */
        val SAMPLE = """
            The user wants help with income problems -- likely math word problems.
            </think>

            Here's how to solve income-related math problems:

            ---

            ## 1. Identify What You're Given

            | Type of Problem | Example |
            |---|---|
            | Total income from multiple jobs | Earns ${'$'}50/hr at Job A |
            | Average income | Earns ${'$'}80/day |

            ### Steps

            - Write down every rate
            - Multiply by the hours
            - Add the results

            Use `rate * hours` for each job, then sum. **Check the units.**

            ```kotlin
            val total = jobs.sumOf { it.rate * it.hours }
            ```
        """.trimIndent()

        /** Mid-generation, with the opener still sitting in the prompt. */
        val MID_THOUGHT = """
            The user is asking about income problems. I should work out what kind they mean before answering: it could be hourly rates, salaries, or averages over a period.

            Let me lay out the common shapes first, then pick an example that covers more than one of them.
        """.trimIndent()
    }
}
