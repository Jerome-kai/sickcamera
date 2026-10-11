package io.github.jeromekai.latent

import android.content.Context
import io.github.jeromekai.latent.camera.CameraSession
import io.github.jeromekai.latent.core.api.ApiEndpoint
import io.github.jeromekai.latent.core.api.OpenAiImageEditor
import io.github.jeromekai.latent.core.api.OpenAiMagicPlanner
import io.github.jeromekai.latent.core.gallery.Gallery
import io.github.jeromekai.latent.core.model.ModelPreset
import io.github.jeromekai.latent.core.model.Prompt
import io.github.jeromekai.latent.core.queue.JobStore
import io.github.jeromekai.latent.core.store.MagicHistoryStore
import io.github.jeromekai.latent.core.store.ModelPresetStore
import io.github.jeromekai.latent.core.store.PromptStore
import io.github.jeromekai.latent.core.store.SettingsStore
import io.github.jeromekai.latent.data.SecureKeyStore
import io.github.jeromekai.latent.work.GenerationScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import java.io.File

/** What the queue badge and the album badge show. */
data class QueueState(val pending: Int = 0, val failed: Int = 0)

/**
 * Everything the app shares, built once per process. The camera kept the same
 * pieces on its controller; here screens and the background worker reach
 * them through this one object.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** The camera's data/ directory, inside the app's private storage. */
    val root: File = File(appContext.filesDir, "latent").apply { mkdirs() }

    val prompts = PromptStore(File(root, "prompts.json"))
    val models = ModelPresetStore(File(root, "models.json"))
    val magicHistory = MagicHistoryStore(File(root, "magic_history.json"))
    val settings = SettingsStore(File(root, "settings.json"))
    val jobs = JobStore(File(root, "queue"))
    val gallery = Gallery(root)
    val secureKey = SecureKeyStore(appContext)

    private val http = OkHttpClient()
    val editor = OpenAiImageEditor(http)
    val planner = OpenAiMagicPlanner(http)
    val scheduler = GenerationScheduler(appContext, this)

    /** Capture and magic-mode state, shared by every screen. */
    val session by lazy { CameraSession(this) }

    /** Reads the key out of the Keystore, so call it off the main thread. */
    fun endpoint(): ApiEndpoint = ApiEndpoint(settings.value.baseUrl, secureKey.get().orEmpty())

    // ---- live state the UI watches -------------------------------------------

    private val _queue = MutableStateFlow(QueueState())
    val queue: StateFlow<QueueState> = _queue.asStateFlow()

    /** Bumped whenever an image lands or is removed, so the album reloads. */
    private val _galleryVersion = MutableStateFlow(0)
    val galleryVersion: StateFlow<Int> = _galleryVersion.asStateFlow()

    /** Results finished while the album was not open: the camera's sparkle. */
    private val _unseen = MutableStateFlow(0)
    val unseen: StateFlow<Int> = _unseen.asStateFlow()

    @Volatile var albumOpen: Boolean = false
        set(value) {
            field = value
            if (value) _unseen.value = 0
        }

    /**
     * Synchronized so the list-then-publish pair cannot interleave: shots,
     * the worker and the queue sheet all call this from different threads,
     * and a slower reader publishing last would leave the badge stale.
     */
    @Synchronized
    fun refreshQueue() {
        val all = jobs.all()
        _queue.value = QueueState(pending = all.count { !it.failed }, failed = all.count { it.failed })
    }

    fun onGalleryChanged() = _galleryVersion.update { it + 1 }

    fun onGenerationComplete() {
        if (!albumOpen) _unseen.update { it + 1 }
        onGalleryChanged()
        refreshQueue()
    }

    // ---- selection helpers ---------------------------------------------------

    fun selectedPrompt(): Prompt {
        val list = prompts.value
        val wanted = settings.value.selectedPromptId
        return list.firstOrNull { it.id == wanted } ?: list.first()
    }

    fun selectedModel(): ModelPreset {
        val list = models.value
        val wanted = settings.value.selectedModelId
        return list.firstOrNull { it.id == wanted } ?: list.first()
    }

    fun referenceFile(prompt: Prompt): File? =
        prompt.referenceImage.takeIf { it.isNotEmpty() }
            ?.let { gallery.referenceImagePath(it) }
            ?.takeIf { it.isFile }
}
