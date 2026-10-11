package io.github.jeromekai.latent.core.model

/**
 * The data the camera works with. Field names on disk match the Python
 * camera's JSON (snake_case), so a prompts.json can move between the two.
 */

/** Which OpenAI-compatible endpoint turns a photo into an edited image. */
enum class ImageApi(val wire: String) {
    /** POST /images/edits, multipart. api.openai.com with gpt-image models. */
    EDITS("edits"),

    /** POST /chat/completions with image parts. Gateways such as Vercel's. */
    CHAT("chat"),

    /** POST /images/generations with an `image` field. SiliconFlow, Volcano Ark. */
    GENERATIONS("generations");

    companion object {
        fun fromWire(value: String?): ImageApi =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() } ?: EDITS
    }
}

/** Which endpoint the magic-prompt planner uses to look at a photo. */
enum class MagicApi(val wire: String) {
    RESPONSES("responses"),
    CHAT("chat");

    companion object {
        fun fromWire(value: String?): MagicApi =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() } ?: RESPONSES
    }
}

data class Prompt(
    val id: String,
    val title: String,
    val body: String,
    /** Bare filename inside the reference-image directory, or "" for none. */
    val referenceImage: String = "",
)

/** One position on the model wheel. */
data class ModelPreset(
    val id: String,
    val label: String,
    val model: String,
    val api: ImageApi,
)

/** A prompt the magic planner invented from a photo. */
data class MagicHistoryEntry(
    val id: String,
    val createdAt: String,
    val title: String,
    val body: String,
    /** The photo it was invented from; sent along as a reference image. */
    val referenceCapturePath: String?,
    /** Set once the owner keeps it as a regular prompt. */
    val promotedPromptId: String?,
)

enum class AppTheme(val wire: String) {
    AQUA("aqua"),
    SILVER("silver"),
    LAVENDER("lavender"),
    MINT("mint"),
    SUNSET("sunset");

    companion object {
        fun fromWire(value: String?): AppTheme =
            entries.firstOrNull { it.wire == value?.trim() } ?: AQUA
    }
}

enum class OutputFormat(val wire: String, val extension: String, val mimeType: String) {
    JPEG("jpeg", ".jpg", "image/jpeg"),
    PNG("png", ".png", "image/png"),
    WEBP("webp", ".webp", "image/webp");

    companion object {
        fun fromWire(value: String?): OutputFormat =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() } ?: JPEG
    }
}

/**
 * Everything the camera's .env and settings.json held that still means
 * something on a phone. Hardware knobs (GPIO pins, SPI clock, hotspot) are
 * gone; the API key is deliberately NOT here -- it lives in encrypted
 * storage on the device, never in a plain JSON file.
 */
data class Settings(
    val theme: AppTheme = AppTheme.AQUA,
    val cameraUsername: String = "",
    val tutorialSeen: Boolean = false,
    /** Empty means https://api.openai.com/v1. */
    val baseUrl: String = "",
    val quality: String = "low",
    /** Landscape size; portrait photos use the same size turned on its side. */
    val size: String = "1536x1024",
    val outputFormat: OutputFormat = OutputFormat.JPEG,
    val outputCompression: Int = 85,
    val imageTimeoutSeconds: Int = 90,
    /** Longer edge x shorter edge the photo is scaled to before upload. */
    val inputWidth: Int = 1280,
    val inputHeight: Int = 960,
    val magicEnabled: Boolean = true,
    val magicApi: MagicApi = MagicApi.RESPONSES,
    val magicModel: String = "gpt-4.1-mini",
    val magicTimeoutSeconds: Int = 30,
    /** Generation attempts before giving up on a photo. 0 retries forever. */
    val maxAttempts: Int = 12,
    /** Refuse new shots while this many are queued. 0 means no limit. */
    val maxPendingGenerations: Int = 0,
    val selectedPromptId: String = "",
    val selectedModelId: String = "",
    /** Copy finished images into the phone's Pictures/Latent folder. */
    val saveToPhoneGallery: Boolean = true,
    /** Volume keys fire the shutter while the camera screen is open. */
    val volumeKeyShutter: Boolean = true,
)

/** What a finished image remembers about how it was made. */
data class GenerationMetadata(
    val capturePath: String? = null,
    val promptId: String? = null,
    val promptTitle: String? = null,
    val promptBody: String? = null,
    val model: String? = null,
    val api: String? = null,
    val createdAt: String? = null,
    val magicReferencePath: String? = null,
    val magicHistoryId: String? = null,
    /** "story" for a 9:16 recreation. */
    val variant: String? = null,
    val sourceGeneratedPath: String? = null,
    val referencePaths: List<String> = emptyList(),
)
