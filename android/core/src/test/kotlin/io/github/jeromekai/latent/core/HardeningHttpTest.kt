package io.github.jeromekai.latent.core

import io.github.jeromekai.latent.core.api.ApiEndpoint
import io.github.jeromekai.latent.core.api.EditRequest
import io.github.jeromekai.latent.core.api.Http
import io.github.jeromekai.latent.core.api.ImageGenException
import io.github.jeromekai.latent.core.api.ImageInput
import io.github.jeromekai.latent.core.api.OpenAiImageEditor
import io.github.jeromekai.latent.core.api.OpenAiMagicPlanner
import io.github.jeromekai.latent.core.model.ImageApi
import io.github.jeromekai.latent.core.model.MagicApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Before
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adversarial tests for the HTTP layer: what a flaky network, a hostile or
 * confused provider and a cancelled WorkManager job do to a model call.
 */
class HardeningHttpTest {
    private lateinit var server: MockWebServer
    private val editor = OpenAiImageEditor()

    private val photo = ImageInput(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3), "photo.jpg", "image/jpeg")
    private val reference = ImageInput(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 9), "ref.jpg", "image/jpeg")
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0)
    private val pngB64 = Base64.getEncoder().encodeToString(png)

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun endpoint(key: String = "sk-test") = ApiEndpoint(server.url("/v1").toString().trimEnd('/'), key)

    private fun request(api: ImageApi = ImageApi.EDITS, model: String = "gpt-image-1", refs: List<ImageInput> = emptyList()) =
        EditRequest(source = photo, prompt = "Turn to cheese", references = refs, model = model, api = api, timeoutSeconds = 10)

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun ok() = json("""{"data":[{"b64_json":"$pngB64"}]}""")

    private fun post(path: String = "/x") =
        Request.Builder().url(server.url(path)).post("{}".toRequestBody("application/json".toMediaType())).build()

    // ---- retry ---------------------------------------------------------------

    @Test
    fun everyTransientStatusIsRetriedExactlyOnce(): Unit = runBlocking {
        // openai-python's retry set: 408, 409, 429 and every 5xx.
        for (code in listOf(408, 409, 429, 500, 502, 504)) {
            val before = server.requestCount
            server.enqueue(MockResponse().setResponseCode(code).setBody("try later"))
            server.enqueue(MockResponse().setBody("fine"))
            assertEquals("fine", Http.execute(OkHttpClient(), post(), "test"), "status $code")
            assertEquals(2, server.requestCount - before, "status $code")
        }
    }

    @Test
    fun everyPermanentStatusFailsOnTheFirstRequest(): Unit = runBlocking {
        for (code in listOf(400, 401, 403, 404, 413, 422)) {
            val before = server.requestCount
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":{"message":"no"}}"""))
            val error = assertFailsWith<ImageGenException>("status $code") { Http.execute(OkHttpClient(), post(), "test") }
            assertFalse(error.retryable, "status $code")
            assertEquals(code, error.statusCode)
            assertEquals(1, server.requestCount - before, "status $code")
        }
    }

    @Test
    fun aDroppedConnectionIsRetried(): Unit = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("second time lucky"))
        assertEquals("second time lucky", Http.execute(OkHttpClient(), post(), "test"))
    }

    @Test
    fun aReplyCutOffMidBodyIsRetried(): Unit = runBlocking {
        server.enqueue(
            MockResponse().setBody("x".repeat(5000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        server.enqueue(MockResponse().setBody("whole"))
        assertEquals("whole", Http.execute(OkHttpClient(), post(), "test"))
    }

    @Test
    fun aConnectionRefusedIsARetryableErrorThatNeverContainsTheKey(): Unit = runBlocking {
        val dead = endpoint(key = "sk-super-secret-key")
        server.shutdown()
        val error = assertFailsWith<ImageGenException> { editor.edit(dead, request()) }
        assertTrue(error.retryable)
        assertFalse("sk-super-secret-key" in error.message.orEmpty())
        assertFalse("sk-super-secret-key" in error.cause?.message.orEmpty())
    }

    @Test
    fun theRetryResendsTheSameMultipartBody(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(ok())
        editor.edit(endpoint(), request(refs = listOf(reference)))

        val first = server.takeRequest().body.readByteArray()
        val second = server.takeRequest().body.readByteArray()
        assertTrue(first.isNotEmpty())
        assertContentEquals(first, second, "a one-shot body would arrive empty or short the second time")
    }

    @Test
    fun noRetriesMeansExactlyOneAttempt(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("never reached"))
        val error = assertFailsWith<ImageGenException> { Http.execute(OkHttpClient(), post(), "test", maxRetries = 0) }
        assertTrue(error.retryable)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aRedirectLoopEndsInAnErrorNotAHang(): Unit = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(307).setHeader("Location", "/v1/images/edits")
        }
        val error = assertFailsWith<ImageGenException> { editor.edit(endpoint(), request()) }
        assertTrue(error.retryable)
        assertTrue(server.requestCount <= 2 * 21, "OkHttp stops at 20 follow-ups per attempt, got ${server.requestCount}")
    }

    @Test
    fun anHttpsApiThatRedirectsToPlainHttpIsNotFollowed(): Unit = runBlocking {
        // Following would send the user's photo (and, on the same host, the key)
        // over an unencrypted connection.
        val tls = HardeningTls.httpsServer()
        try {
            tls.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/steal").toString()))
            server.enqueue(ok())
            val secure = OpenAiImageEditor(HardeningTls.trustingClient())

            val tlsEndpoint = ApiEndpoint(tls.url("/v1").toString().trimEnd('/'), "sk-test")
            assertFailsWith<ImageGenException> { secure.edit(tlsEndpoint, request()) }

            assertEquals(0, server.requestCount, "the plain-http hop must never be contacted")
        } finally {
            tls.shutdown()
        }
    }

    // ---- cancellation --------------------------------------------------------

    @Test
    fun cancellingBeforeAnyResponseStopsTheCallAndDoesNotRetry(): Unit = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(Dispatchers.Default) { editor.edit(endpoint(), request().copy(timeoutSeconds = 60)) }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS), "the request should have reached the server")

        job.cancel()
        withTimeout(3_000) { job.join() }

        assertTrue(job.isCancelled)
        assertEquals(1, server.requestCount, "a cancelled call must not be retried")
    }

    @Test
    fun cancellingWhileTheBodyIsStillArrivingStopsTheCall(): Unit = runBlocking {
        // Headers arrive at once, the body trickles in. A blocking body read
        // that ignores cancellation would hold the worker until the timeout.
        server.enqueue(
            json("x".repeat(200_000)).throttleBody(10, 1, TimeUnit.SECONDS),
        )
        val job = launch(Dispatchers.Default) { editor.edit(endpoint(), request().copy(timeoutSeconds = 60)) }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        kotlinx.coroutines.delay(700) // let the headers and the first bytes land

        job.cancel()
        withTimeout(3_000) { job.join() }

        assertTrue(job.isCancelled)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun cancellingAMagicPlannerCallStopsIt(): Unit = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val planner = OpenAiMagicPlanner()
        val job = launch(Dispatchers.Default) { planner.plan(endpoint(), photo, "gpt-4.1-mini", MagicApi.RESPONSES, 60) }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))

        job.cancel()
        withTimeout(3_000) { job.join() }

        assertEquals(1, server.requestCount)
    }

    // ---- timeouts ------------------------------------------------------------

    @Test
    fun aSilentServerTimesOutAsARetryableErrorAfterOneRetry(): Unit = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
        val started = System.nanoTime()

        val error = assertFailsWith<ImageGenException> { editor.edit(endpoint(), request().copy(timeoutSeconds = 1)) }

        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue(error.retryable)
        assertEquals(2, server.requestCount)
        assertTrue(seconds in 1.5..8.0, "two 1s attempts plus the 0.5s pause, took ${seconds}s")
    }

    @Test
    fun aSlowDripBodyIsCutOffByTheWholeCallTimeout(): Unit = runBlocking {
        // One byte every 300 ms never trips a 1s read timeout; only the
        // whole-call timeout ends this.
        repeat(2) {
            server.enqueue(json("x".repeat(100_000)).throttleBody(1, 300, TimeUnit.MILLISECONDS))
        }
        val started = System.nanoTime()

        val error = assertFailsWith<ImageGenException> { editor.edit(endpoint(), request().copy(timeoutSeconds = 1)) }

        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue(error.retryable)
        assertTrue(seconds < 10, "took ${seconds}s")
    }

    @Test
    fun aZeroTimeoutDoesNotMeanWaitForever(): Unit = runBlocking {
        // OkHttp reads 0 as "no timeout"; a stray 0 must not hang a worker.
        repeat(2) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
        assertFailsWith<ImageGenException> {
            withTimeout(12_000) { editor.edit(endpoint(), request().copy(timeoutSeconds = 0)) }
        }
    }

    @Test
    fun aNegativeOrAbsurdTimeoutIsClampedNotCrashed(): Unit = runBlocking {
        for (seconds in listOf(-5, Int.MIN_VALUE, Int.MAX_VALUE)) {
            server.enqueue(ok())
            val result = editor.edit(endpoint(), request().copy(timeoutSeconds = seconds))
            assertContentEquals(png, result.bytes, "timeout $seconds")
        }
    }

    // ---- 200s that are not answers ------------------------------------------

    @Test
    fun anEmptyBodyOn200IsAnImageGenExceptionForEveryApi(): Unit = runBlocking {
        for (api in ImageApi.entries) {
            server.enqueue(MockResponse().setBody(""))
            val error = assertFailsWith<ImageGenException>("$api") { editor.edit(endpoint(), request(api)) }
            assertContains(error.message!!, "not JSON")
        }
    }

    @Test
    fun anHtmlPageOn200ShowsASnippetForEveryApi(): Unit = runBlocking {
        for (api in ImageApi.entries) {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html><body>Sign in to the Wi-Fi</body></html>"))
            val error = assertFailsWith<ImageGenException>("$api") { editor.edit(endpoint(), request(api)) }
            assertContains(error.message!!, "Sign in to the Wi-Fi")
            assertTrue(error.retryable, "a captive portal clears; retry")
        }
    }

    @Test
    fun jsonOfTheWrongShapeIsAlwaysAnImageGenException(): Unit = runBlocking {
        val bodies = listOf(
            "null", "[]", "42", "\"x\"", "true", "{}",
            """{"data":null}""", """{"data":"x"}""", """{"data":{}}""", """{"data":[null,1,"x",[],{}]}""",
            """{"images":[1]}""", """{"images":"x","data":5}""", """{"choices":null}""", """{"choices":[null,1,"x",[],{"message":5}]}""",
            """{"choices":[{"message":{"images":"x","content":7}}]}""",
            """{"data":[{"b64_json":[]},{"b64_json":{}},{"url":{}}]}""",
            """{"error":{"message":"quota"}}""",
        )
        for (api in ImageApi.entries) {
            for (body in bodies) {
                server.enqueue(json(body))
                try {
                    editor.edit(endpoint(), request(api))
                    throw AssertionError("$api accepted $body")
                } catch (expected: ImageGenException) {
                    // Any ImageGenException is right; a ClassCastException or NPE is not.
                }
            }
        }
    }

    @Test
    fun aUtf8ByteOrderMarkOnAProviderReplyIsTolerated(): Unit = runBlocking {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        server.enqueue(MockResponse().setBody(Buffer().write(bom).writeUtf8("""{"data":[{"b64_json":"$pngB64"}]}""")))
        assertContentEquals(png, editor.edit(endpoint(), request()).bytes)
    }

    // ---- error bodies --------------------------------------------------------

    @Test
    fun errorBodiesAreSummarisedSafely() {
        assertEquals("quota exceeded", Http.errorDetail("""{"error":{"message":"  quota exceeded  "}}"""))
        assertEquals("(empty response)", Http.errorDetail(""))
        assertEquals("(empty response)", Http.errorDetail("   \n "))
        // A plain-string error is shown as sent, not dropped.
        assertContains(Http.errorDetail("""{"error":"overloaded"}"""), "overloaded")
        // SiliconFlow-style top-level message.
        assertContains(Http.errorDetail("""{"code":20012,"message":"Model does not exist","data":null}"""), "Model does not exist")
        // An HTML 502 page cannot flood the UI or the job file.
        assertTrue(Http.errorDetail("<html>" + "x".repeat(100_000) + "</html>").length <= 500)
    }

    @Test
    fun aNullMessageIsNotReportedAsTheWordNull() {
        val detail = Http.errorDetail("""{"error":{"message":null,"code":"rate_limit"}}""")
        assertTrue(detail != "null", "got '$detail'")
        assertContains(detail, "rate_limit")
    }

    // ---- multipart structure -------------------------------------------------

    private class Part(val headers: String, val body: ByteArray) {
        val name: String get() = Regex("name=\"([^\"]*)\"").find(headers)!!.groupValues[1]
        val filename: String? get() = Regex("filename=\"([^\"]*)\"").find(headers)?.groupValues?.get(1)
        val text: String get() = String(body, Charsets.UTF_8)
    }

    /** Parse bytes as Latin-1 so each byte stays one char, then split on the boundary. */
    private fun parts(sent: RecordedRequest): List<Part> {
        val boundary = sent.getHeader("Content-Type")!!.substringAfter("boundary=").trim('"')
        val text = String(sent.body.readByteArray(), Charsets.ISO_8859_1)
        return text.split("--$boundary").drop(1)
            .filterNot { it.startsWith("--") }
            .map { chunk ->
                val trimmed = chunk.removePrefix("\r\n").removeSuffix("\r\n")
                val gap = trimmed.indexOf("\r\n\r\n")
                Part(trimmed.substring(0, gap), trimmed.substring(gap + 4).toByteArray(Charsets.ISO_8859_1))
            }
    }

    @Test
    fun formFieldsAreSentInTheCamerasOrder(): Unit = runBlocking {
        server.enqueue(ok())
        editor.edit(endpoint(), request(model = "gpt-image-1"))
        assertEquals(
            listOf("model", "prompt", "quality", "size", "output_format", "input_fidelity", "output_compression", "image"),
            parts(server.takeRequest()).map { it.name },
        )

        server.enqueue(ok())
        editor.edit(endpoint(), request(model = "gpt-image-2"))
        assertEquals(
            listOf("model", "prompt", "quality", "size", "output_format", "output_compression", "image"),
            parts(server.takeRequest()).map { it.name },
        )
    }

    @Test
    fun compressionIsSentOnlyForTheLossyFormats(): Unit = runBlocking {
        for ((format, expected) in listOf("jpeg" to "73", "webp" to "73", "png" to null)) {
            server.enqueue(ok())
            editor.edit(endpoint(), request().copy(outputFormat = format, outputCompression = 73))
            val sent = parts(server.takeRequest()).associateBy { it.name }
            assertEquals(expected, sent["output_compression"]?.text, format)
            assertEquals(format, sent["output_format"]!!.text)
        }
    }

    @Test
    fun oneImageIsSingularAndSeveralAreAnOrderedArray(): Unit = runBlocking {
        server.enqueue(ok())
        editor.edit(endpoint(), request())
        assertEquals(listOf("image"), parts(server.takeRequest()).map { it.name }.filter { it.startsWith("image") })

        val second = reference.copy(filename = "second.jpg")
        val third = reference.copy(filename = "third.jpg")
        server.enqueue(ok())
        editor.edit(endpoint(), request(refs = listOf(second, third)))
        val images = parts(server.takeRequest()).filter { it.name.startsWith("image") }
        assertEquals(listOf("image[]", "image[]", "image[]"), images.map { it.name })
        assertEquals(listOf("photo.jpg", "second.jpg", "third.jpg"), images.map { it.filename }, "the photo must come first")
    }

    @Test
    fun imagePartsCarryTheirOwnContentType(): Unit = runBlocking {
        server.enqueue(ok())
        editor.edit(endpoint(), request(refs = listOf(reference.copy(mimeType = "image/png"))))
        val images = parts(server.takeRequest()).filter { it.name == "image[]" }
        assertContains(images[0].headers, "Content-Type: image/jpeg")
        assertContains(images[1].headers, "Content-Type: image/png")
    }

    @Test
    fun anAwkwardFilenameCannotInjectHeaders(): Unit = runBlocking {
        val evil = photo.copy(filename = "evil\"\r\nContent-Type: text/html\r\n\r\n<script>.jpg")
        server.enqueue(ok())
        editor.edit(endpoint(), request().copy(source = evil))

        val image = parts(server.takeRequest()).single { it.name == "image" }
        assertEquals(2, image.headers.lines().size, "only Content-Disposition and Content-Type: ${image.headers}")
        assertEquals(1, Regex("Content-Type:").findAll(image.headers).count())
    }

    @Test
    fun binaryImageBytesSurviveTheFormIncludingBoundaryLookalikes(): Unit = runBlocking {
        val nasty = ByteArray(256) { it.toByte() } +
            "\r\n--not-the-boundary\r\n\r\n".toByteArray() +
            byteArrayOf(0, 0, 13, 10, 13, 10, -1, -2)
        server.enqueue(ok())
        editor.edit(endpoint(), request().copy(source = photo.copy(bytes = nasty)))

        assertContentEquals(nasty, parts(server.takeRequest()).single { it.name == "image" }.body)
    }

    @Test
    fun theWrappedPromptSurvivesUnicodeQuotesAndNewlines(): Unit = runBlocking {
        val prompt = "Make it 🧀\nline two \"quoted\" \\ back\tslash — é 日本語"
        server.enqueue(ok())
        editor.edit(endpoint(), request().copy(prompt = prompt))

        assertEquals(OpenAiImageEditor.wrapPrompt(prompt, hasReferences = false), parts(server.takeRequest()).single { it.name == "prompt" }.text)
    }

    // ---- request construction ------------------------------------------------

    @Test
    fun theBearerTokenIsTrimmed(): Unit = runBlocking {
        server.enqueue(ok())
        editor.edit(endpoint(key = "  sk-padded\n"), request())
        assertEquals("Bearer sk-padded", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun aKeyThatCannotBeSentIsAPermanentErrorNotACrash(): Unit = runBlocking {
        // A line break or a pasted "…" or zero-width space inside the key makes
        // OkHttp throw IllegalArgumentException, which the worker would retry
        // for hours as if it were a network blip.
        for (key in listOf("sk-ab\ncd", "sk-abc…", "sk-​abc", "sk-ab cd", "sk-\tabc")) {
            val error = assertFailsWith<ImageGenException>("key ${key.length} chars") { editor.edit(endpoint(key = key), request()) }
            assertFalse(error.retryable)
            assertFalse(key in error.message.orEmpty(), "the message must not echo the key")
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aMalformedBaseUrlIsAPermanentErrorNotACrash(): Unit = runBlocking {
        for (base in listOf("api.openai.com/v1", "ftp://example.com/v1", "http://", "://x", "not a url", "https://exa mple.com")) {
            for (api in ImageApi.entries) {
                val error = assertFailsWith<ImageGenException>("'$base' $api") {
                    editor.edit(ApiEndpoint(base, "sk-test"), request(api))
                }
                assertFalse(error.retryable, "'$base' will never work")
            }
        }
    }

    @Test
    fun theMagicPlannerRejectsBadKeysAndUrlsTheSameWay(): Unit = runBlocking {
        val planner = OpenAiMagicPlanner()
        for (api in MagicApi.entries) {
            val badKey = assertFailsWith<ImageGenException> { planner.plan(endpoint(key = "sk-ab\ncd"), photo, "m", api, 10) }
            assertFalse(badKey.retryable)
            val badUrl = assertFailsWith<ImageGenException> { planner.plan(ApiEndpoint("no-scheme.example/v1", "sk"), photo, "m", api, 10) }
            assertFalse(badUrl.retryable)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theResolvedBaseHandlesBlanksAndRepeatedSlashes() {
        val openAi = "https://api.openai.com/v1"
        assertEquals(openAi, ApiEndpoint("", "k").resolvedBase)
        assertEquals(openAi, ApiEndpoint("   ", "k").resolvedBase)
        assertEquals(openAi, ApiEndpoint(" / ", "k").resolvedBase)
        assertEquals(openAi, ApiEndpoint("///", "k").resolvedBase)
        assertEquals("https://gw.example/v1", ApiEndpoint("  https://gw.example/v1///  ", "k").resolvedBase)
    }

    @Test
    fun aBlankKeyNeverReachesTheNetwork(): Unit = runBlocking {
        for (key in listOf("", " ", "\n\t")) {
            val error = assertFailsWith<ImageGenException> { editor.edit(endpoint(key = key), request()) }
            assertFalse(error.retryable)
        }
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }
}
