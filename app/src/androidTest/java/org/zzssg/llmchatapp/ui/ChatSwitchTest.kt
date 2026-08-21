package org.zzssg.llmchatapp.ui

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.zzssg.llmchatapp.data.ChatStore
import java.io.File

/**
 * The reported bug: "if I switch from a currently responding chat to a past one
 * and then switch back, it discontinues to generate an answer."
 *
 * It was not a stall. Tokens were written straight into whatever transcript was
 * on screen, so the only way to keep an answer out of the wrong conversation was
 * to cancel it on every switch -- which the ViewModel did, explicitly. This
 * drives a real model through a real switch and asserts the answer is still
 * being written afterwards, and lands in the chat it was asked in.
 *
 * Needs a model on the device, like the rest of the instrumented suite:
 *
 *   adb push model.gguf /data/local/tmp/test-model.gguf
 */
class ChatSwitchTest {

    private lateinit var app: Application
    private lateinit var viewModel: ChatViewModel
    private lateinit var chatStore: ChatStore

    @Before
    fun setUp() {
        val source = File(MODEL_PATH)
        assumeTrue("No model at $MODEL_PATH", source.isFile)

        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as Application
        chatStore = ChatStore(app)

        // The library reads from the app's own directory, so the pushed model is
        // linked in rather than imported through the picker.
        val models = File(app.filesDir, "models").apply { mkdirs() }
        val installed = File(models, "switch-test.gguf")
        if (!installed.isFile || installed.length() != source.length()) {
            source.copyTo(installed, overwrite = true)
        }

        viewModel = onMain { ChatViewModel(app) }
        val model = await("the pushed model to appear in the library") {
            viewModel.state.value.models.firstOrNull { it.file.name == installed.name }
        }
        onMain { viewModel.activate(model) }
        await("the model to load") { viewModel.state.value.activeModel }
    }

    @After
    fun tearDown() {
        onMain { viewModel.stopGeneration() }
        runBlocking { chatStore.list().forEach { chatStore.delete(it.id) } }
    }

    @Test
    fun switchingAwayAndBackKeepsTheAnswerComing() {
        // A chat to switch *to*: the bug needs somewhere else to go.
        onMain { viewModel.send("Say hello.") }
        await("the first reply to finish") { true.takeIf { !viewModel.state.value.isGenerating } }
        val firstChatId = requireNotNull(viewModel.state.value.activeChatId)

        onMain { viewModel.startNewChat() }
        assertEquals("a new chat starts empty", emptyList<ChatMessage>(), viewModel.state.value.messages)
        onMain { viewModel.send("Count slowly from one to twenty, one number per line.") }
        val answeringChatId = await("the new chat to get an id") {
            viewModel.state.value.activeChatId
        }
        val progressBefore = await("the answer to start arriving") {
            viewModel.state.value.messages.lastOrNull()?.text?.takeIf { it.length > 4 }
        }

        // The switch that used to kill it, and the switch back.
        // openChat reads from disk, so the switch lands a moment after the call.
        onMain { viewModel.openChat(firstChatId) }
        await("the old chat to come on screen") {
            firstChatId.takeIf { viewModel.state.value.activeChatId == it }
        }
        assertTrue("the answer should still be running", viewModel.state.value.isGenerating)

        onMain { viewModel.openChat(answeringChatId) }
        await("the answering chat to come back on screen") {
            answeringChatId.takeIf { viewModel.state.value.activeChatId == it }
        }
        val onReturn = requireNotNull(viewModel.state.value.messages.lastOrNull()).text
        assertTrue(
            "coming back should show what was written while away, not a truncated copy",
            onReturn.length >= progressBefore.length,
        )

        val progressAfter = await("more tokens after the round trip") {
            viewModel.state.value.messages.lastOrNull()?.text?.takeIf { it.length > onReturn.length }
        }
        assertTrue(progressAfter.startsWith(onReturn))

        await("the answer to finish") { true.takeIf { !viewModel.state.value.isGenerating } }
        val finished = viewModel.state.value.messages.last()
        assertFalse("the bubble should stop streaming", finished.streaming)
        assertTrue("the reply should have stats", finished.stats != null)
    }

    @Test
    fun anAnswerFinishedWhileAwayIsFiledUnderItsOwnChat() {
        onMain { viewModel.send("Say hello.") }
        await("the first reply to finish") { true.takeIf { !viewModel.state.value.isGenerating } }
        val firstChatId = requireNotNull(viewModel.state.value.activeChatId)

        onMain { viewModel.startNewChat() }
        onMain { viewModel.send("Name three colours.") }
        assertTrue("the second chat is a different one", viewModel.state.value.activeChatId != firstChatId)
        val answeringChatId = await("the second chat to get an id") {
            viewModel.state.value.activeChatId
        }

        // Leave before it finishes and stay away.
        onMain { viewModel.openChat(firstChatId) }
        await("the answer to finish while off screen") {
            true.takeIf { !viewModel.state.value.isGenerating }
        }

        val onScreen = viewModel.state.value.messages
        assertEquals("the old chat must not have grown", 2, onScreen.size)
        assertEquals("Say hello.", onScreen.first().text)

        val stored = runBlocking { chatStore.load(answeringChatId) }
        assertTrue("the answer should be on disk under its own chat", stored != null)
        val reply = requireNotNull(stored).messages.last()
        assertEquals(org.zzssg.llmchatapp.llm.ChatTurn.ROLE_ASSISTANT, reply.role)
        assertTrue("the stored answer should not be empty", reply.text.isNotBlank())
    }

    // -- helpers ------------------------------------------------------------

    /** The ViewModel and its scope both live on the main thread. */
    private fun <T> onMain(block: () -> T): T = runBlocking {
        withContext(Dispatchers.Main) { block() }
    }

    /** Polls [probe] until it returns non-null. Generation here is genuinely slow. */
    private fun <T : Any> await(what: String, probe: () -> T?): T = runBlocking {
        withTimeoutOrNull(TIMEOUT_MS) {
            while (true) {
                probe()?.let { return@withTimeoutOrNull it }
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: throw AssertionError("timed out waiting for $what")
    }

    private companion object {
        const val MODEL_PATH = "/data/local/tmp/test-model.gguf"
        const val TIMEOUT_MS = 180_000L
    }
}
