package org.zzssg.llmchatapp.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class StoredMessageTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Speculative-decoding counters have to survive a round trip.
     *
     * They were missing from the schema at first, so reopening a conversation
     * silently dropped the acceptance figure -- the number the whole feature is
     * judged by -- and the stats line reverted to looking like the old build.
     */
    @Test
    fun `draft counters survive a save and load`() {
        val original = StoredMessage(
            role = "assistant",
            text = "Blue",
            tokenCount = 253,
            elapsedMs = 102_000,
            drafted = 180,
            accepted = 122,
        )

        val restored = json.decodeFromString<StoredMessage>(json.encodeToString(original))

        assertEquals(original, restored)
        assertEquals(180, restored.drafted)
        assertEquals(122, restored.accepted)
    }

    /** History written before the counters existed must still open. */
    @Test
    fun `messages written by an older build still load`() {
        val legacy = """{"role":"assistant","text":"Blue","tokenCount":12,"elapsedMs":5000}"""

        val restored = json.decodeFromString<StoredMessage>(legacy)

        assertEquals("Blue", restored.text)
        assertEquals(12, restored.tokenCount)
        assertEquals("absent counters read as zero", 0, restored.drafted)
        assertEquals(0, restored.accepted)
    }

    /** And a chat written by a newer build must not break an older one. */
    @Test
    fun `unknown fields are ignored rather than fatal`() {
        val future = """{"role":"user","text":"Hi","tokenCount":0,"elapsedMs":0,"somethingNew":42}"""

        val restored = json.decodeFromString<StoredMessage>(future)

        assertEquals("Hi", restored.text)
    }

    @Test
    fun `a whole conversation round trips`() {
        val chat = StoredChat(
            id = "abc",
            title = "Colours",
            createdAt = 1,
            updatedAt = 2,
            modelId = "qwen35",
            messages = listOf(
                StoredMessage("user", "Name a colour"),
                StoredMessage("assistant", "Blue", 5, 1200, drafted = 8, accepted = 5),
            ),
        )

        val restored = json.decodeFromString<StoredChat>(json.encodeToString(chat))

        assertEquals(chat, restored)
        assertEquals(5, restored.messages[1].accepted)
    }
}
