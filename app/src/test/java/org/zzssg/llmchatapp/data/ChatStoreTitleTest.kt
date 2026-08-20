package org.zzssg.llmchatapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStoreTitleTest {

    @Test
    fun `a short question becomes the title verbatim`() {
        assertEquals("Name one colour.", ChatStore.deriveTitle("Name one colour."))
    }

    @Test
    fun `surrounding and repeated whitespace is normalised`() {
        assertEquals("What is this", ChatStore.deriveTitle("  What\n is \t this  "))
    }

    @Test
    fun `an empty message falls back to the default title`() {
        assertEquals(ChatStore.DEFAULT_TITLE, ChatStore.deriveTitle("   "))
        assertEquals(ChatStore.DEFAULT_TITLE, ChatStore.deriveTitle(""))
    }

    @Test
    fun `a long message is cut at a word boundary`() {
        val long = "Explain the difference between memory bandwidth and compute throughput " +
            "for transformer inference on mobile hardware"
        val title = ChatStore.deriveTitle(long)

        assertTrue("should be shortened, got ${title.length}", title.length <= 50)
        assertTrue("should be marked as truncated", title.endsWith("…"))
        assertTrue("should not end mid-word", !title.dropLast(1).endsWith(" "))
        assertTrue("should start with the original text", long.startsWith(title.dropLast(1)))
    }

    @Test
    fun `a long unbroken run still gets truncated`() {
        val title = ChatStore.deriveTitle("a".repeat(200))
        assertTrue("should be shortened", title.length <= 50)
        assertTrue(title.endsWith("…"))
    }
}
