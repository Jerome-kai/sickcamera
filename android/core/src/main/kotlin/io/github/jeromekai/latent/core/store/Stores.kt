package io.github.jeromekai.latent.core.store

import io.github.jeromekai.latent.core.model.Defaults
import io.github.jeromekai.latent.core.model.MagicHistoryEntry
import io.github.jeromekai.latent.core.model.ModelPreset
import io.github.jeromekai.latent.core.model.Normalize
import io.github.jeromekai.latent.core.model.Prompt
import io.github.jeromekai.latent.core.model.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * One JSON file, held in memory as a StateFlow so the UI redraws when it
 * changes. Every value passes through [decode] on the way in AND out, so what
 * reaches disk is always normalised, however the caller built it.
 *
 * [update] holds the lock across read-modify-write. The camera's
 * MagicHistoryStore did not, and a promote racing a new entry dropped one.
 */
class JsonStateStore<T>(
    private val file: File,
    private val encode: (T) -> JsonElement,
    private val decode: (JsonElement?) -> T,
    onCorrupt: (File, Exception) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private val _state: MutableStateFlow<T>

    init {
        val raw = JsonFiles.readOrNull(file, onCorrupt)
        val initial = decode(raw)
        if (raw == null) {
            // Seed the file, as the camera did on first run.
            runCatching { JsonFiles.writeAtomic(file, encode(initial)) }
        }
        _state = MutableStateFlow(initial)
    }

    val state: StateFlow<T> = _state.asStateFlow()

    val value: T get() = _state.value

    fun update(transform: (T) -> T): T = synchronized(lock) {
        val next = decode(encode(transform(_state.value)))
        JsonFiles.writeAtomic(file, encode(next))
        _state.value = next
        next
    }

    fun replace(value: T): T = update { value }
}

class PromptStore(file: File) {
    private val store = JsonStateStore(file, Normalize::promptsToJson, { Normalize.prompts(it) })
    val prompts: StateFlow<List<Prompt>> = store.state
    val value: List<Prompt> get() = store.value

    fun replaceAll(prompts: List<Prompt>): List<Prompt> = store.replace(prompts)

    fun update(transform: (List<Prompt>) -> List<Prompt>): List<Prompt> = store.update(transform)

    /** New prompts go on top, as in the camera's web editor. */
    fun add(title: String = Normalize.DEFAULT_NEW_PROMPT_TITLE, body: String = Normalize.DEFAULT_NEW_PROMPT_BODY): Prompt {
        var created: Prompt? = null
        store.update { current ->
            val id = freshId(current.map { it.id }.toSet())
            val prompt = Prompt(id = id, title = title, body = body)
            created = prompt
            listOf(prompt) + current
        }
        return store.value.first { it.id == created!!.id }
    }

    /** Refuses to delete the last prompt: the shutter always needs one. */
    fun remove(id: String): Boolean {
        var removed = false
        store.update { current ->
            if (current.size <= 1 || current.none { it.id == id }) current
            else current.filterNot { it.id == id }.also { removed = true }
        }
        return removed
    }

    fun edit(id: String, transform: (Prompt) -> Prompt): Prompt? {
        store.update { current -> current.map { if (it.id == id) transform(it) else it } }
        return store.value.firstOrNull { it.id == id }
    }

    private fun freshId(taken: Set<String>): String {
        var index = taken.size + 1
        while ("prompt-$index" in taken) index += 1
        return "prompt-$index"
    }
}

class ModelPresetStore(file: File, private val defaults: () -> List<ModelPreset> = Defaults::modelPresets) {
    private val store = JsonStateStore(
        file,
        Normalize::modelPresetsToJson,
        { Normalize.modelPresets(it, defaults()) },
    )
    val presets: StateFlow<List<ModelPreset>> = store.state
    val value: List<ModelPreset> get() = store.value

    fun replaceAll(presets: List<ModelPreset>): List<ModelPreset> = store.replace(presets)

    fun update(transform: (List<ModelPreset>) -> List<ModelPreset>): List<ModelPreset> = store.update(transform)
}

class MagicHistoryStore(file: File) {
    private val store = JsonStateStore(file, Normalize::magicHistoryToJson, Normalize::magicHistory)
    val entries: StateFlow<List<MagicHistoryEntry>> = store.state
    val value: List<MagicHistoryEntry> get() = store.value

    /** Newest first. Returns the entry as normalised (its id may change). */
    fun add(entry: MagicHistoryEntry): MagicHistoryEntry {
        val updated = store.update { listOf(entry) + it }
        return updated.first()
    }

    fun markPromoted(entryId: String, promptId: String?): List<MagicHistoryEntry> = store.update { current ->
        current.map { if (it.id == entryId) it.copy(promotedPromptId = promptId?.trim()?.ifEmpty { null }) else it }
    }

    fun remove(entryId: String): List<MagicHistoryEntry> = store.update { current -> current.filterNot { it.id == entryId } }
}

class SettingsStore(file: File) {
    private val store = JsonStateStore(file, Normalize::settingsToJson, Normalize::settings)
    val settings: StateFlow<Settings> = store.state
    val value: Settings get() = store.value

    fun update(transform: (Settings) -> Settings): Settings = store.update(transform)
}
