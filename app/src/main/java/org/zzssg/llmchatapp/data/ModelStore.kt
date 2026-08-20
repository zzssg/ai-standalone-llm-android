package org.zzssg.llmchatapp.data

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** A GGUF file the user has imported. */
data class ModelFile(
    val file: File,
    val displayName: String,
    val sizeBytes: Long,
) {
    val id: String get() = file.name
}

/** Progress of an import, so the UI can show something during a multi-gigabyte copy. */
sealed interface ImportProgress {
    data class Copying(val bytesCopied: Long, val totalBytes: Long) : ImportProgress {
        /** Null when the source did not report a size. */
        val fraction: Float? =
            if (totalBytes > 0) (bytesCopied.toFloat() / totalBytes).coerceIn(0f, 1f) else null
    }

    data class Finished(val model: ModelFile) : ImportProgress
}

class ModelImportException(message: String) : IOException(message)

/**
 * Owns the on-disk model library.
 *
 * Models live in `filesDir/models`, not `cacheDir`: the system is free to evict
 * anything in the cache directory at any moment, which used to make a previously
 * working model vanish between sessions.
 */
class ModelStore(private val context: Context) {

    private val root: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    suspend fun list(): List<ModelFile> = withContext(Dispatchers.IO) {
        root.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(GGUF_EXTENSION, ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .map { ModelFile(it, it.nameWithoutExtension, it.length()) }
    }

    suspend fun delete(model: ModelFile): Boolean = withContext(Dispatchers.IO) {
        model.file.delete()
    }

    /**
     * Copies the document behind [uri] into the model library.
     *
     * A content:// URI from the picker is only readable while the grant lasts and
     * cannot be handed to native code as a path, so the bytes have to be copied.
     * The copy is cancellable and cleans up its partial file.
     */
    fun import(uri: Uri): Flow<ImportProgress> = flow {
        val resolver = context.contentResolver
        val (name, declaredSize) = queryMetadata(uri)

        if (!name.endsWith(GGUF_EXTENSION, ignoreCase = true)) {
            throw ModelImportException(
                "\"$name\" is not a .gguf file. Pick a GGUF model exported for llama.cpp."
            )
        }

        if (declaredSize > 0) {
            val free = availableBytes()
            // Keep a little headroom so the device does not end up completely full.
            if (declaredSize + FREE_SPACE_MARGIN_BYTES > free) {
                throw ModelImportException(
                    "Not enough free space: this model needs ${formatBytes(declaredSize)} " +
                        "but only ${formatBytes(free)} is available."
                )
            }
        }

        val target = File(root, sanitize(name))
        val partial = File(root, "${target.name}.part")
        partial.delete()

        try {
            val input = resolver.openInputStream(uri)
                ?: throw ModelImportException("Could not open the selected file.")

            input.use { source ->
                partial.outputStream().use { sink ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var copied = 0L
                    var lastReported = 0L

                    while (true) {
                        coroutineContext.ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        copied += read

                        // Throttle emissions: a 4 GB copy at 64 KB per chunk would
                        // otherwise push ~65k updates through to recomposition.
                        if (copied - lastReported >= PROGRESS_STEP_BYTES) {
                            lastReported = copied
                            emit(ImportProgress.Copying(copied, declaredSize))
                        }
                    }
                    sink.flush()
                    emit(ImportProgress.Copying(copied, declaredSize))
                }
            }

            if (!partial.renameTo(target)) {
                throw ModelImportException("Could not finish writing the model to storage.")
            }
            emit(ImportProgress.Finished(ModelFile(target, target.nameWithoutExtension, target.length())))
        } catch (e: Throwable) {
            partial.delete()
            throw e
        }
    }.flowOn(Dispatchers.IO)

    private fun queryMetadata(uri: Uri): Pair<String, Long> {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    cursor.getString(nameIndex)
                } else {
                    uri.lastPathSegment.orEmpty()
                }
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex)
                } else {
                    0L
                }
                return name to size
            }
        }
        return uri.lastPathSegment.orEmpty() to 0L
    }

    private fun availableBytes(): Long {
        val stat = StatFs(context.filesDir.absolutePath)
        return stat.availableBlocksLong * stat.blockSizeLong
    }

    /** Display names come from an untrusted provider, so strip anything path-like. */
    private fun sanitize(name: String): String =
        name.substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            .take(MAX_FILENAME_LENGTH)
            .ifEmpty { "model$GGUF_EXTENSION" }

    private companion object {
        const val GGUF_EXTENSION = ".gguf"
        const val COPY_BUFFER_BYTES = 1 shl 16
        const val PROGRESS_STEP_BYTES = 4L shl 20
        const val FREE_SPACE_MARGIN_BYTES = 256L shl 20
        const val MAX_FILENAME_LENGTH = 120
    }
}

/**
 * Human-readable byte count, e.g. `1.8 GB`.
 *
 * Uses the device locale explicitly so the decimal separator matches the rest of
 * the UI rather than depending on whatever default is in effect.
 */
fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String = when {
    bytes >= 1L shl 30 -> String.format(locale, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(locale, "%.0f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(locale, "%.0f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
