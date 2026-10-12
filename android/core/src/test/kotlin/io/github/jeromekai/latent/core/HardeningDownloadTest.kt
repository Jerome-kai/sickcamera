package io.github.jeromekai.latent.core

import io.github.jeromekai.latent.core.api.ApiEndpoint
import io.github.jeromekai.latent.core.api.EditRequest
import io.github.jeromekai.latent.core.api.ImageGenException
import io.github.jeromekai.latent.core.api.ImageInput
import io.github.jeromekai.latent.core.api.OpenAiImageEditor
import io.github.jeromekai.latent.core.model.ImageApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Before
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The /images/generations path that fetches a provider-hosted image. The link
 * comes from a third party, so everything about it is hostile until proven
 * otherwise: scheme, size, framing, redirects and how long it takes.
 */
class HardeningDownloadTest {
    private lateinit var api: MockWebServer
    private lateinit var cdn: MockWebServer
    private val editor = OpenAiImageEditor(HardeningTls.trustingClient())
    private val cap = OpenAiImageEditor.MAX_DOWNLOAD_BYTES

    private val photo = ImageInput(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1), "photo.jpg", "image/jpeg")

    @Before fun start() {
        api = MockWebServer().apply { start() }
        cdn = HardeningTls.httpsServer()
    }

    @After fun stop() {
        api.shutdown()
        cdn.shutdown()
    }

    private fun endpoint() = ApiEndpoint(api.url("/v1").toString().trimEnd('/'), "sk-test")

    private fun request(timeoutSeconds: Int = 30) = EditRequest(
        source = photo, prompt = "p", model = "m", api = ImageApi.GENERATIONS, timeoutSeconds = timeoutSeconds,
    )

    /** Make the API reply with a link to [url] (the CDN's /img.png unless told otherwise). */
    private fun linkTo(url: String = cdn.url("/img.png").toString()) {
        api.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"images":[{"url":"$url"}]}"""))
    }

    private fun png(size: Long) = HardeningTls.pngBody(size)

    // ---- the 40 MB cap -------------------------------------------------------

    @Test
    fun aBodyOneByteUnderTheCapIsAccepted(): Unit = runBlocking {
        linkTo()
        cdn.enqueue(MockResponse().setBody(png(cap - 1)))
        val result = editor.edit(endpoint(), request())
        assertEquals(cap - 1, result.bytes.size.toLong())
        assertEquals("image/png", result.mimeType)
    }

    @Test
    fun aBodyExactlyAtTheCapIsAccepted(): Unit = runBlocking {
        linkTo()
        cdn.enqueue(MockResponse().setBody(png(cap)))
        assertEquals(cap, editor.edit(endpoint(), request()).bytes.size.toLong())
    }

    @Test
    fun aBodyOneByteOverTheCapIsRefusedFromItsContentLength(): Unit = runBlocking {
        linkTo()
        cdn.enqueue(MockResponse().setBody(png(cap + 1)))
        val error = assertFailsWith<ImageGenException> { editor.edit(endpoint(), request()) }
        assertFalse(error.retryable)
        assertContains(error.message!!, "too large")
    }

    @Test
    fun aChunkedBodyAtTheCapIsAcceptedWithNoContentLength(): Unit = runBlocking {
        linkTo()
        cdn.enqueue(MockResponse().setChunkedBody(png(cap), 64 * 1024))
        assertEquals(cap, editor.edit(endpoint(), request()).bytes.size.toLong())
    }

    @Test
    fun aChunkedBodyOneByteOverTheCapIsRefusedWithNoContentLength(): Unit = runBlocking {
        linkTo()
        cdn.enqueue(MockResponse().setChunkedBody(png(cap + 1), 64 * 1024))
        val error = assertFailsWith<ImageGenException> { editor.edit(endpoint(), request()) }
        assertFalse(error.retryable)
        assertContains(error.message!!, "too large")
    }

    @Test
    fun aBodyThatEndsWhenTheConnectionClosesIsStillCapped(): Unit = runBlocking {
        linkTo()
        cdn.enqueue(
            MockResponse().setBody(png(cap + 1)).removeHeader("Content-Length")
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END),
        )
        val error = assertFailsWith<ImageGenException> { editor.edit(endpoint(), request()) }
        assertFalse(error.retryable)
        assertContains(error.message!!, "too large")
    }

    @Test
    fun aGzipBombIsCappedOnItsDecompressedSize(): Unit = runBlocking {
        // 41 MB of zeros gzips to a few dozen KB: the Content-Length is tiny and
        // honest, and only the decompressed size gives it away.
        val zip = ByteArrayOutputStream()
        GZIPOutputStream(zip).use { out ->
            val zeros = ByteArray(1 shl 20)
            repeat(41) { out.write(zeros) }
        }
        assertTrue(zip.size() < 200_000, "the bomb should be small on the wire, was ${zip.size()}")
        linkTo()
        cdn.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(Buffer().write(zip.toByteArray())))

        val error = assertFailsWith<ImageGenException> { editor.edit(endpoint(), request()) }
        assertFalse(error.retryable)
        assertContains(error.message!!, "too large")
    }

    @Test
    fun aHugeLyingContentLengthIsRefusedBeforeReadingAnything(): Unit = runBlocking {
        linkTo()
        cdn.enqueue(
            MockResponse().setBody("12345").setHeader("Content-Length", "10000000000")
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END),
        )
        val error = assertFailsWith<ImageGenException> { editor.edit(endpoint(), request()) }
        assertFalse(error.retryable)
        assertContains(error.message!!, "too large")
    }

    @Test
    fun aContentLengthLongerThanTheBodyIsARetryableErrorNotARawIoException(): Unit = runBlocking {
        linkTo()
        cdn.enqueue(
            MockResponse().setBody("0123456789").setHeader("Content-Length", "100")
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END),
        )
        val error = assertFailsWith<ImageGenException> { editor.edit(endpoint(), request()) }
        assertTrue(error.retryable, "a truncated download is worth another try")
    }

    @Test
    fun anEmptyDownloadIsNotAnImage(): Unit = runBlocking {
        linkTo()
        cdn.enqueue(MockResponse().setResponseCode(200).setBody(""))
        val error = assertFailsWith<ImageGenException> { editor.edit(endpoint(), request()) }
        assertTrue(error.retryable)
    }

    // ---- what the CDN says ---------------------------------------------------

    @Test
    fun downloadErrorsAreClassifiedByStatus(): Unit = runBlocking {
        for ((code, retryable) in listOf(403 to false, 404 to false, 410 to false, 429 to true, 500 to true, 503 to true)) {
            linkTo()
            cdn.enqueue(MockResponse().setResponseCode(code).setBody("nope"))
            val error = assertFailsWith<ImageGenException>("status $code") { editor.edit(endpoint(), request()) }
            assertEquals(retryable, error.retryable, "status $code")
            assertContains(error.message!!, "HTTP $code")
        }
    }

    @Test
    fun theDownloadCarriesNoCredentials(): Unit = runBlocking {
        linkTo()
        cdn.enqueue(MockResponse().setBody(png(64)))
        editor.edit(endpoint(), request())

        val sent = cdn.takeRequest()
        assertEquals("GET", sent.method)
        assertNull(sent.getHeader("Authorization"), "the key is for the API, not the CDN")
        assertNull(sent.getHeader("Cookie"))
    }

    @Test
    fun downloadedBytesAreSniffedForTheirType(): Unit = runBlocking {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16)
        val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)
        for ((bytes, expected) in listOf(jpeg to "image/jpeg", webp to "image/webp")) {
            linkTo()
            cdn.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            assertEquals(expected, editor.edit(endpoint(), request()).mimeType)
        }
    }

    @Test
    fun aLinkThatMerelyContainsBase64CommaIsDownloadedNotDecoded(): Unit = runBlocking {
        linkTo(cdn.url("/img.png?fmt=base64,QUJD").toString())
        cdn.enqueue(MockResponse().setBody(png(64)))

        val result = editor.edit(endpoint(), request())

        assertEquals(64, result.bytes.size, "the CDN's bytes, not the three bytes of 'ABC'")
        assertEquals(1, cdn.requestCount)
    }

    // ---- which links are followed ---------------------------------------------

    @Test
    fun onlyHttpsLinksAreEverFetched(): Unit = runBlocking {
        val links = listOf(
            "http://localhost:1/x.png", "ftp://example.com/x.png", "file:///etc/passwd",
            "javascript:alert(1)", "//cdn.example.com/x.png", "https:/broken", "not a url", "",
        )
        for (link in links) {
            linkTo(link)
            val error = assertFailsWith<ImageGenException>("'$link'") { editor.edit(endpoint(), request()) }
            assertFalse(error.retryable, "'$link'")
        }
        assertEquals(0, cdn.requestCount)
    }

    @Test
    fun aDownloadRedirectedToPlainHttpIsRefused(): Unit = runBlocking {
        // An https link that bounces to http://192.168.x.x/... must not be followed.
        val lan = MockWebServer().apply { start() }
        try {
            lan.enqueue(MockResponse().setBody(png(64)))
            linkTo()
            cdn.enqueue(MockResponse().setResponseCode(302).setHeader("Location", lan.url("/internal.png").toString()))

            assertFailsWith<ImageGenException> { editor.edit(endpoint(), request()) }

            assertEquals(0, lan.requestCount, "the plain-http hop must never be contacted")
        } finally {
            lan.shutdown()
        }
    }

    @Test
    fun aDownloadRedirectedToAnotherHttpsHostIsFollowed(): Unit = runBlocking {
        // CDNs redirect to signed storage URLs all the time; that must keep working.
        val storage = HardeningTls.httpsServer()
        try {
            storage.enqueue(MockResponse().setBody(png(128)))
            linkTo()
            cdn.enqueue(MockResponse().setResponseCode(302).setHeader("Location", storage.url("/signed.png").toString()))

            assertEquals(128, editor.edit(endpoint(), request()).bytes.size)
            assertNull(storage.takeRequest().getHeader("Authorization"))
        } finally {
            storage.shutdown()
        }
    }

    @Test
    fun aDownloadRedirectLoopEndsInAnError(): Unit = runBlocking {
        cdn.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(302).setHeader("Location", "/img.png")
        }
        linkTo()
        assertFailsWith<ImageGenException> { editor.edit(endpoint(), request()) }
        assertTrue(cdn.requestCount <= 25, "OkHttp stops after 20 follow-ups, got ${cdn.requestCount}")
    }

    // ---- time ----------------------------------------------------------------

    @Test
    fun aSlowDownloadIsCutOffByTheCallTimeout(): Unit = runBlocking {
        linkTo()
        cdn.enqueue(MockResponse().setBody(png(100_000)).throttleBody(1, 300, TimeUnit.MILLISECONDS))
        val started = System.nanoTime()

        val error = assertFailsWith<ImageGenException> { editor.edit(endpoint(), request(timeoutSeconds = 1)) }

        assertTrue(error.retryable)
        assertTrue((System.nanoTime() - started) / 1e9 < 10, "the one-second limit must bound the whole download")
    }

    @Test
    fun cancellingMidDownloadStopsTheCall(): Unit = runBlocking {
        linkTo()
        cdn.enqueue(MockResponse().setBody(png(300_000)).throttleBody(10, 1, TimeUnit.SECONDS))
        val job = launch(Dispatchers.Default) { editor.edit(endpoint(), request(timeoutSeconds = 60)) }
        assertNotNull(cdn.takeRequest(5, TimeUnit.SECONDS), "the download should have started")
        delay(700) // headers and the first bytes have landed; the rest is a trickle

        job.cancel()
        withTimeout(3_000) { job.join() }

        assertTrue(job.isCancelled)
    }
}
