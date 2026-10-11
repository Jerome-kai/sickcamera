package io.github.jeromekai.latent.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

object Defaults {
    /**
     * The camera's five built-in prompts, exported byte-for-byte from the
     * Python config.py into resources/latent/default_prompts.json rather than
     * retyped, so a transcription slip cannot quietly change what the AI does.
     */
    fun prompts(): List<Prompt> {
        val stream = Defaults::class.java.getResourceAsStream("/latent/default_prompts.json")
            ?: error("default_prompts.json missing from the core resources")
        val raw: JsonElement = stream.bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()) }
        return Normalize.prompts(raw, ensureDefaultsWhenEmpty = false)
    }

    /** The camera's stock .env: gpt-image-2 against /images/edits. */
    fun modelPresets(): List<ModelPreset> = listOf(
        ModelPreset(id = "default", label = "gpt-image-2", model = "gpt-image-2", api = ImageApi.EDITS),
    )

    /**
     * The three setups the camera's .env.example documented, as one-tap
     * presets: each fixes the base URL, the image model and api mode, and the
     * magic planner, which have to agree with each other to work at all.
     */
    val providerRecipes: List<ProviderRecipe> = listOf(
        ProviderRecipe(
            id = "openai",
            name = "OpenAI",
            note = "api.openai.com with gpt-image-2",
            baseUrl = "",
            model = ModelPreset("gpt-image-2", "gpt-image-2", "gpt-image-2", ImageApi.EDITS),
            magicApi = MagicApi.RESPONSES,
            magicModel = "gpt-4.1-mini",
        ),
        ProviderRecipe(
            id = "vercel",
            name = "Vercel AI Gateway",
            note = "Gemini 2.5 Flash Image through the gateway",
            baseUrl = "https://ai-gateway.vercel.sh/v1",
            model = ModelPreset("gemini-flash", "Gemini Flash", "google/gemini-2.5-flash-image", ImageApi.CHAT),
            magicApi = MagicApi.RESPONSES,
            magicModel = "openai/gpt-4.1-mini",
        ),
        ProviderRecipe(
            id = "siliconflow",
            name = "SiliconFlow",
            note = "Qwen Image Edit, reachable from China without a VPN",
            baseUrl = "https://api.siliconflow.cn/v1",
            model = ModelPreset("qwen-edit", "Qwen Edit", "Qwen/Qwen-Image-Edit-2509", ImageApi.GENERATIONS),
            magicApi = MagicApi.CHAT,
            magicModel = "Qwen/Qwen2.5-VL-32B-Instruct",
        ),
    )

    const val OPENAI_BASE_URL = "https://api.openai.com/v1"
}

data class ProviderRecipe(
    val id: String,
    val name: String,
    val note: String,
    val baseUrl: String,
    val model: ModelPreset,
    val magicApi: MagicApi,
    val magicModel: String,
)
