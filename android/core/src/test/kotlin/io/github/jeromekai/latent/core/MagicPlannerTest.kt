package io.github.jeromekai.latent.core

import io.github.jeromekai.latent.core.api.ApiEndpoint
import io.github.jeromekai.latent.core.api.ImageGenException
import io.github.jeromekai.latent.core.api.ImageInput
import io.github.jeromekai.latent.core.api.OpenAiMagicPlanner
import io.github.jeromekai.latent.core.model.MagicApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MagicPlannerTest {
    private lateinit var server: MockWebServer
    private val planner = OpenAiMagicPlanner()
    private val photo = ImageInput(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), "p.jpg", "image/jpeg")

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun endpoint() = ApiEndpoint(server.url("/v1").toString().trimEnd('/'), "sk-test")

    @Test
    fun responsesUsesAStrictSchemaAndRebuildsOutputText() = runBlocking {
        // The raw API has no output_text; the SDK computes it from the parts.
        server.enqueue(
            MockResponse().setBody(
                """{"output":[{"type":"message","content":[{"type":"output_text","text":"{\"title\":\"Cheese "},{"type":"output_text","text":"Hat\",\"prompt\":\"Add a cheese hat.\"}"}]}]}""",
            ),
        )

        val result = planner.plan(endpoint(), photo, "gpt-4.1-mini", MagicApi.RESPONSES, 10)

        assertEquals("Cheese Hat", result.title)
        assertEquals("Add a cheese hat.", result.prompt)
        val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val format = sent["text"]!!.jsonObject["format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        assertEquals("true", format["strict"]!!.jsonPrimitive.content)
        val image = sent["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[1].jsonObject
        assertEquals("low", image["detail"]!!.jsonPrimitive.content)
    }

    @Test
    fun chatModeParsesFencedJson() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"```json\n{\"title\":\"Duck\",\"prompt\":\"Add ducks.\"}\n```"}}]}"""))
        val result = planner.plan(endpoint(), photo, "Qwen/Qwen2.5-VL-32B-Instruct", MagicApi.CHAT, 10)
        assertEquals("Duck", result.title)
        val sent = server.takeRequest().body.readUtf8()
        assertTrue("Reply with ONLY the JSON object" in sent)
    }

    @Test
    fun anIncompletePayloadIsRejected() {
        assertFailsWith<ImageGenException> { planner.parse("""{"title":"","prompt":"x"}""") }
        assertFailsWith<ImageGenException> { planner.parse("no json here") }
    }
}
