package io.github.jeromekai.latent.core.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * A failed model call. [retryable] separates "try again later" (network,
 * rate limit, server error) from "this will never work" (bad key, bad model
 * name, refused prompt), so the queue can stop at once on the second kind
 * instead of retrying it for hours.
 */
class ImageGenException(
    message: String,
    val retryable: Boolean,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Where to send requests, and the key to send with them. */
data class ApiEndpoint(
    /** Empty means https://api.openai.com/v1. */
    val baseUrl: String,
    val apiKey: String,
) {
    val resolvedBase: String
        get() = baseUrl.trim().trimEnd('/').ifEmpty { "https://api.openai.com/v1" }
}

/** A photo or reference image as bytes, the way it goes over the wire. */
data class ImageInput(
    val bytes: ByteArray,
    val filename: String,
    val mimeType: String,
) {
    override fun equals(other: Any?) = other is ImageInput &&
        filename == other.filename && mimeType == other.mimeType && bytes.contentEquals(other.bytes)

    override fun hashCode() = 31 * (31 * filename.hashCode() + mimeType.hashCode()) + bytes.contentHashCode()
}

internal object Http {
    val json = Json { ignoreUnknownKeys = true }

    fun client(base: OkHttpClient?, timeoutSeconds: Int): OkHttpClient =
        (base ?: OkHttpClient()).newBuilder()
            // callTimeout bounds the WHOLE exchange, upload and download
            // included; the per-phase timeouts alone would let a slow drip
            // run far past the configured limit.
            .callTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            // OkHttp strips Authorization when a redirect changes host, so the
            // key cannot follow a redirect to someone else's server -- the
            // leak the camera's urllib path had.
            .followRedirects(true)
            .build()

    /**
     * Run a request, retrying once on the failures openai-python retries
     * (connection errors, 408, 409, 429, 5xx) -- the camera used
     * max_retries=1. Returns the body text of a 2xx response.
     *
     * The call is cancelled with the coroutine. WorkManager stops a job when
     * its constraints fail (the network drops), and a blocking execute()
     * would otherwise keep uploading in the background regardless.
     */
    suspend fun execute(client: OkHttpClient, request: Request, what: String, maxRetries: Int = 1): String {
        var lastError: ImageGenException? = null
        for (attempt in 0..maxRetries) {
            if (attempt > 0) delay(500L * attempt)
            try {
                val response = client.newCall(request).await()
                return withContext(Dispatchers.IO) { response.use { readOrFail(it, what) } }
            } catch (error: ImageGenException) {
                if (!error.retryable) throw error
                lastError = error
            } catch (error: IOException) {
                lastError = ImageGenException(
                    "$what failed: ${error.message ?: error.javaClass.simpleName}",
                    retryable = true,
                    cause = error,
                )
            }
        }
        throw lastError ?: ImageGenException("$what failed", retryable = true)
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }

    private fun readOrFail(response: Response, what: String): String {
        val body = response.body?.string().orEmpty()
        if (response.isSuccessful) return body
        val code = response.code
        val retryable = code == 408 || code == 409 || code == 429 || code >= 500
        throw ImageGenException(
            "$what failed: HTTP $code: ${errorDetail(body)}",
            retryable = retryable,
            statusCode = code,
        )
    }

    /** The provider's own error message when it sends OpenAI-style JSON. */
    fun errorDetail(body: String): String {
        val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull()
        val message = ((parsed as? JsonObject)?.get("error") as? JsonObject)?.get("message")
        val text = (message as? JsonPrimitive)?.content ?: body
        return text.trim().take(500).ifEmpty { "(empty response)" }
    }

    fun parse(body: String, what: String): JsonElement = try {
        json.parseToJsonElement(body)
    } catch (error: Exception) {
        throw ImageGenException("$what returned something that is not JSON: ${body.take(200)}", retryable = true, cause = error)
    }
}

object DataUrls {
    fun build(image: ImageInput): String =
        "data:${image.mimeType};base64,${Base64.getEncoder().encodeToString(image.bytes)}"

    /** _decode_data_url: bytes after "base64,", or null if absent or malformed. */
    fun decode(url: String?): ByteArray? {
        if (url == null || "base64," !in url) return null
        return try {
            Base64.getMimeDecoder().decode(url.substringAfter("base64,").trim())
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

object ImageSniffer {
    /** Content type from magic bytes; providers do not always say. */
    fun mimeType(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "image/png"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII).startsWith("GIF8") -> "image/gif"
        else -> null
    }
}
