package io.github.jeromekai.latent.core

import io.github.jeromekai.latent.core.api.ApiEndpoint
import io.github.jeromekai.latent.core.api.DataUrls
import io.github.jeromekai.latent.core.api.EditRequest
import io.github.jeromekai.latent.core.api.ImageGenException
import io.github.jeromekai.latent.core.api.ImageInput
import io.github.jeromekai.latent.core.api.ImageSniffer
import io.github.jeromekai.latent.core.api.OpenAiImageEditor
import io.github.jeromekai.latent.core.model.ImageApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DataUrls, ImageSniffer and the shapes of provider replies that carry an image. */
class HardeningImageDataTest {
    private lateinit var server: MockWebServer
    private val editor = OpenAiImageEditor()

    private val photo = ImageInput(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3), "photo.jpg", "image/jpeg")
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0)
    private val pngB64 = Base64.getEncoder().encodeToString(png)

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun endpoint() = ApiEndpoint(server.url("/v1").toString().trimEnd('/'), "sk-test")

    private fun request(api: ImageApi) = EditRequest(source = photo, prompt = "p", model = "m", api = api, timeoutSeconds = 10)

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun chat(body: String) = editor.extractChatImage(Json.parseToJsonElement(body))

    // ---- DataUrls ------------------------------------------------------------

    @Test
    fun everyByteValueAndEveryPaddingLengthRoundTrips() {
        val all = ByteArray(256) { it.toByte() }
        for (length in 0..8) {
            val bytes = all.copyOf(length)
            val url = DataUrls.build(ImageInput(bytes, "x", "image/png"))
            if (length == 0) assertNull(DataUrls.decode(url), "nothing to decode is null, not an empty image")
            else assertContentEquals(bytes, DataUrls.decode(url), "length $length")
        }
        assertContentEquals(all, DataUrls.decode(DataUrls.build(ImageInput(all, "x", "image/png"))))
    }

    @Test
    fun buildProducesAStandardDataUrl() {
        val jpeg = ImageInput(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), "x.jpg", "image/jpeg")
        assertEquals("data:image/jpeg;base64,/9j/", DataUrls.build(jpeg))
    }

    @Test
    fun whitespaceAnywhereInThePayloadIsIgnored() {
        val wrapped = pngB64.chunked(4).joinToString("\r\n  ")
        assertContentEquals(png, DataUrls.decode("data:image/png;base64,$wrapped"))
        assertContentEquals(png, DataUrls.decode("data:image/png;base64,\n\t $pngB64 \n"))
        assertContentEquals(png, DataUrls.decode("data:image/png;base64," + pngB64.toList().joinToString(" ")))
    }

    @Test
    fun badBase64IsNullNotSilentGarbage() {
        // The lenient MIME decoder used to skip every character it did not
        // know, so these came back as empty or corrupted "images".
        val bad = listOf(
            "@@@@", "!!!!", "AAAA$", "AAAAA", "A", "====", "QUJD*", "QUJD,base64,QUJD",
            "QU-_", // URL-safe alphabet: not what the providers send; do not guess
            "éééé", "日本語",
        )
        for (payload in bad) {
            assertNull(DataUrls.decode("data:image/png;base64,$payload"), "payload '$payload'")
        }
    }

    @Test
    fun anEmptyOrBlankPayloadIsNull() {
        assertNull(DataUrls.decode("data:image/png;base64,"))
        assertNull(DataUrls.decode("data:image/png;base64,   \r\n"))
    }

    @Test
    fun aMissingMarkerIsNull() {
        for (url in listOf(null, "", "data:image/png", "data:image/png;base64", "image/png;base64 QUJD", "QUJD", "base64")) {
            assertNull(DataUrls.decode(url), "'$url'")
        }
    }

    @Test
    fun aLinkThatMerelyContainsBase64CommaIsNotADataUrl() {
        assertNull(DataUrls.decode("https://cdn.example.com/render?fmt=base64,QUJD"))
        assertNull(DataUrls.decode("http://x/base64,QUJD"))
    }

    @Test
    fun mimeParametersAndMarkerCaseDoNotMatter() {
        val abc = "ABC".toByteArray()
        assertContentEquals(abc, DataUrls.decode("data:image/png;charset=utf-8;base64,QUJD"))
        assertContentEquals(abc, DataUrls.decode("DATA:image/png;BASE64,QUJD"))
    }

    @Test
    fun aTwentyMegabytePayloadDecodesInOnePass() {
        val big = ByteArray(20 * 1024 * 1024) { (it * 31).toByte() }
        val started = System.nanoTime()
        val back = DataUrls.decode(DataUrls.build(ImageInput(big, "big", "image/png")))
        assertContentEquals(big, back)
        assertTrue((System.nanoTime() - started) / 1e9 < 20)
    }

    // ---- ImageSniffer --------------------------------------------------------

    private val headers = mapOf(
        "image/jpeg" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
        "image/png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
        "image/webp" to "RIFF\u0001\u0002\u0003\u0004WEBP".toByteArray(Charsets.ISO_8859_1),
        "image/gif" to "GIF89a".toByteArray(),
    )

    @Test
    fun eachFormatIsRecognisedFromItsSmallestHeader() {
        for ((mime, header) in headers) assertEquals(mime, ImageSniffer.mimeType(header), mime)
        assertEquals("image/gif", ImageSniffer.mimeType("GIF87a".toByteArray()))
    }

    @Test
    fun everyTruncatedHeaderIsNullAndNeverThrows() {
        for ((mime, header) in headers) {
            for (length in 0 until header.size) {
                assertNull(ImageSniffer.mimeType(header.copyOf(length)), "$mime cut to $length bytes")
            }
        }
        assertNull(ImageSniffer.mimeType(ByteArray(0)))
    }

    @Test
    fun lookalikesAreNotImages() {
        val notImages = listOf(
            "RIFF\u0001\u0002\u0003\u0004WAVE".toByteArray(Charsets.ISO_8859_1),
            "RIFF\u0001\u0002\u0003\u0004AVI ".toByteArray(Charsets.ISO_8859_1),
            "WEBPRIFF\u0001\u0002\u0003\u0004".toByteArray(Charsets.ISO_8859_1),
            "GIF7xx".toByteArray(),
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00),
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x58, 0x0D, 0x0A, 0x1A, 0x0A),
            "<html><body>429</body></html>".toByteArray(),
            """{"error":"nope"}""".toByteArray(),
            ByteArray(64),
        )
        for (bytes in notImages) assertNull(ImageSniffer.mimeType(bytes), String(bytes, Charsets.ISO_8859_1).take(16))
    }

    @Test
    fun arbitraryBytesNeverMakeTheSnifferThrow() {
        val random = Random(1234)
        repeat(20_000) {
            val bytes = random.nextBytes(random.nextInt(0, 40))
            ImageSniffer.mimeType(bytes) // must not throw
        }
        // Prefixes of real headers followed by noise.
        for (header in headers.values) {
            repeat(500) {
                ImageSniffer.mimeType(header.copyOf(random.nextInt(0, header.size + 1)) + random.nextBytes(random.nextInt(0, 10)))
            }
        }
    }

    // ---- extractChatImage ------------------------------------------------------

    @Test
    fun repliesWithoutUsableChoicesGiveNull() {
        val bodies = listOf(
            "null", "[]", "{}", """{"choices":null}""", """{"choices":"x"}""", """{"choices":[]}""",
            """{"choices":[null,1,"x",[],{}]}""",
            """{"choices":[{"message":null}]}""", """{"choices":[{"message":"hi"}]}""", """{"choices":[{"message":[]}]}""",
            """{"choices":[{"message":{"images":"x","content":"y"}}]}""",
            """{"choices":[{"message":{"images":[null,1,"x",[],{}]}}]}""",
            """{"choices":[{"message":{"images":[{"image_url":null}]}}]}""",
            """{"choices":[{"message":{"images":[{"image_url":{"url":null}}]}}]}""",
            """{"choices":[{"message":{"images":[{"image_url":{"url":5}}]}}]}""",
            """{"choices":[{"message":{"images":[{"image_url":{}}]}}]}""",
            """{"choices":[{"message":{"images":[{"image_url":[]}]}}]}""",
            """{"choices":[{"message":{"content":"data:image/png;base64,$pngB64"}}]}""", // a bare string is not an image part
        )
        for (body in bodies) assertNull(chat(body), body)
    }

    @Test
    fun anUnusableImageDoesNotHideALaterGoodOne() {
        val body = """{"choices":[
            {"message":"text only"},
            {"message":{"images":[{"image_url":{"url":"data:image/png;base64,!!!!"}}]}},
            {"message":{"images":[{"image_url":{"url":"data:image/png;base64,"}}]}},
            {"message":{"content":[{"type":"image_url","image_url":"data:image/png;base64,$pngB64"}]}}
        ]}"""
        assertContentEquals(png, chat(body))
    }

    @Test
    fun imagesArePreferredOverContentParts() {
        val other = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val body = """{"choices":[{"message":{
            "content":[{"image_url":{"url":"data:image/png;base64,$other"}}],
            "images":[{"image_url":{"url":"data:image/png;base64,$pngB64"}}]}}]}"""
        assertContentEquals(png, chat(body))
    }

    @Test
    fun aWrappedDataUrlInAChatReplyStillDecodes() {
        val wrapped = pngB64.chunked(4).joinToString("\\n")
        assertContentEquals(png, chat("""{"choices":[{"message":{"images":[{"image_url":{"url":"data:image/png;base64,$wrapped"}}]}}]}"""))
    }

    @Test
    fun deeplyNestedGarbageDoesNotCrashTheExtractor() {
        val depth = 1000
        val nested = "[".repeat(depth) + "]".repeat(depth)
        assertNull(chat("""{"choices":[{"message":{"content":$nested,"images":$nested}}]}"""))
    }

    // ---- JSON null is not the text "null" --------------------------------------

    @Test
    fun aJsonNullB64FieldFallsThroughToTheNextFieldOnEdits() = runBlocking {
        // JsonNull.content is the four letters "null", which are valid base64
        // and used to decode into three bytes of garbage reported as the image.
        server.enqueue(json("""{"data":[{"b64_json":null,"image_base64":"$pngB64"}]}"""))
        assertContentEquals(png, editor.edit(endpoint(), request(ImageApi.EDITS)).bytes)
    }

    @Test
    fun aJsonNullB64FieldFallsThroughToTheUrlOnGenerations() = runBlocking {
        server.enqueue(json("""{"data":[{"b64_json":null,"url":"data:image/png;base64,$pngB64"}]}"""))
        assertContentEquals(png, editor.edit(endpoint(), request(ImageApi.GENERATIONS)).bytes)
    }

    @Test
    fun aJsonNullUrlIsSkippedNotFetchedAsTheWordNull() = runBlocking {
        server.enqueue(json("""{"images":[{"url":null},{"url":"data:image/png;base64,$pngB64"}]}"""))
        assertContentEquals(png, editor.edit(endpoint(), request(ImageApi.GENERATIONS)).bytes)
    }

    @Test
    fun anImageEntryWithNothingUsableMovesOnToTheNext() = runBlocking {
        server.enqueue(json("""{"data":[{"b64_json":""},{"revised_prompt":"x"},{"b64_json":"$pngB64"}]}"""))
        assertContentEquals(png, editor.edit(endpoint(), request(ImageApi.GENERATIONS)).bytes)
    }

    @Test
    fun imagesAreUsedWhenPresentAndDataOtherwise() = runBlocking {
        val other = Base64.getEncoder().encodeToString(byteArrayOf(7, 7, 7))
        server.enqueue(json("""{"images":[{"url":"data:image/png;base64,$pngB64"}],"data":[{"b64_json":"$other"}]}"""))
        assertContentEquals(png, editor.edit(endpoint(), request(ImageApi.GENERATIONS)).bytes)

        server.enqueue(json("""{"images":[],"data":[{"b64_json":"$pngB64"}]}"""))
        assertContentEquals(png, editor.edit(endpoint(), request(ImageApi.GENERATIONS)).bytes)
    }

    // ---- an "image" that is not one ---------------------------------------------

    @Test
    fun undecodableBase64OnEditsIsAnErrorNotAnEmptyImage() = runBlocking {
        for (payload in listOf("!!!!", "@@@@@@@@", "A", "日本語")) {
            server.enqueue(json("""{"data":[{"b64_json":"$payload"}]}"""))
            val error = assertFailsWith<ImageGenException>(payload) { editor.edit(endpoint(), request(ImageApi.EDITS)) }
            assertTrue(error.retryable, "a garbled reply is worth another try")
        }
    }

    @Test
    fun undecodableBase64OnGenerationsIsAnErrorNotAnEmptyImage() = runBlocking {
        server.enqueue(json("""{"data":[{"b64_json":"!!!!"}]}"""))
        assertFailsWith<ImageGenException> { editor.edit(endpoint(), request(ImageApi.GENERATIONS)) }
    }

    @Test
    fun aChatReplyWhoseOnlyImageIsEmptyIsNoImage() = runBlocking {
        server.enqueue(json("""{"choices":[{"message":{"images":[{"image_url":{"url":"data:image/png;base64,"}}]}}]}"""))
        val error = assertFailsWith<ImageGenException> { editor.edit(endpoint(), request(ImageApi.CHAT)) }
        assertTrue(!error.retryable)
    }

    @Test
    fun noResultEverCarriesZeroBytes() = runBlocking {
        val replies = mapOf(
            ImageApi.EDITS to listOf("""{"data":[{"b64_json":"===="}]}""", """{"data":[{"b64_json":"   "}]}"""),
            ImageApi.GENERATIONS to listOf("""{"data":[{"b64_json":"===="}]}"""),
            ImageApi.CHAT to listOf("""{"choices":[{"message":{"images":[{"image_url":{"url":"data:image/png;base64,===="}}]}}]}"""),
        )
        for ((api, bodies) in replies) {
            for (body in bodies) {
                server.enqueue(json(body))
                val outcome = runCatching { editor.edit(endpoint(), request(api)) }
                val result = outcome.getOrNull()
                if (result != null) assertNotNull(result.bytes.takeIf { it.isNotEmpty() }, "$api returned an empty image for $body")
            }
        }
    }
}
