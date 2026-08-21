package org.zzssg.llmchatapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.zzssg.llmchatapp.llm.ChatTurn

/**
 * Covers the rule that makes a reply survive being navigated away from.
 *
 * The bug this replaces: tokens were written straight into "the messages on
 * screen", so the only way to keep a reply from landing in the wrong transcript
 * was to cancel it whenever the user opened another chat -- which is exactly
 * what they saw as "switching chats stops the answer".
 */
class LiveReplyTest {

    private val user = ChatMessage(role = ChatTurn.ROLE_USER, text = "hello")
    private val assistant = ChatMessage(role = ChatTurn.ROLE_ASSISTANT, text = "", streaming = true)

    private fun reply() = LiveReply("chat-a", listOf(user, assistant))

    @Test
    fun `a reply knows which chat it belongs to`() {
        val live = reply()

        assertTrue(live.isOnScreen("chat-a"))
        assertFalse("another chat is not this reply's chat", live.isOnScreen("chat-b"))
        assertFalse("a fresh, unsaved chat is not this reply's chat", live.isOnScreen(null))
    }

    @Test
    fun `tokens keep accumulating while the user reads a different chat`() {
        val live = reply()

        live.apply(assistant.id) { it.copy(text = "par") }
        live.apply(assistant.id) { it.copy(text = "partial") }

        // Nothing here consults the screen: that is the point. The transcript is
        // complete when the reply ends, wherever the user happens to be.
        assertEquals("partial", live.messages.last().text)
        assertEquals("the question is still in front of the answer", user.id, live.messages.first().id)
    }

    @Test
    fun `settle ends the streaming bubble after a stop`() {
        val live = reply()
        live.apply(assistant.id) { it.copy(text = "half an ans") }

        live.settle()

        assertFalse(live.messages.last().streaming)
        assertEquals("what streamed in is kept", "half an ans", live.messages.last().text)
        assertTrue("the user message was never streaming", live.messages.none { it.streaming })
    }

    @Test
    fun `an update for a message that is not there changes nothing`() {
        val live = reply()

        live.apply("no-such-id") { it.copy(text = "wrong") }

        assertEquals(listOf("hello", ""), live.messages.map { it.text })
    }
}
