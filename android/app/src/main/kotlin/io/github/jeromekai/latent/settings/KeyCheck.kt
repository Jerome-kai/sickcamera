package io.github.jeromekai.latent.settings

import io.github.jeromekai.latent.core.api.ApiEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * What asking the server about the key told us. The split between [Rejected]
 * and [Unverified] matters: only a refusal means the key is wrong. A gateway
 * with no /models route, or a dropped connection, proves nothing either way.
 */
sealed interface KeyCheckResult {
    data object Valid : KeyCheckResult

    /** The server answered 401 or 403. */
    data object Rejected : KeyCheckResult

    /** The key holds a character no key has (see [isSendableKey]), so it was never sent. */
    data object Malformed : KeyCheckResult

    /** [reason] is a full sentence for the owner and never contains the key. */
    data class Unverified(val reason: String) : KeyCheckResult
}

private const val CHECK_TIMEOUT_SECONDS = 10L

/**
 * Keys are visible ASCII and nothing else. A line break or a curly quote from a
 * careless paste is not, and OkHttp throws when such a character reaches a
 * header, so it has to be caught before a request is built.
 */
internal fun isSendableKey(key: String): Boolean = key.isNotEmpty() && key.all { it.code in 0x21..0x7E }

private val checkClient: OkHttpClient = OkHttpClient.Builder()
    .callTimeout(CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    // A redirect is not followed: the key goes to the server the owner chose
    // and nowhere else, whatever that server answers.
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

/**
 * Asks the server to list its models, the cheapest call every OpenAI-compatible
 * endpoint has. Costs nothing and generates nothing. OpenAI answers it only for
 * a good key, but some gateways list models to anyone, so [KeyCheckResult.Valid]
 * from a different server proves less; the screen says so.
 */
suspend fun checkKey(endpoint: ApiEndpoint): KeyCheckResult = withContext(Dispatchers.IO) {
    val key = endpoint.apiKey.trim()
    if (key.isEmpty()) return@withContext KeyCheckResult.Unverified("There is no key to check yet.")

    // Checked before the request is built: a header OkHttp refuses would throw out of here.
    if (!isSendableKey(key)) return@withContext KeyCheckResult.Malformed

    val base = endpoint.resolvedBase
    // Never send a key in the clear, even if a hand-edited settings file asks to.
    if (!base.startsWith("https://", ignoreCase = true)) {
        return@withContext KeyCheckResult.Unverified("The server address must start with https://.")
    }
    val url = "$base/models".toHttpUrlOrNull()
        ?: return@withContext KeyCheckResult.Unverified("The server address is not a valid web address.")

    val request = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $key")
        .get()
        .build()

    try {
        when (val status = statusOf(checkClient.newCall(request))) {
            in 200..299 -> KeyCheckResult.Valid
            401, 403 -> KeyCheckResult.Rejected
            404, 405 -> KeyCheckResult.Unverified(
                "This server has no /models route to ask, so it cannot say whether the key works.",
            )
            else -> KeyCheckResult.Unverified("The server answered with HTTP $status.")
        }
    } catch (error: IOException) {
        // The exception's own message can echo the address; describe it
        // ourselves so nothing odd ever lands in the UI.
        KeyCheckResult.Unverified(
            when (error) {
                // callTimeout surfaces as a plain InterruptedIOException; socket timeouts extend it.
                is InterruptedIOException -> "The server did not answer within $CHECK_TIMEOUT_SECONDS seconds."
                is UnknownHostException -> "The server could not be found. Check your connection and the server address."
                is SSLException -> "A secure connection to the server could not be made."
                else -> "The server could not be reached."
            },
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // A button that only asks a question must never take the app down.
        KeyCheckResult.Unverified("The check could not be completed.")
    }
}

/**
 * Only the status line is needed, so the response is closed on arrival
 * instead of being handed back to be leaked if the caller has gone.
 */
private suspend fun statusOf(call: Call): Int = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            response.use { if (continuation.isActive) continuation.resume(it.code) }
        }
    })
}
