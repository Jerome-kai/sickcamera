package io.github.jeromekai.latent.core.api

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    /** OkHttp refuses a timeout past Int.MAX_VALUE milliseconds; a day is already absurd. */
    private const val MAX_TIMEOUT_SECONDS = 24 * 60 * 60

    // "https:/host" and "http:///host" both parse as a real host in HttpUrl, so an address
    // typed wrong would be sent to a stranger's server instead of failing as a settings error.
    private val HTTP_ADDRESS = Regex("^https?://[^/?#\\s]+", RegexOption.IGNORE_CASE)
    val HTTPS_ADDRESS = Regex("^https://[^/?#\\s]+", RegexOption.IGNORE_CASE)

    fun client(base: OkHttpClient?, timeoutSeconds: Int): OkHttpClient {
        // OkHttp reads 0 as "no timeout at all" and throws for negative or huge values; a stray
        // setting must neither hang a background worker forever nor crash it.
        val seconds = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS).toLong()
        return (base ?: OkHttpClient()).newBuilder()
            // callTimeout bounds the WHOLE exchange, upload and download
            // included; the per-phase timeouts alone would let a slow drip
            // run far past the configured limit.
            .callTimeout(seconds, TimeUnit.SECONDS)
            .readTimeout(seconds, TimeUnit.SECONDS)
            .writeTimeout(seconds, TimeUnit.SECONDS)
            // OkHttp strips Authorization when a redirect changes host, so the
            // key cannot follow a redirect to someone else's server -- the
            // leak the camera's urllib path had.
            .followRedirects(true)
            // ...but it would still follow https -> plain http, sending the photo (and, on the
            // same host, the key) unencrypted. Redirects may not change the scheme.
            .followSslRedirects(false)
            .build()
    }

    /** "Bearer <key>", or a permanent error when the key cannot travel in a header at all. */
    fun bearer(apiKey: String): String {
        val key = apiKey.trim()
        // A pasted line break, "..." character or zero-width space makes OkHttp throw
        // IllegalArgumentException, which the worker would retry for hours as a network blip.
        if (key.any { it.code !in 0x21..0x7E }) {
            throw ImageGenException(
                "The API key contains a space, line break or non-ASCII character. Paste it again in Settings.",
                retryable = false,
            )
        }
        return "Bearer $key"
    }

    /** [path] under the endpoint's base address, or a permanent error when that address is not http(s). */
    fun url(endpoint: ApiEndpoint, path: String): HttpUrl {
        val base = endpoint.resolvedBase
        // The address itself is never echoed: it may carry credentials.
        return (if (HTTP_ADDRESS.containsMatchIn(base)) (base + path).toHttpUrlOrNull() else null)
            ?: throw ImageGenException("The base URL is not a valid http or https address. Check it in Settings.", retryable = false)
    }

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
                return await(client.newCall(request)) { readOrFail(it, what) }
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

    /**
     * Enqueue [call] and run [handle] on the response INSIDE the callback, on OkHttp's own thread.
     * Reading the body after resuming would happen where cancellation can no longer reach it: the
     * cancel handler below only exists while the coroutine is suspended, so a body still trickling
     * in would keep a worker blocked until the call timeout.
     */
    suspend fun <T> await(call: Call, handle: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(runCatching { response.use(handle) })
            }
        })
    }

    private fun readOrFail(response: Response, what: String): String {
        val body = response.body?.string().orEmpty()
        if (response.isSuccessful) return body
        val code = response.code
        throw ImageGenException(
            "$what failed: HTTP $code: ${errorDetail(body)}",
            retryable = isRetryableStatus(code),
            statusCode = code,
        )
    }

    /** openai-python's retry set: request timeout, conflict, rate limit and every server error. */
    fun isRetryableStatus(code: Int): Boolean = code == 408 || code == 409 || code == 429 || code >= 500

    /**
     * The text of a JSON string, or null for anything else. JsonNull.content is the four letters
     * "null" (valid base64, and a plausible host), so reading `.content` straight off a field the
     * provider sent as null turned it into data.
     */
    fun stringOrNull(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The provider's own error message when it sends OpenAI-style JSON. */
    fun errorDetail(body: String): String {
        val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull()
        val message = ((parsed as? JsonObject)?.get("error") as? JsonObject)?.get("message")
        val text = (message as? JsonPrimitive)?.contentOrNull ?: body
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

    /**
     * _decode_data_url: the bytes of a `data:...;base64,...` URL, or null when it is no such URL,
     * is malformed, or carries nothing. A link that merely contains "base64," is not a data URL.
     */
    fun decode(url: String?): ByteArray? {
        if (url == null || !url.startsWith("data:", ignoreCase = true)) return null
        val comma = url.indexOf(',')
        if (comma < 0 || !url.substring(0, comma).endsWith("base64", ignoreCase = true)) return null
        return decodeBase64(url.substring(comma + 1))
    }

    /**
     * Strict base64 with line wrapping tolerated. Python's b64decode, and Java's MIME decoder,
     * silently skip every character outside the alphabet, which turned "!!!!" into an empty
     * "image" and a stray character into quietly corrupted bytes.
     */
    internal fun decodeBase64(payload: String): ByteArray? {
        val compact = payload.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return null
        return try {
            Base64.getDecoder().decode(compact).takeIf { it.isNotEmpty() }
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
