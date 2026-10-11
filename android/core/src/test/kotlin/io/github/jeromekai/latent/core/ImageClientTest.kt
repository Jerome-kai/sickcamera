package io.github.jeromekai.latent.core

import io.github.jeromekai.latent.core.api.ApiEndpoint
import io.github.jeromekai.latent.core.api.EditRequest
import io.github.jeromekai.latent.core.api.ImageGenException
import io.github.jeromekai.latent.core.api.ImageInput
import io.github.jeromekai.latent.core.api.OpenAiImageEditor
import io.github.jeromekai.latent.core.model.ImageApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageClientTest {
    private lateinit var server: MockWebServer
    private val editor = OpenAiImageEditor()

    // A real JPEG header so sniffing has something to recognise.
    private val photo = ImageInput(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3), "photo.jpg", "image/jpeg")
    private val reference = ImageInput(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 9), "ref.jpg", "image/jpeg")
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0)
    private val pngB64 = Base64.getEncoder().encodeToString(png)

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun endpoint(key: String = "sk-test") = ApiEndpoint(server.url("/v1").toString().trimEnd('/'), key)

    private fun request(api: ImageApi, model: String = "gpt-image-2", refs: List<ImageInput> = emptyList()) =
        EditRequest(source = photo, prompt = "Turn to cheese", references = refs, model = model, api = api, timeoutSeconds = 10)

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    // ---- edits ---------------------------------------------------------------

    @Test
    fun editsSendsTheCamerasMultipartForm() = runBlocking {
        server.enqueue(json("""{"data":[{"b64_json":"$pngB64"}]}"""))

        val result = editor.edit(endpoint(), request(ImageApi.EDITS))

        val sent = server.takeRequest()
        assertEquals("/v1/images/edits", sent.path)
        assertEquals("Bearer sk-test", sent.getHeader("Authorization"))
        val body = sent.body.readUtf8()
        for (field in listOf("model", "prompt", "quality", "size", "output_format", "output_compression")) {
            assertContains(body, "name=\"$field\"")
        }
        assertContains(body, "name=\"image\"; filename=\"photo.jpg\"")
        // gpt-image-2 rejects input_fidelity; the camera only sent it to other models.
        assertFalse("input_fidelity" in body)
        assertContentEquals(png, result.bytes)
        assertEquals("image/png", result.mimeType)
    }

    @Test
    fun editsSendsInputFidelityToOtherModelsAndAnArrayForReferences() = runBlocking {
        server.enqueue(json("""{"data":[{"image_base64":"$pngB64"}]}"""))

        editor.edit(endpoint(), request(ImageApi.EDITS, model = "gpt-image-1", refs = listOf(reference)))

        val body = server.takeRequest().body.readUtf8()
        assertContains(body, "name=\"input_fidelity\"")
        // Several images travel as image[], the way openai-python encodes a list.
        assertEquals(2, Regex("name=\"image\\[]\"").findAll(body).count())
        assertContains(body, "Use the first attached image as the main camera photo")
    }

    @Test
    fun aGatewayPrefixedGptImage2IsStillRecognised() = runBlocking {
        server.enqueue(json("""{"data":[{"b64_json":"$pngB64"}]}"""))
        editor.edit(endpoint(), request(ImageApi.EDITS, model = "openai/gpt-image-2"))
        assertFalse("input_fidelity" in server.takeRequest().body.readUtf8())
    }

    // ---- chat ----------------------------------------------------------------

    @Test
    fun chatSendsTextThenEveryImageAndReadsMessageImages() = runBlocking {
        server.enqueue(json("""{"choices":[{"message":{"images":[{"image_url":{"url":"data:image/png;base64,$pngB64"}}]}}]}"""))

        val result = editor.edit(endpoint(), request(ImageApi.CHAT, model = "google/gemini-2.5-flash-image", refs = listOf(reference)))

        val sent = server.takeRequest()
        assertEquals("/v1/chat/completions", sent.path)
        val payload = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
        val content = payload["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(3, content.size) // text + photo + reference
        assertTrue(content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content.startsWith("data:image/jpeg;base64,"))
        assertContentEquals(png, result.bytes)
    }

    @Test
    fun chatAlsoFindsImagesInContentParts() {
        val response = Json.parseToJsonElement(
            """{"choices":[{"message":{"content":[{"type":"text","text":"here"},{"type":"image_url","image_url":{"url":"data:image/png;base64,$pngB64"}}]}}]}""",
        )
        assertContentEquals(png, editor.extractChatImage(response))
    }

    @Test
    fun chatWithNoImageIsAPermanentError() = runBlocking {
        server.enqueue(json("""{"choices":[{"message":{"content":"I can't draw that."}}]}"""))
        val error = assertFailsWith<ImageGenException> { editor.edit(endpoint(), request(ImageApi.CHAT)) }
        assertFalse(error.retryable)
        assertContains(error.message!!, "image-generation-capable")
    }

    // ---- generations ---------------------------------------------------------

    @Test
    fun generationsSendsOneImageAndCountsSkippedReferences() = runBlocking {
        server.enqueue(json("""{"images":[{"url":"data:image/png;base64,$pngB64"}]}"""))

        val result = editor.edit(endpoint(), request(ImageApi.GENERATIONS, refs = listOf(reference, reference)))

        val payload = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue(payload["image"]!!.jsonPrimitive.content.startsWith("data:image/jpeg;base64,"))
        assertEquals(2, result.skippedReferences)
        assertContentEquals(png, result.bytes)
    }

    @Test
    fun generationsReadsOpenAiStyleB64() = runBlocking {
        server.enqueue(json("""{"data":[{"b64_json":"$pngB64"}]}"""))
        assertContentEquals(png, editor.edit(endpoint(), request(ImageApi.GENERATIONS)).bytes)
    }

    @Test
    fun aPlainHttpImageLinkIsRefused() = runBlocking {
        // The URL comes from a third party's response; the camera fetched any
        // scheme to any host. Refuse anything that is not https.
        server.enqueue(json("""{"images":[{"url":"http://192.168.1.1/admin.png"}]}"""))
        val error = assertFailsWith<ImageGenException> { editor.edit(endpoint(), request(ImageApi.GENERATIONS)) }
        assertContains(error.message!!, "not https")
        assertEquals(1, server.requestCount) // never fetched
    }

    // ---- errors --------------------------------------------------------------

    @Test
    fun aBadKeyFailsAtOnceWithTheProvidersMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Incorrect API key provided."}}"""))

        val error = assertFailsWith<ImageGenException> { editor.edit(endpoint(), request(ImageApi.EDITS)) }

        assertFalse(error.retryable)
        assertEquals(401, error.statusCode)
        assertContains(error.message!!, "Incorrect API key provided.")
        assertEquals(1, server.requestCount) // a 401 is not retried
    }

    @Test
    fun aServerErrorIsRetriedOnceThenReportedAsRetryable() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(503).setBody("busy")) }

        val error = assertFailsWith<ImageGenException> { editor.edit(endpoint(), request(ImageApi.EDITS)) }

        assertTrue(error.retryable)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aRateLimitThenSuccessRecovers() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))
        server.enqueue(json("""{"data":[{"b64_json":"$pngB64"}]}"""))
        assertContentEquals(png, editor.edit(endpoint(), request(ImageApi.EDITS)).bytes)
    }

    @Test
    fun noKeyFailsBeforeAnyRequest() = runBlocking {
        val error = assertFailsWith<ImageGenException> { editor.edit(endpoint(key = "  "), request(ImageApi.EDITS)) }
        assertFalse(error.retryable)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theKeyDoesNotFollowARedirectToAnotherHost() = runBlocking {
        // The camera's urllib path forwarded Authorization across hosts.
        val elsewhere = MockWebServer().apply { start() }
        try {
            elsewhere.enqueue(json("""{"data":[{"b64_json":"$pngB64"}]}"""))
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", elsewhere.url("/steal").toString()))

            editor.edit(endpoint(), request(ImageApi.EDITS))

            assertEquals("Bearer sk-test", server.takeRequest().getHeader("Authorization"))
            assertNull(elsewhere.takeRequest().getHeader("Authorization"))
        } finally {
            elsewhere.shutdown()
        }
    }
}
