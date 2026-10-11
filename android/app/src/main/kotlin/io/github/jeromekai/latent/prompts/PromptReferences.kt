package io.github.jeromekai.latent.prompts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import io.github.jeromekai.latent.AppContainer
import io.github.jeromekai.latent.core.imaging.PixelSize
import io.github.jeromekai.latent.core.model.Normalize
import io.github.jeromekai.latent.core.model.Prompt
import io.github.jeromekai.latent.core.store.JsonFiles
import io.github.jeromekai.latent.data.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * A prompt's reference image: picked from the phone, shrunk, and kept in the
 * references directory under the prompt's own id. All of it is blocking disk
 * work, so each entry point hops to Dispatchers.IO itself and the screen can
 * call it from any coroutine.
 */
object PromptReferences {
    /** The camera refused uploads over 12 MiB; so do we. */
    const val MAX_BYTES = 12L * 1024 * 1024

    private const val JPEG_QUALITY = 88

    sealed interface Outcome {
        /** [fileName] is what the prompt now points at, inside the references directory. */
        data class Attached(val fileName: String) : Outcome

        /** [message] is fit to show the owner as it stands. */
        data class Failed(val message: String) : Outcome
    }

    /**
     * Shrink the picked image and make it [promptId]'s reference. A failure
     * leaves the old image (if any) exactly as it was: the new file is only
     * swapped in once it is whole, and the prompt only repointed after that.
     */
    suspend fun attach(container: AppContainer, promptId: String, uri: Uri, context: Context): Outcome =
        withContext(Dispatchers.IO) {
            val prompt = container.prompts.value.firstOrNull { it.id == promptId }
                ?: return@withContext Outcome.Failed("That prompt no longer exists.")
            val fileName = "$promptId.jpg"
            if (fileName.length > Normalize.REFERENCE_IMAGE_MAX_LENGTH) {
                // The store would cut the stored name short and the file saved
                // under the full one would be unreachable.
                return@withContext Outcome.Failed("This prompt's id is too long to hold an image.")
            }

            // Copied to a temp file first: the size cap is counted on the bytes
            // actually read (a provider's reported size can be missing or wrong),
            // and the EXIF-aware decoder wants a file it can open twice.
            val copy = File.createTempFile("reference-", ".tmp", context.cacheDir)
            try {
                if (!copyCapped(context, uri, copy)) {
                    return@withContext Outcome.Failed("That image is over 12 MB. Pick a smaller one.")
                }
                val jpeg = prepare(container, copy)
                    ?: return@withContext Outcome.Failed("That file is not a readable image.")

                JsonFiles.writeBytesAtomic(container.gallery.referenceImagePath(fileName), jpeg)
                val updated = container.prompts.edit(promptId) { it.copy(referenceImage = fileName) }
                if (updated == null) {
                    // Removed while we were working; do not leave its image behind.
                    container.gallery.referenceImagePath(fileName).delete()
                    return@withContext Outcome.Failed("That prompt no longer exists.")
                }
                // A prompt imported with some other file name moves to ours.
                if (prompt.referenceImage != fileName) deleteIfUnshared(container, prompt.referenceImage)
                Outcome.Attached(fileName)
            } catch (_: OutOfMemoryError) {
                Outcome.Failed("That image is too large to process.")
            } catch (_: Exception) {
                // IOException, SecurityException from a revoked grant, a provider
                // that throws on open: none should take the app down mid-pick.
                Outcome.Failed("Image upload failed.")
            } finally {
                copy.delete()
            }
        }

    /** Take the image off [prompt] and delete its file. False if it could not be done. */
    suspend fun remove(container: AppContainer, prompt: Prompt): Boolean = withContext(Dispatchers.IO) {
        try {
            // Read afresh: the card's copy of the prompt may be a frame behind.
            val name = container.prompts.value.firstOrNull { it.id == prompt.id }?.referenceImage
                ?: return@withContext false
            container.prompts.edit(prompt.id) { it.copy(referenceImage = "") }
            deleteIfUnshared(container, name)
            true
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Delete a prompt together with the reference image it owned. The camera
     * left that file on disk forever. False when this was the last prompt
     * (the shutter always needs one) or the write failed.
     */
    suspend fun removePrompt(container: AppContainer, prompt: Prompt): Boolean = withContext(Dispatchers.IO) {
        try {
            val name = container.prompts.value.firstOrNull { it.id == prompt.id }?.referenceImage.orEmpty()
            if (!container.prompts.remove(prompt.id)) return@withContext false
            deleteIfUnshared(container, name)
            true
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Two prompts can name one file only through a hand-edited prompts.json,
     * but deleting it then would blind the other. Call this AFTER the owner
     * has been repointed or removed, so any remaining reference is someone else's.
     */
    private fun deleteIfUnshared(container: AppContainer, fileName: String) {
        if (fileName.isEmpty()) return
        if (container.prompts.value.any { it.referenceImage == fileName }) return
        container.gallery.referenceImagePath(fileName).delete()
    }

    /** Copy [uri] into [into]. False, with the copy abandoned, once it passes [MAX_BYTES]. */
    private fun copyCapped(context: Context, uri: Uri, into: File): Boolean {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        input.use { source ->
            into.outputStream().use { sink ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BYTES) return false
                    sink.write(buffer, 0, read)
                }
            }
        }
        return true
    }

    /**
     * Upright, shrunk to fit the generation input size, flattened and encoded.
     * PIL's thumbnail() is the model: aspect kept, never upscaled, NEVER
     * cropped. (Images.fitForGeneration crops 4:3, which suits a photo but
     * would silently cut the edges off a character sheet or a style board.)
     */
    private fun prepare(container: AppContainer, source: File): ByteArray? {
        val settings = container.settings.value
        val long = maxOf(settings.inputWidth, settings.inputHeight)
        val short = minOf(settings.inputWidth, settings.inputHeight)

        val decoded = Images.decodeOriented(source, PixelSize(long, short)) ?: return null
        val fitted = Images.thumbnail(decoded, long, short)
        val flat = if (fitted.hasAlpha()) onWhite(fitted) else fitted
        val bytes = Images.jpeg(flat, JPEG_QUALITY)
        // thumbnail() and the no-alpha path hand back their input, so dedupe by identity.
        setOf(decoded, fitted, flat).forEach { it.recycle() }
        return bytes
    }

    /** JPEG has no alpha: left alone, a transparent PNG would turn black. */
    private fun onWhite(source: Bitmap): Bitmap {
        val flat = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, 0f, 0f, null)
        }
        return flat
    }
}
