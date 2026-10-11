package io.github.jeromekai.latent.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Port of the Python camera's config.py normalisation. These functions take
 * whatever is on disk -- hand-edited, truncated, from an older version -- and
 * return clean values, never throwing. That tolerance is the point: a bad
 * prompts.json must degrade to defaults, not stop the app from starting.
 */
object Normalize {
    const val PROMPT_TITLE_MAX_LENGTH = 22
    const val CAMERA_USERNAME_MAX_LENGTH = 32
    const val MODEL_LABEL_MAX_LENGTH = 18
    const val REFERENCE_IMAGE_MAX_LENGTH = 128
    const val DEFAULT_NEW_PROMPT_TITLE = "New Prompt"
    const val DEFAULT_NEW_PROMPT_BODY = "Describe the edit you want."

    private val NON_ID_CHARS = Regex("[^a-z0-9]+")
    // (?U): Python's \s matches Unicode whitespace; Java's matches only ASCII.
    private val WHITESPACE = Regex("(?U)\\s+")
    private val NON_USERNAME_CHARS = Regex("[^a-z0-9._-]+")
    private val NON_FILENAME_CHARS = Regex("[^A-Za-z0-9._-]+")

    // ---- scalars -----------------------------------------------------------

    /** Python's str(value or ""): null, false, 0 and "" all become "". */
    internal fun text(value: JsonElement?): String {
        if (value == null || value is JsonNull) return ""
        if (value !is JsonPrimitive) return value.toString()
        if (value.isString) return value.content
        val raw = value.content
        return if (raw == "false" || raw == "0" || raw == "0.0") "" else raw
    }

    /** Truncate by code points, as Python slicing does, never mid-emoji. */
    internal fun takeCodePoints(value: String, count: Int): String {
        if (value.codePointCount(0, value.length) <= count) return value
        return value.substring(0, value.offsetByCodePoints(0, count))
    }

    /** _normalize_prompt_id / _normalize_magic_history_id. Mutates [used]. */
    fun uniqueId(value: JsonElement?, used: MutableSet<String>, fallbackPrefix: String, index: Int): String {
        var raw = text(value).trim().lowercase().replace(NON_ID_CHARS, "-").trim('-')
        if (raw.isEmpty()) raw = "$fallbackPrefix-$index"
        var candidate = raw
        var suffix = 2
        while (candidate in used) {
            candidate = "$raw-$suffix"
            suffix += 1
        }
        used += candidate
        return candidate
    }

    fun promptTitle(value: JsonElement?): String {
        val cleaned = text(value).trim().ifEmpty { DEFAULT_NEW_PROMPT_TITLE }
        return takeCodePoints(cleaned, PROMPT_TITLE_MAX_LENGTH).trim().ifEmpty { DEFAULT_NEW_PROMPT_TITLE }
    }

    fun promptBody(value: JsonElement?): String = text(value).trim().ifEmpty { DEFAULT_NEW_PROMPT_BODY }

    fun cameraUsername(value: String): String {
        val cleaned = value.trim().lowercase()
            .replace(WHITESPACE, "-")
            .replace(NON_USERNAME_CHARS, "")
        return takeCodePoints(cleaned, CAMERA_USERNAME_MAX_LENGTH).trim('.', '_', '-')
    }

    /**
     * A prompt's reference image is a bare filename inside the reference
     * directory. Only the basename survives, so a hand-edited file cannot point
     * the app anywhere else. Unlike the Python original this re-checks for "."
     * and ".." AFTER stripping characters: "..%" used to sanitise into "..".
     */
    fun referenceImage(value: JsonElement?): String {
        val basename = text(value).trim().replace('\\', '/').substringAfterLast('/')
        if (basename in setOf("", ".", "..")) return ""
        val sanitised = takeCodePoints(basename.replace(NON_FILENAME_CHARS, ""), REFERENCE_IMAGE_MAX_LENGTH)
        return if (sanitised in setOf("", ".", "..")) "" else sanitised
    }

    fun modelLabel(value: JsonElement?, fallback: String): String {
        val cleaned = text(value).split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")
            .ifEmpty { fallback }
        return takeCodePoints(cleaned, MODEL_LABEL_MAX_LENGTH).trim().ifEmpty { fallback }
    }

    // ---- prompts -----------------------------------------------------------

    /**
     * normalize_prompt_entries. Accepts the list form on disk, and the older
     * {id: body} / {id: {title, body}} map forms the camera also read.
     */
    fun prompts(raw: JsonElement?, ensureDefaultsWhenEmpty: Boolean = true): List<Prompt> {
        val items: List<Triple<JsonElement?, JsonElement?, Pair<JsonElement?, JsonElement?>>> = when (raw) {
            is JsonArray -> raw.filterIsInstance<JsonObject>().map {
                Triple(it["id"], it["title"], it["body"] to it["reference_image"])
            }
            is JsonObject -> raw.entries.map { (key, value) ->
                if (value is JsonObject) {
                    Triple(value["id"] ?: JsonPrimitive(key), value["title"], value["body"] to value["reference_image"])
                } else {
                    Triple(JsonPrimitive(key), null, value to null)
                }
            }
            else -> emptyList()
        }
        val used = mutableSetOf<String>()
        val cleaned = items.mapIndexed { index, (id, title, rest) ->
            Prompt(
                id = uniqueId(id, used, "prompt", index + 1),
                title = promptTitle(title),
                body = promptBody(rest.first),
                referenceImage = referenceImage(rest.second),
            )
        }
        return if (cleaned.isEmpty() && ensureDefaultsWhenEmpty) Defaults.prompts() else cleaned
    }

    fun promptsToJson(prompts: List<Prompt>): JsonElement = buildJsonArray {
        for (prompt in prompts) {
            add(buildJsonObject {
                put("id", prompt.id)
                put("title", prompt.title)
                put("body", prompt.body)
                // Written only when set, matching the camera's prompts.json.
                if (prompt.referenceImage.isNotEmpty()) put("reference_image", prompt.referenceImage)
            })
        }
    }

    // ---- model presets -----------------------------------------------------

    /** normalize_model_presets: drop entries with no model; never empty. */
    fun modelPresets(raw: JsonElement?, defaults: List<ModelPreset> = Defaults.modelPresets()): List<ModelPreset> {
        if (raw !is JsonArray) return defaults
        val used = mutableSetOf<String>()
        val cleaned = raw.mapIndexedNotNull { index, entry ->
            if (entry !is JsonObject) return@mapIndexedNotNull null
            val model = text(entry["model"]).trim()
            if (model.isEmpty()) return@mapIndexedNotNull null
            ModelPreset(
                id = uniqueId(entry["id"], used, "prompt", index + 1),
                label = modelLabel(entry["label"], model.substringAfterLast('/')),
                model = model,
                api = ImageApi.fromWire(text(entry["api"])),
            )
        }
        return cleaned.ifEmpty { defaults }
    }

    fun modelPresetsToJson(presets: List<ModelPreset>): JsonElement = buildJsonArray {
        for (preset in presets) {
            add(buildJsonObject {
                put("id", preset.id)
                put("label", preset.label)
                put("model", preset.model)
                put("api", preset.api.wire)
            })
        }
    }

    // ---- magic history -----------------------------------------------------

    fun magicHistory(raw: JsonElement?): List<MagicHistoryEntry> {
        if (raw !is JsonArray) return emptyList()
        val used = mutableSetOf<String>()
        return raw.mapIndexedNotNull { index, entry ->
            if (entry !is JsonObject) return@mapIndexedNotNull null
            MagicHistoryEntry(
                id = uniqueId(entry["id"], used, "magic", index + 1),
                createdAt = text(entry["created_at"]).trim(),
                title = promptTitle(entry["title"]),
                body = promptBody(entry["body"]),
                referenceCapturePath = text(entry["reference_capture_path"]).trim().ifEmpty { null },
                promotedPromptId = text(entry["promoted_prompt_id"]).trim().ifEmpty { null },
            )
        }
    }

    fun magicHistoryToJson(entries: List<MagicHistoryEntry>): JsonElement = buildJsonArray {
        for (entry in entries) {
            add(buildJsonObject {
                put("id", entry.id)
                put("created_at", entry.createdAt)
                put("title", entry.title)
                put("body", entry.body)
                put("reference_capture_path", entry.referenceCapturePath?.let(::JsonPrimitive) ?: JsonNull)
                put("promoted_prompt_id", entry.promotedPromptId?.let(::JsonPrimitive) ?: JsonNull)
            })
        }
    }

    // ---- settings ----------------------------------------------------------

    private fun clamp(value: JsonElement?, default: Int, min: Int, max: Int): Int {
        val parsed = (value as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }
        return (parsed ?: default).coerceIn(min, max)
    }

    private fun flag(value: JsonElement?, default: Boolean): Boolean {
        val primitive = value as? JsonPrimitive ?: return default
        primitive.booleanOrNull?.let { return it }
        return when (primitive.contentOrNull?.trim()?.lowercase()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> default
        }
    }

    private fun size(value: JsonElement?, default: String): String {
        val match = Regex("^\\s*(\\d{2,5})\\s*x\\s*(\\d{2,5})\\s*$").find(text(value)) ?: return default
        return "${match.groupValues[1]}x${match.groupValues[2]}"
    }

    fun settings(raw: JsonElement?): Settings {
        val obj = raw as? JsonObject ?: JsonObject(emptyMap())
        val d = Settings()
        return Settings(
            theme = AppTheme.fromWire(text(obj["app_background_theme"]).ifEmpty { null }),
            cameraUsername = cameraUsername(text(obj["camera_username"])),
            tutorialSeen = flag(obj["tutorial_seen"], d.tutorialSeen),
            baseUrl = text(obj["base_url"]).trim().trimEnd('/'),
            quality = text(obj["quality"]).trim().lowercase()
                .takeIf { it in setOf("low", "medium", "high", "auto") } ?: d.quality,
            size = size(obj["size"], d.size),
            outputFormat = OutputFormat.fromWire(text(obj["output_format"]).ifEmpty { null }),
            outputCompression = clamp(obj["output_compression"], d.outputCompression, 0, 100),
            imageTimeoutSeconds = clamp(obj["image_timeout_seconds"], d.imageTimeoutSeconds, 10, 600),
            inputWidth = clamp(obj["input_width"], d.inputWidth, 256, 4096),
            inputHeight = clamp(obj["input_height"], d.inputHeight, 256, 4096),
            magicEnabled = flag(obj["magic_mode_enabled"], d.magicEnabled),
            magicApi = MagicApi.fromWire(text(obj["magic_mode_api"]).ifEmpty { null }),
            magicModel = text(obj["magic_mode_model"]).trim().ifEmpty { d.magicModel },
            magicTimeoutSeconds = clamp(obj["magic_mode_timeout_seconds"], d.magicTimeoutSeconds, 5, 300),
            maxAttempts = clamp(obj["queue_max_attempts"], d.maxAttempts, 0, 100),
            maxPendingGenerations = clamp(obj["max_pending_generations"], d.maxPendingGenerations, 0, 1000),
            selectedPromptId = text(obj["selected_prompt_id"]).trim(),
            selectedModelId = text(obj["selected_model_id"]).trim(),
            saveToPhoneGallery = flag(obj["save_to_phone_gallery"], d.saveToPhoneGallery),
            volumeKeyShutter = flag(obj["volume_key_shutter"], d.volumeKeyShutter),
        )
    }

    fun settingsToJson(s: Settings): JsonElement = buildJsonObject {
        put("app_background_theme", s.theme.wire)
        put("camera_username", s.cameraUsername)
        put("tutorial_seen", s.tutorialSeen)
        put("base_url", s.baseUrl)
        put("quality", s.quality)
        put("size", s.size)
        put("output_format", s.outputFormat.wire)
        put("output_compression", s.outputCompression)
        put("image_timeout_seconds", s.imageTimeoutSeconds)
        put("input_width", s.inputWidth)
        put("input_height", s.inputHeight)
        put("magic_mode_enabled", s.magicEnabled)
        put("magic_mode_api", s.magicApi.wire)
        put("magic_mode_model", s.magicModel)
        put("magic_mode_timeout_seconds", s.magicTimeoutSeconds)
        put("queue_max_attempts", s.maxAttempts)
        put("max_pending_generations", s.maxPendingGenerations)
        put("selected_prompt_id", s.selectedPromptId)
        put("selected_model_id", s.selectedModelId)
        put("save_to_phone_gallery", s.saveToPhoneGallery)
        put("volume_key_shutter", s.volumeKeyShutter)
    }

    // ---- generation metadata ----------------------------------------------

    fun metadata(raw: JsonElement?): GenerationMetadata {
        val obj = raw as? JsonObject ?: return GenerationMetadata()
        fun opt(key: String) = text(obj[key]).trim().ifEmpty { null }
        val refs = (obj["reference_paths"] as? JsonArray)?.map { text(it).trim() }?.filter { it.isNotEmpty() }
        return GenerationMetadata(
            capturePath = opt("capture_path"),
            promptId = opt("prompt_id"),
            promptTitle = opt("prompt_title"),
            promptBody = opt("prompt_body"),
            model = opt("model"),
            api = opt("api"),
            createdAt = opt("created_at"),
            magicReferencePath = opt("magic_reference_path"),
            magicHistoryId = opt("magic_history_id"),
            variant = opt("variant"),
            sourceGeneratedPath = opt("source_generated_path"),
            referencePaths = refs.orEmpty(),
        )
    }

    fun metadataToJson(m: GenerationMetadata): JsonElement = buildJsonObject {
        fun opt(key: String, value: String?) { if (!value.isNullOrEmpty()) put(key, value) }
        opt("capture_path", m.capturePath)
        opt("prompt_id", m.promptId)
        opt("prompt_title", m.promptTitle)
        opt("prompt_body", m.promptBody)
        opt("model", m.model)
        opt("api", m.api)
        opt("created_at", m.createdAt)
        opt("magic_reference_path", m.magicReferencePath)
        opt("magic_history_id", m.magicHistoryId)
        opt("variant", m.variant)
        opt("source_generated_path", m.sourceGeneratedPath)
        if (m.referencePaths.isNotEmpty()) {
            put("reference_paths", buildJsonArray { m.referencePaths.forEach { add(JsonPrimitive(it)) } })
        }
    }
}
