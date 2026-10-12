package io.github.jeromekai.latent.core.api

import io.github.jeromekai.latent.core.model.ImageApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** One edit: the photo, the instruction, and how to call the model. */
data class EditRequest(
    val source: ImageInput,
    val prompt: String,
    val references: List<ImageInput> = emptyList(),
    val model: String,
    val api: ImageApi,
    val quality: String = "low",
    val size: String = "1536x1024",
    /** jpeg, png or webp: what /images/edits is asked to return. */
    val outputFormat: String = "jpeg",
    val outputCompression: Int = 85,
    val timeoutSeconds: Int = 90,
)

/** What the model returned: raw image bytes and their sniffed type. */
data class EditResult(
    val bytes: ByteArray,
    val mimeType: String,
    /** References the chosen api could not carry (generations takes one image). */
    val skippedReferences: Int = 0,
)

interface ImageEditor {
    suspend fun edit(endpoint: ApiEndpoint, request: EditRequest): EditResult
}

/**
 * Port of the camera's OpenAIImageEditor. The wrapper prompts are verbatim:
 * they are what made the camera's results look the way they do.
 */
class OpenAiImageEditor(private val baseClient: OkHttpClient? = null) : ImageEditor {

    companion object {
        /** The largest image accepted from a provider URL. */
        const val MAX_DOWNLOAD_BYTES = 40L * 1024 * 1024

        private val GPT_IMAGE_2 = setOf("gpt-image-2", "gpt-image-2-2026-04-21")

        fun wrapPrompt(prompt: String, hasReferences: Boolean): String = if (hasReferences) {
            "Use the first attached image as the main camera photo to transform. " +
                "Use any additional attached images only as reference images for inspiration. " +
                "Keep the first image recognizable, but apply the user's requested motif or concept " +
                "using the reference image details where helpful. " +
                "User prompt: $prompt"
        } else {
            "Use the attached camera photo as the source image. " +
                "Transform it according to the user's request while keeping the result coherent. " +
                "User prompt: $prompt"
        }

        /** recreate_vertical_from_generated's instruction, verbatim. */
        const val STORY_PROMPT =
            "Edit the attached image into a clean 9:16 vertical composition suitable for a phone screen. " +
                "Zoom out or extend the scene naturally so the original content stays fully visible and centered in the taller frame. " +
                "Preserve the same subject, style, lighting, and overall look. Do not add text, borders, or watermarks."

        const val STORY_SIZE = "1024x1536"
    }

    override suspend fun edit(endpoint: ApiEndpoint, request: EditRequest): EditResult {
        if (endpoint.apiKey.isBlank()) {
            throw ImageGenException("No API key is set. Add one in Settings.", retryable = false)
        }
        val client = Http.client(baseClient, request.timeoutSeconds)
        val fullPrompt = wrapPrompt(request.prompt, request.references.isNotEmpty())
        return when (request.api) {
            ImageApi.EDITS -> viaEdits(client, endpoint, request, fullPrompt)
            ImageApi.CHAT -> viaChat(client, endpoint, request, fullPrompt)
            ImageApi.GENERATIONS -> viaGenerations(client, endpoint, request, fullPrompt)
        }
    }

    // ---- /images/edits ------------------------------------------------------

    private suspend fun viaEdits(
        client: OkHttpClient,
        endpoint: ApiEndpoint,
        request: EditRequest,
        fullPrompt: String,
    ): EditResult {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", request.model)
            .addFormDataPart("prompt", fullPrompt)
            .addFormDataPart("quality", request.quality)
            .addFormDataPart("size", request.size)
            .addFormDataPart("output_format", request.outputFormat)
        // Gateways prefix model names ("openai/gpt-image-2"); compare the bare name.
        if (request.model.substringAfterLast('/') !in GPT_IMAGE_2) {
            form.addFormDataPart("input_fidelity", "low")
        }
        if (request.outputFormat == "jpeg" || request.outputFormat == "webp") {
            form.addFormDataPart("output_compression", request.outputCompression.toString())
        }
        // One image goes as `image`; several as an `image[]` array, the way
        // openai-python encodes a list.
        val images = listOf(request.source) + request.references
        val field = if (images.size > 1) "image[]" else "image"
        for (image in images) {
            form.addFormDataPart(field, image.filename, image.bytes.toRequestBody(image.mimeType.toMediaType()))
        }

        val body = Http.execute(
            client,
            authorized(endpoint, "/images/edits").post(form.build()).build(),
            "images/edits",
        )
        val result = Http.parse(body, "images/edits")
        val data = (result as? JsonObject)?.get("data") as? JsonArray
        for (item in data.orEmpty()) {
            val obj = item as? JsonObject ?: continue
            for (key in listOf("b64_json", "image_base64")) {
                val encoded = Http.stringOrNull(obj[key])
                if (!encoded.isNullOrBlank()) {
                    val bytes = DataUrls.decodeBase64(encoded)
                        ?: throw ImageGenException("images/edits returned undecodable image data.", retryable = true)
                    return EditResult(bytes, ImageSniffer.mimeType(bytes) ?: "image/${request.outputFormat}")
                }
            }
        }
        throw ImageGenException("OpenAI returned no image data.", retryable = true)
    }

    // ---- /chat/completions ---------------------------------------------------

    private suspend fun viaChat(
        client: OkHttpClient,
        endpoint: ApiEndpoint,
        request: EditRequest,
        fullPrompt: String,
    ): EditResult {
        // quality and size are the model's choice on this path; only the
        // prompt and the images travel.
        val payload = buildJsonObject {
            put("model", request.model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", fullPrompt)
                        }
                        for (image in listOf(request.source) + request.references) {
                            addJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") { put("url", DataUrls.build(image)) }
                            }
                        }
                    }
                }
            }
        }
        val body = Http.execute(client, jsonPost(endpoint, "/chat/completions", payload), "chat/completions")
        val bytes = extractChatImage(Http.parse(body, "chat/completions"))
            ?: throw ImageGenException(
                "The model returned no image. Make sure the model is image-generation-capable " +
                    "(e.g. google/gemini-2.5-flash-image on the Vercel AI Gateway) when using the chat api.",
                retryable = false,
            )
        return EditResult(bytes, ImageSniffer.mimeType(bytes) ?: "image/png")
    }

    /**
     * _extract_chat_image_bytes. Gateways return images in message.images as
     * image_url parts; some providers put them in message.content instead.
     */
    internal fun extractChatImage(response: JsonElement): ByteArray? {
        val choices = (response as? JsonObject)?.get("choices") as? JsonArray ?: return null
        for (choice in choices) {
            val message = (choice as? JsonObject)?.get("message") as? JsonObject ?: continue
            val parts = buildList {
                (message["images"] as? JsonArray)?.let { addAll(it) }
                (message["content"] as? JsonArray)?.let { addAll(it) }
            }
            for (part in parts) {
                val imageUrl = (part as? JsonObject)?.get("image_url") ?: continue
                val url = Http.stringOrNull(if (imageUrl is JsonObject) imageUrl["url"] else imageUrl)
                DataUrls.decode(url)?.let { return it }
            }
        }
        return null
    }

    // ---- /images/generations -------------------------------------------------

    private suspend fun viaGenerations(
        client: OkHttpClient,
        endpoint: ApiEndpoint,
        request: EditRequest,
        fullPrompt: String,
    ): EditResult {
        // This endpoint takes exactly one image, so reference images cannot
        // travel; the caller is told how many were left behind.
        val payload = buildJsonObject {
            put("model", request.model)
            put("prompt", fullPrompt)
            put("image", DataUrls.build(request.source))
        }
        val body = Http.execute(client, jsonPost(endpoint, "/images/generations", payload), "images/generations")
        val bytes = extractGenerationsImage(client, Http.parse(body, "images/generations"))
        return EditResult(bytes, ImageSniffer.mimeType(bytes) ?: "image/png", skippedReferences = request.references.size)
    }

    /**
     * SiliconFlow returns {"images": [{"url": ...}]}; OpenAI-style providers
     * {"data": [{"b64_json": ...}]} or {"data": [{"url": ...}]}.
     */
    private suspend fun extractGenerationsImage(client: OkHttpClient, result: JsonElement): ByteArray {
        val obj = result as? JsonObject
        val items = (obj?.get("images") as? JsonArray)?.takeIf { it.isNotEmpty() }
            ?: (obj?.get("data") as? JsonArray).orEmpty()
        for (item in items) {
            val entry = item as? JsonObject ?: continue
            Http.stringOrNull(entry["b64_json"])?.takeIf { it.isNotBlank() }?.let { encoded ->
                return DataUrls.decodeBase64(encoded)
                    ?: throw ImageGenException("images/generations returned undecodable image data.", retryable = true)
            }
            val url = Http.stringOrNull(entry["url"])?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            DataUrls.decode(url)?.let { return it }
            return download(client, url)
        }
        throw ImageGenException(
            "images/generations returned no image. Make sure the model is image-edit-capable " +
                "(e.g. Qwen/Qwen-Image-Edit-2509 on SiliconFlow) when using the generations api.",
            retryable = false,
        )
    }

    /**
     * Fetch an image the provider hosts. https only and size-capped: the URL
     * comes from a third party's response, and the camera's version would
     * fetch any scheme to any host and read it whole into memory.
     */
    private suspend fun download(client: OkHttpClient, url: String): ByteArray {
        // HttpUrl reads "https:/host" as a real host, so the two slashes are checked by hand.
        val parsed = url.trim().takeIf { Http.HTTPS_ADDRESS.containsMatchIn(it) }?.toHttpUrlOrNull()
        if (parsed == null || !parsed.isHttps) {
            throw ImageGenException("The provider returned an image link that is not https: ${url.take(120)}", retryable = false)
        }
        // No Authorization on this request: the key is for the API, not the CDN.
        val request = Request.Builder().url(parsed).get().build()
        // Read inside the callback, like the API calls, so cancelling the coroutine also stops a
        // body that is still trickling in instead of leaving a worker blocked until the timeout.
        return try {
            Http.await(client.newCall(request), ::readDownload)
        } catch (error: IOException) {
            throw ImageGenException("Could not download the generated image: ${error.message}", retryable = true, cause = error)
        }
    }

    private fun readDownload(response: Response): ByteArray {
        if (!response.isSuccessful) {
            throw ImageGenException(
                "Could not download the generated image: HTTP ${response.code}",
                retryable = Http.isRetryableStatus(response.code),
                statusCode = response.code,
            )
        }
        val body = response.body ?: throw ImageGenException("Empty image download.", retryable = true)
        val declared = body.contentLength()
        if (declared > MAX_DOWNLOAD_BYTES) {
            throw ImageGenException("The generated image is too large to download ($declared bytes).", retryable = false)
        }
        // The declared length can be missing (chunked, or gzip, which OkHttp unpacks) or wrong,
        // so the cap is enforced on the bytes actually read.
        val source = body.source()
        if (source.request(MAX_DOWNLOAD_BYTES + 1)) {
            throw ImageGenException("The generated image is too large to download.", retryable = false)
        }
        val bytes = source.buffer.readByteArray()
        if (bytes.isEmpty()) throw ImageGenException("The generated image download was empty.", retryable = true)
        return bytes
    }

    // ---- request helpers ----------------------------------------------------

    private fun authorized(endpoint: ApiEndpoint, path: String): Request.Builder =
        Request.Builder()
            .url(Http.url(endpoint, path))
            .header("Authorization", Http.bearer(endpoint.apiKey))

    private fun jsonPost(endpoint: ApiEndpoint, path: String, payload: JsonElement): Request =
        authorized(endpoint, path)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
}
