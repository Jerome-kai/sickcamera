package io.github.jeromekai.latent.core.gallery

import io.github.jeromekai.latent.core.model.GenerationMetadata
import io.github.jeromekai.latent.core.model.Normalize
import io.github.jeromekai.latent.core.store.JsonFiles
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class GalleryItem(
    val file: File,
    /** Relative to the generated directory, e.g. "2026-10-11/....jpg". */
    val relativePath: String,
    val modifiedMillis: Long,
    val sizeBytes: Long,
)

/**
 * The on-disk layout, kept close to the camera's data/ directory:
 *
 *   captures/<date>/<id>-<prompt>-source.jpg      the photo as taken
 *   captures/<date>/<id>-magic-reference.jpg      a magic-mode seed photo
 *   generated/<date>/<id>-<prompt>-generated.<ext> the AI result
 *   generated/<date>/<...>.<ext>.json             how it was made
 *   references/<prompt id>.jpg                    a prompt's reference image
 *
 * One difference: a photo and its result share one id. The camera stamped
 * them separately and had to pair them by nearest timestamp; sharing the id
 * makes "show me the original" exact.
 */
class Gallery(val root: File) {
    val capturesDir = File(root, "captures")
    val generatedDir = File(root, "generated")
    val referencesDir = File(root, "references")

    companion object {
        private val ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSSSSS")
        private val DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

        fun isImageFile(file: File): Boolean =
            file.isFile && !file.name.startsWith(".") && file.extension.lowercase() in IMAGE_EXTENSIONS

        /** The camera's media ids: %Y%m%d-%H%M%S-%f, microseconds included. */
        fun mediaId(now: LocalDateTime): String = now.format(ID_FORMAT)

        /** Folder-safe slug for the prompt part of a filename. */
        fun slug(value: String): String =
            value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "prompt" }
    }

    fun dayDir(base: File, now: LocalDateTime): File = File(base, now.format(DAY_FORMAT))

    fun capturePath(id: String, promptId: String, now: LocalDateTime): File =
        File(dayDir(capturesDir, now), "$id-${slug(promptId)}-source.jpg")

    fun magicReferencePath(id: String, now: LocalDateTime): File =
        File(dayDir(capturesDir, now), "$id-magic-reference.jpg")

    fun generatedPath(id: String, promptId: String, extension: String, now: LocalDateTime): File =
        File(dayDir(generatedDir, now), "$id-${slug(promptId)}-generated$extension")

    fun referenceImagePath(filename: String): File = File(referencesDir, filename)

    /** Path relative to [root], with forward slashes, for storing in JSON. */
    fun relative(file: File): String =
        file.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath

    /**
     * A stored relative path back to a file, refusing anything that resolves
     * outside [root] -- the traversal guard the camera's web routes had.
     */
    fun resolve(relativePath: String): File? {
        if (relativePath.isBlank()) return null
        val candidate = File(root, relativePath.trimStart('/', '\\')).canonicalFile
        val base = root.canonicalFile
        return if (candidate == base || candidate.path.startsWith(base.path + File.separator)) candidate else null
    }

    /** Every generated image, newest first. */
    fun list(): List<GalleryItem> {
        if (!generatedDir.exists()) return emptyList()
        val base = generatedDir.canonicalFile
        return generatedDir.walkTopDown()
            .filter(::isImageFile)
            .map { file ->
                GalleryItem(
                    file = file,
                    relativePath = file.canonicalFile.relativeTo(base).invariantSeparatorsPath,
                    modifiedMillis = file.lastModified(),
                    sizeBytes = file.length(),
                )
            }
            .sortedWith(compareByDescending<GalleryItem> { it.modifiedMillis }.thenByDescending { it.file.name })
            .toList()
    }

    fun metadataFile(image: File): File = File(image.parentFile, "${image.name}.json")

    fun readMetadata(image: File): GenerationMetadata =
        Normalize.metadata(JsonFiles.readOrNull(metadataFile(image)))

    fun writeMetadata(image: File, metadata: GenerationMetadata) =
        JsonFiles.writeAtomic(metadataFile(image), Normalize.metadataToJson(metadata))

    /** The photo a result was made from, for the compare view. */
    fun sourceFor(image: File): File? {
        val stored = readMetadata(image).capturePath ?: return null
        return resolve(stored)?.takeIf { it.isFile }
    }

    /**
     * Delete a generated image and its sidecar. The path must resolve inside
     * the generated directory; anything else is refused.
     */
    fun delete(image: File): Boolean {
        val target = image.canonicalFile
        val base = generatedDir.canonicalFile
        if (!target.path.startsWith(base.path + File.separator) || !isImageFile(target)) return false
        val deleted = target.delete()
        if (deleted) metadataFile(target).delete()
        return deleted
    }
}
