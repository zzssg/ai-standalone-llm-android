package org.zzssg.llmchatapp.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** One stored turn. Mirrors the UI model but carries only what is worth keeping. */
@Serializable
data class StoredMessage(
    val role: String,
    val text: String,
    val tokenCount: Int = 0,
    val elapsedMs: Long = 0,
    // Speculative-decoding counters. Defaulted so chats written before these
    // existed still load -- Json is configured to ignore unknown keys, and
    // defaults cover the other direction.
    val drafted: Int = 0,
    val accepted: Int = 0,
)

/** A whole conversation as it sits on disk. */
@Serializable
data class StoredChat(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** Which model produced it, so the UI can say so. */
    val modelId: String = "",
    val messages: List<StoredMessage> = emptyList(),
)

/** What the history list needs, without parsing every message. */
data class ChatSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
    val modelId: String,
)

/**
 * Conversations on disk, one JSON file each.
 *
 * A file per chat rather than a single index keeps writes small -- only the
 * conversation that changed is rewritten -- and means a corrupt file costs one
 * conversation instead of the whole history.
 */
class ChatStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true   // tolerate history written by a newer build
        encodeDefaults = true
    }

    private val root: File
        get() = File(context.filesDir, "chats").apply { mkdirs() }

    suspend fun list(): List<ChatSummary> = withContext(Dispatchers.IO) {
        root.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .mapNotNull { file -> runCatching { read(file) }.getOrNull() }
            .map {
                ChatSummary(
                    id = it.id,
                    title = it.title.ifBlank { DEFAULT_TITLE },
                    updatedAt = it.updatedAt,
                    messageCount = it.messages.size,
                    modelId = it.modelId,
                )
            }
            .sortedByDescending { it.updatedAt }
    }

    suspend fun load(id: String): StoredChat? = withContext(Dispatchers.IO) {
        val file = fileFor(id)
        if (!file.isFile) null else runCatching { read(file) }.getOrNull()
    }

    suspend fun save(chat: StoredChat) = withContext(Dispatchers.IO) {
        // Write-then-rename: a process death mid-write leaves the previous
        // version intact rather than a half-written file.
        val target = fileFor(chat.id)
        val temp = File(root, "${chat.id}.json.tmp")
        temp.writeText(json.encodeToString(chat))
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        fileFor(id).delete()
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        root.listFiles().orEmpty().forEach { it.delete() }
    }

    private fun read(file: File): StoredChat = json.decodeFromString(file.readText())

    private fun fileFor(id: String) = File(root, "${sanitizeId(id)}.json")

    /** Ids are generated internally, but never build a path from unchecked text. */
    private fun sanitizeId(id: String) =
        id.replace(Regex("""[^A-Za-z0-9_-]"""), "_").take(64).ifEmpty { "chat" }

    companion object {
        const val DEFAULT_TITLE = "New chat"

        /**
         * Names a conversation after its opening message.
         *
         * Cheap and predictable: the first thing the user said is what they will
         * recognise in a list days later, and it costs no extra generation.
         */
        fun deriveTitle(firstUserMessage: String): String {
            val cleaned = firstUserMessage.trim().replace(Regex("""\s+"""), " ")
            if (cleaned.isEmpty()) return DEFAULT_TITLE
            return if (cleaned.length <= TITLE_MAX_LENGTH) {
                cleaned
            } else {
                // Prefer a word boundary so the title does not end mid-word.
                val cut = cleaned.take(TITLE_MAX_LENGTH)
                val lastSpace = cut.lastIndexOf(' ')
                (if (lastSpace > TITLE_MAX_LENGTH / 2) cut.take(lastSpace) else cut).trimEnd() + "…"
            }
        }

        private const val TITLE_MAX_LENGTH = 48
    }
}
