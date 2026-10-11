package io.github.jeromekai.latent.core.store

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Durable JSON files: the port of write_json_atomic / read_json_or_default.
 *
 * Phones lose power and kill apps mid-write as routinely as the camera did,
 * and a half-written prompts.json must never stop the app from starting.
 */
object JsonFiles {
    val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** Replace [file] in one step, so an interrupted write cannot truncate it. */
    fun writeAtomic(file: File, value: JsonElement) {
        // Encode first: a value that cannot be encoded never creates the temp file.
        val bytes = (json.encodeToString(JsonElement.serializer(), value) + "\n").toByteArray(Charsets.UTF_8)
        writeBytesAtomic(file, bytes)
    }

    fun writeBytesAtomic(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            FileChannel.open(
                temp.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ).use { channel ->
                channel.write(java.nio.ByteBuffer.wrap(bytes))
                channel.force(true)
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: IOException) {
            temp.delete()
            throw error
        }
        syncDirectory(file.parentFile)
    }

    /** The rename itself must reach the disk, or a power cut can undo it. */
    private fun syncDirectory(directory: File?) {
        if (directory == null) return
        try {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
            // Not every filesystem allows opening a directory; the data is safe either way.
        }
    }

    /**
     * Parse [file], or return null when it is missing or damaged. A damaged
     * file is moved aside to <name>.corrupt so the next save writes cleanly and
     * the original survives for inspection.
     */
    fun readOrNull(file: File, onCorrupt: (File, Exception) -> Unit = { _, _ -> }): JsonElement? {
        if (!file.exists()) return null
        return try {
            json.parseToJsonElement(file.readText(Charsets.UTF_8))
        } catch (error: Exception) {
            onCorrupt(file, error)
            val quarantine = File(file.parentFile, "${file.name}.corrupt")
            quarantine.delete()
            file.renameTo(quarantine)
            null
        }
    }
}
