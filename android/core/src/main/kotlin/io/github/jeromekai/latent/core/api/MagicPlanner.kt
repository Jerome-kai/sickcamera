package io.github.jeromekai.latent.core.api

import io.github.jeromekai.latent.core.model.MagicApi
import io.github.jeromekai.latent.core.model.Normalize
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A prompt the planner invented from a photo. */
data class MagicPrompt(val title: String, val prompt: String)

interface MagicPromptPlanner {
    suspend fun plan(endpoint: ApiEndpoint, image: ImageInput, model: String, api: MagicApi, timeoutSeconds: Int): MagicPrompt
}

/** Port of OpenAIMagicPromptPlanner; instruction text verbatim. */
class OpenAiMagicPlanner(
    private val baseClient: OkHttpClient? = null,
    private val titleMaxLength: Int = Normalize.PROMPT_TITLE_MAX_LENGTH,
) : MagicPromptPlanner {

    fun instructionText(): String =
        "Look at this camera photo and pick one funny, visually distinct motif, prop, " +
            "detail, gesture, texture, or object that could inspire edits to future photos. " +
            "Return JSON with: " +
            "`title` = a punchy 1-3 word name, max " +
            "$titleMaxLength characters; " +
            "`prompt` = one concise image-edit instruction that tells an image model how to " +
            "apply that motif to another photo while keeping the new photo recognizable and coherent. " +
            "Do not mention JSON. Do not mention camera UI. Do not describe the whole image."

    override suspend fun plan(
        endpoint: ApiEndpoint,
        image: ImageInput,
        model: String,
        api: MagicApi,
        timeoutSeconds: Int,
    ): MagicPrompt {
        if (endpoint.apiKey.isBlank()) {
            throw ImageGenException("No API key is set. Add one in Settings.", retryable = false)
        }
        val client = Http.client(baseClient, timeoutSeconds)
        val dataUrl = DataUrls.build(image)
        val raw = when (api) {
            MagicApi.RESPONSES -> viaResponses(client, endpoint, model, dataUrl)
            MagicApi.CHAT -> viaChat(client, endpoint, model, dataUrl)
        }
        if (raw.isEmpty()) throw ImageGenException("The model returned no magic prompt output.", retryable = true)
        return parse(raw)
    }

    private suspend fun viaResponses(client: OkHttpClient, endpoint: ApiEndpoint, model: String, dataUrl: String): String {
        val payload = buildJsonObject {
            put("model", model)
            putJsonArray("input") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "input_text")
                            put("text", instructionText())
                        }
                        addJsonObject {
                            put("type", "input_image")
                            put("image_url", dataUrl)
                            put("detail", "low")
                        }
                    }
                }
            }
            putJsonObject("text") {
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("name", "magic_prompt")
                    put("strict", true)
                    putJsonObject("schema") {
                        put("type", "object")
                        put("additionalProperties", false)
                        putJsonObject("properties") {
                            putJsonObject("title") { put("type", "string") }
                            putJsonObject("prompt") { put("type", "string") }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("title"))
                            add(JsonPrimitive("prompt"))
                        }
                    }
                }
            }
        }
        val body = Http.execute(client, post(endpoint, "/responses", payload), "responses")
        return responsesOutputText(Http.parse(body, "responses")).trim()
    }

    /**
     * The SDK's response.output_text, rebuilt from the raw HTTP body: every
     * output_text part of every message in `output`, joined. Some gateways
     * also send a top-level output_text, which wins when present.
     */
    internal fun responsesOutputText(response: JsonElement): String {
        val obj = response as? JsonObject ?: return ""
        (obj["output_text"] as? JsonPrimitive)?.content?.let { if (it.isNotBlank()) return it }
        val output = obj["output"] as? JsonArray ?: return ""
        return buildString {
            for (item in output) {
                val content = (item as? JsonObject)?.get("content") as? JsonArray ?: continue
                for (part in content) {
                    val partObj = part as? JsonObject ?: continue
                    if ((partObj["type"] as? JsonPrimitive)?.content != "output_text") continue
                    append((partObj["text"] as? JsonPrimitive)?.content.orEmpty())
                }
            }
        }
    }

    private suspend fun viaChat(client: OkHttpClient, endpoint: ApiEndpoint, model: String, dataUrl: String): String {
        // No structured-output guarantee here, so ask for bare JSON and parse
        // leniently.
        val payload = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put(
                                "text",
                                instructionText() +
                                    " Reply with ONLY the JSON object {\"title\": ..., \"prompt\": ...} " +
                                    "and nothing else — no code fences, no commentary.",
                            )
                        }
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", dataUrl) }
                        }
                    }
                }
            }
        }
        val body = Http.execute(client, post(endpoint, "/chat/completions", payload), "chat/completions")
        val choices = (Http.parse(body, "chat/completions") as? JsonObject)?.get("choices") as? JsonArray
        val message = (choices?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
        return ((message?.get("content") as? JsonPrimitive)?.content ?: "").trim()
    }

    /**
     * _parse_magic_payload: chat models sometimes wrap the JSON in fences or
     * prose, so take the outermost braces.
     */
    fun parse(raw: String): MagicPrompt {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        val candidate = if (start != -1 && end > start) raw.substring(start, end + 1) else raw
        val payload = try {
            Http.json.parseToJsonElement(candidate) as? JsonObject
        } catch (error: Exception) {
            null
        } ?: throw ImageGenException("The model returned invalid magic prompt JSON: ${raw.take(300)}", retryable = true)

        val title = Normalize.text(payload["title"]).trim()
        val prompt = Normalize.text(payload["prompt"]).trim()
        if (title.isEmpty() || prompt.isEmpty()) {
            throw ImageGenException("The model returned an incomplete magic prompt.", retryable = true)
        }
        return MagicPrompt(
            title = Normalize.takeCodePoints(title, titleMaxLength).trim().ifEmpty { "Magic" },
            prompt = prompt,
        )
    }

    private fun post(endpoint: ApiEndpoint, path: String, payload: JsonElement): Request =
        Request.Builder()
            .url(endpoint.resolvedBase + path)
            .header("Authorization", "Bearer ${endpoint.apiKey.trim()}")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
}
