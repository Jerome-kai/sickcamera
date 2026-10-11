package io.github.jeromekai.latent.camera

import android.util.Log
import io.github.jeromekai.latent.AppContainer
import io.github.jeromekai.latent.core.api.ImageInput
import io.github.jeromekai.latent.core.api.OpenAiImageEditor
import io.github.jeromekai.latent.core.gallery.Gallery
import io.github.jeromekai.latent.core.imaging.Geometry
import io.github.jeromekai.latent.core.imaging.PixelSize
import io.github.jeromekai.latent.core.model.MagicHistoryEntry
import io.github.jeromekai.latent.core.queue.GenerationJob
import io.github.jeromekai.latent.data.Images
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val TAG = "CameraSession"

/** A message for the status line; [id] makes a repeat of the same text show again. */
data class Status(val text: String, val isError: Boolean = false, val id: Long = System.nanoTime())

data class SessionState(
    val magicActive: Boolean = false,
    /** A magic prompt is being written right now. */
    val magicPending: Boolean = false,
    /** The last magic attempt failed (the camera's "Magic mode failed"). */
    val magicFailed: Boolean = false,
    /** The current magic recipe: the newest one written, or restored at start. */
    val magic: MagicHistoryEntry? = null,
    val status: Status? = null,
)

/**
 * The camera controller's capture and magic-mode logic. It lives for the
 * whole process, not one screen, so a magic prompt being written carries on
 * when you wander off to the album -- as it did on the camera.
 *
 * Status strings are the camera's, verbatim, except where they named a
 * physical button that a phone does not have.
 */
class CameraSession(private val container: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(SessionState(magic = restoreMagic()))
    val state: StateFlow<SessionState> = _state.asStateFlow()

    init {
        // With Magic Mode switched off the camera could not be in magic mode
        // and shot with normal prompts. The phone can flip the switch while a
        // recipe is active, so leave magic mode when that happens.
        scope.launch {
            container.settings.settings
                .map { it.magicEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (!enabled) _state.update { it.copy(magicActive = false, magicFailed = false) }
                }
        }
    }

    /**
     * magic-history-restore-at-boot: the newest recipe comes back if its
     * reference photo still exists, so magic mode can shoot straight away.
     * Older entries are not tried, as on the camera.
     */
    private fun restoreMagic(): MagicHistoryEntry? {
        val newest = container.magicHistory.value.firstOrNull() ?: return null
        val reference = newest.referenceCapturePath?.let { container.gallery.resolve(it) }
        return newest.takeIf { reference?.isFile == true }
    }

    /**
     * Any new message replaces the last one, as on the camera -- which is also
     * what ends the "Magic mode failed" pill, since that was just the status
     * text being exactly that string.
     */
    fun say(text: String, isError: Boolean = false) =
        _state.update { it.copy(status = Status(text, isError), magicFailed = false) }

    /** _magic_prompt_display_text: what the title pill shows in magic mode. */
    fun magicPillText(s: SessionState = state.value): String = when {
        s.magicFailed -> s.magic?.title ?: "Recipe failed"
        s.magicPending -> "Making magic recipe"
        s.magic == null -> "Snap a photo of something unique"
        else -> s.magic.title
    }

    /** created_at as the camera wrote it: local ISO time to the second, seconds always present. */
    private fun isoSeconds(now: LocalDateTime): String =
        now.withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

    /** A photo is a temporary file in the cache; deleting it is disk work too. */
    private fun discard(photo: File) {
        scope.launch(Dispatchers.IO) { photo.delete() }
    }

    // ---- refusals --------------------------------------------------------------

    /** Why the shutter cannot fire right now, or null if it can. */
    fun refusal(): String? {
        if (!container.secureKey.hasKey.value) return "Add your API key in Settings first."
        val cap = container.settings.value.maxPendingGenerations
        if (cap > 0 && container.queue.value.pending >= cap) return "Queue full. Wait for album."
        return null
    }

    // ---- normal and magic shots ----------------------------------------------

    /** The shutter: a normal shot, or a magic one while magic mode is on. */
    fun shoot(photo: File) {
        val s = state.value
        // The camera's order: a recipe still being written, then no recipe yet.
        if (s.magicActive && s.magicPending) {
            discard(photo)
            say("Magic prompt is still being written")
            return
        }
        if (s.magicActive && s.magic == null) {
            discard(photo)
            say("Hold the shutter to create a magic prompt first")
            return
        }
        val prompt = container.selectedPrompt()
        val magic = if (s.magicActive) s.magic else null
        val title = magic?.title ?: prompt.title
        say(if (magic != null) "Using magic prompt: $title" else "$title queued")

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    try {
                        val references = if (magic != null) {
                            listOfNotNull(magic.referenceCapturePath)
                        } else {
                            listOfNotNull(container.referenceFile(prompt)?.let { container.gallery.relative(it) })
                        }
                        enqueue(
                            photo = photo,
                            promptId = if (magic != null) "magic" else prompt.id,
                            promptTitle = title,
                            promptBody = magic?.body ?: prompt.body,
                            references = references,
                            magicHistoryId = magic?.id,
                        )
                    } finally {
                        photo.delete()
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Capture failed", error)
                say("Capture failed. Preview ready.", isError = true)
            }
        }
    }

    /** Runs on the IO dispatcher: it decodes, scales and writes the photo, then saves the job. */
    private fun enqueue(
        photo: File,
        promptId: String,
        promptTitle: String,
        promptBody: String,
        references: List<String>,
        magicHistoryId: String?,
    ) {
        val settings = container.settings.value
        val model = container.selectedModel()
        val now = LocalDateTime.now()
        val id = Gallery.mediaId(now)

        val oriented = Images.orientedSize(photo) ?: error("the camera returned an unreadable photo")
        val input = Geometry.generationInputSize(oriented, settings.inputWidth, settings.inputHeight)
        val decoded = Images.decodeOriented(photo, input) ?: error("the camera returned an unreadable photo")
        val fitted = Images.fitForGeneration(decoded, settings.inputWidth, settings.inputHeight)
        // Taken before the recycle below: a recycled bitmap is not for reading.
        val fittedSize = PixelSize(fitted.width, fitted.height)
        val capture = container.gallery.capturePath(id, promptId, now)
        capture.parentFile?.mkdirs()
        capture.writeBytes(Images.jpeg(fitted, 90))
        if (fitted !== decoded) decoded.recycle()
        fitted.recycle()

        val output = container.gallery.generatedPath(id, promptId, settings.outputFormat.extension, now)
        val job = GenerationJob(
            id = id,
            createdAt = now.toString(),
            promptId = promptId,
            promptTitle = promptTitle,
            promptBody = promptBody,
            capturePath = container.gallery.relative(capture),
            generatedPath = container.gallery.relative(output),
            referencePaths = references,
            magicHistoryId = magicHistoryId,
            model = model.model,
            api = model.api,
            // A portrait shot asks for a portrait image back.
            size = Geometry.outputSize(settings.size, fittedSize),
            quality = settings.quality,
            outputFormat = settings.outputFormat.wire,
            outputCompression = settings.outputCompression,
        )
        container.jobs.save(job)
        container.refreshQueue()
        container.scheduler.schedule(job.id)
    }

    // ---- magic mode ------------------------------------------------------------

    /**
     * Hold the shutter: _queue_magic_prompt_from_current_frame. The photo
     * becomes a reference, and a vision model invents a short motif and an
     * edit prompt from it.
     */
    fun seedMagic(photo: File) {
        val settings = container.settings.value
        if (!settings.magicEnabled) { discard(photo); return }
        if (state.value.magicPending) { discard(photo); say("Magic prompt is still being written"); return }

        _state.update {
            it.copy(
                magicActive = true,
                magicPending = true,
                magicFailed = false,
                status = Status("Building a magic prompt from this reference photo"),
            )
        }
        scope.launch {
            try {
                val now = LocalDateTime.now()
                // All on the IO dispatcher: the decode, the Keystore read behind
                // endpoint() and the base64 of the upload are not main-thread work.
                // The reference photo is kept even if planning fails, as on the camera.
                val entry = withContext(Dispatchers.IO) {
                    try {
                        val id = Gallery.mediaId(now)
                        val oriented = Images.orientedSize(photo) ?: error("unreadable photo")
                        val input = Geometry.generationInputSize(oriented, settings.inputWidth, settings.inputHeight)
                        val decoded = Images.decodeOriented(photo, input) ?: error("unreadable photo")
                        val fitted = Images.fitForGeneration(decoded, settings.inputWidth, settings.inputHeight)
                        val reference = container.gallery.magicReferencePath(id, now)
                        reference.parentFile?.mkdirs()
                        reference.writeBytes(Images.jpeg(fitted, 90))
                        if (fitted !== decoded) decoded.recycle()
                        fitted.recycle()

                        val planned = container.planner.plan(
                            endpoint = container.endpoint(),
                            image = ImageInput(reference.readBytes(), reference.name, "image/jpeg"),
                            model = settings.magicModel,
                            api = settings.magicApi,
                            timeoutSeconds = settings.magicTimeoutSeconds,
                        )
                        container.magicHistory.add(
                            MagicHistoryEntry(
                                id = "magic-${reference.nameWithoutExtension}",
                                createdAt = isoSeconds(now),
                                title = planned.title,
                                body = planned.prompt,
                                referenceCapturePath = container.gallery.relative(reference),
                                promotedPromptId = null,
                            ),
                        )
                    } finally {
                        photo.delete()
                    }
                }
                _state.update {
                    it.copy(magic = entry, magicPending = false, magicFailed = false, status = Status("Magic prompt ready: ${entry.title}"))
                }
            } catch (error: Exception) {
                // Magic mode stays on and the previous recipe stays current.
                _state.update {
                    it.copy(
                        magicPending = false,
                        magicFailed = true,
                        status = Status("Magic mode failed: ${error.message ?: "unknown error"}", isError = true),
                    )
                }
            }
        }
    }

    /** The prompt button in magic mode: back to normal prompts. */
    fun exitMagic() {
        _state.update {
            it.copy(magicActive = false, magicFailed = false, status = Status("Ready with ${container.selectedPrompt().title}"))
        }
    }

    /** Use a recipe from the history as the current magic prompt. */
    fun useMagic(entry: MagicHistoryEntry) {
        // Magic Mode off means magic mode cannot start. Say why, because the
        // recipe list that called this is about to hand control back to the camera.
        if (!container.settings.value.magicEnabled) {
            say("Magic mode is turned off in Settings", isError = true)
            return
        }
        _state.update { it.copy(magic = entry, magicActive = true, magicFailed = false, status = Status("Magic ready: ${entry.title}")) }
    }

    // ---- recreate as a 9:16 story -----------------------------------------------

    /**
     * recreate_vertical_from_generated, queued instead of blocking the screen.
     * True means the request was accepted; the job file is written in the
     * background, so a failure after that arrives as a status message.
     */
    fun recreateStory(generated: File): Boolean {
        refusal()?.let { say(it, isError = true); return false }
        say("Recreating as a 9:16 story")
        scope.launch {
            try {
                withContext(Dispatchers.IO) { enqueueStory(generated) }
            } catch (error: Exception) {
                Log.w(TAG, "Could not queue the 9:16 story", error)
                say(error.message ?: "Could not recreate this image", isError = true)
            }
        }
        return true
    }

    /** Runs on the IO dispatcher: path checks, job file and queue count all touch the disk. */
    private fun enqueueStory(generated: File) {
        // The camera's two refusals, with its messages.
        require(generated.canonicalFile.startsWith(container.gallery.generatedDir.canonicalFile)) {
            "Invalid generated image path."
        }
        check(generated.isFile) { "Generated image not found." }

        val settings = container.settings.value
        val model = container.selectedModel()
        val now = LocalDateTime.now()
        val id = Gallery.mediaId(now)
        val output = container.gallery.generatedPath(id, "story", settings.outputFormat.extension, now)
        val source = container.gallery.relative(generated)
        val job = GenerationJob(
            id = id,
            createdAt = now.toString(),
            promptId = "story",
            promptTitle = "Story",
            promptBody = OpenAiImageEditor.STORY_PROMPT,
            capturePath = source,
            generatedPath = container.gallery.relative(output),
            model = model.model,
            api = model.api,
            size = OpenAiImageEditor.STORY_SIZE,
            quality = settings.quality,
            outputFormat = settings.outputFormat.wire,
            outputCompression = settings.outputCompression,
            variant = "story",
            sourceGeneratedPath = source,
        )
        container.jobs.save(job)
        container.refreshQueue()
        container.scheduler.schedule(job.id)
    }
}
