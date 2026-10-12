package io.github.jeromekai.latent.core

import io.github.jeromekai.latent.core.api.ApiEndpoint
import io.github.jeromekai.latent.core.api.ImageGenException
import io.github.jeromekai.latent.core.api.ImageInput
import io.github.jeromekai.latent.core.api.OpenAiMagicPlanner
import io.github.jeromekai.latent.core.model.MagicApi
import io.github.jeromekai.latent.core.model.Normalize
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the magic planner does with the odd things language models say. */
class HardeningPlannerTest {
    private lateinit var server: MockWebServer
    private val planner = OpenAiMagicPlanner()
    private val photo = ImageInput(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), "p.jpg", "image/jpeg")

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun endpoint() = ApiEndpoint(server.url("/v1").toString().trimEnd('/'), "sk-test")

    private fun reply(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun outputText(text: String) =
        """{"output":[{"type":"message","content":[{"type":"output_text","text":${JsonPrimitive(text)}}]}]}"""

    private fun plan(api: MagicApi) = runBlocking { planner.plan(endpoint(), photo, "m", api, 10) }

    private fun hasLoneSurrogate(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) { i += 2; continue }
                return true
            }
            if (Character.isLowSurrogate(c)) return true
            i++
        }
        return false
    }

    // ---- parse -----------------------------------------------------------------

    @Test
    fun aLongEmojiTitleIsCutByCodePointNeverMidPair() {
        val title = "🧀".repeat(30)
        val parsed = planner.parse("""{"title":"$title","prompt":"Add cheese."}""")
        assertEquals("🧀".repeat(Normalize.PROMPT_TITLE_MAX_LENGTH), parsed.title)
        assertFalse(hasLoneSurrogate(parsed.title))
    }

    @Test
    fun aTitleCutRightBeforeASpaceDoesNotKeepTheSpace() {
        val title = "a".repeat(21) + " tail"
        assertEquals("a".repeat(21), planner.parse("""{"title":"$title","prompt":"x"}""").title)
    }

    @Test
    fun theTitleLimitIsConfigurable() {
        val short = OpenAiMagicPlanner(titleMaxLength = 5)
        assertEquals("abcde", short.parse("""{"title":"abcdefgh","prompt":"x"}""").title)
        assertContains(short.instructionText(), "max 5 characters")
        assertEquals("Magic", OpenAiMagicPlanner(titleMaxLength = 0).parse("""{"title":"abc","prompt":"x"}""").title)
    }

    @Test
    fun scalarTitlesAndPromptsAreStringifiedLikePython() {
        assertEquals("5", planner.parse("""{"title":5,"prompt":"x"}""").title)
        assertEquals("true", planner.parse("""{"title":true,"prompt":"x"}""").title)
        // str(0 or "") is "", which makes the payload incomplete.
        assertFailsWith<ImageGenException> { planner.parse("""{"title":0,"prompt":"x"}""") }
        assertFailsWith<ImageGenException> { planner.parse("""{"title":"A","prompt":false}""") }
        assertFailsWith<ImageGenException> { planner.parse("""{"title":null,"prompt":"x"}""") }
    }

    @Test
    fun structuredValuesInTitleAndPromptDoNotCrash() {
        val parsed = planner.parse("""{"title":["a","b"],"prompt":{"k":"v"}}""")
        assertTrue(parsed.title.isNotEmpty() && parsed.prompt.isNotEmpty())
        assertTrue(parsed.title.codePointCount(0, parsed.title.length) <= Normalize.PROMPT_TITLE_MAX_LENGTH)
    }

    @Test
    fun whitespaceOnlyFieldsAreIncomplete() {
        assertFailsWith<ImageGenException> { planner.parse("""{"title":"   \n","prompt":"x"}""") }
        assertFailsWith<ImageGenException> { planner.parse("""{"title":"A","prompt":"\t "}""") }
    }

    @Test
    fun proseFencesAndByteOrderMarksAroundTheObjectAreSkipped() {
        val good = """{"title":"Duck","prompt":"Add ducks."}"""
        for (wrapped in listOf("Sure! Here you go:\n$good\nHope it helps.", "```json\n$good\n```", "﻿$good", "  \n$good\n\n")) {
            assertEquals("Duck", planner.parse(wrapped).title, wrapped)
        }
    }

    @Test
    fun braceInsideAStringDoesNotConfuseTheOutermostMatch() {
        val parsed = planner.parse("""{"title":"Curly","prompt":"Wrap it in { and } characters."}""")
        assertEquals("Wrap it in { and } characters.", parsed.prompt)
    }

    @Test
    fun anArrayOfObjectsOrTwoObjectsIsRejectedNotHalfRead() {
        // The outermost braces of "{a} {b}" span both, which is not one object.
        assertFailsWith<ImageGenException> {
            planner.parse("""{"title":"A","prompt":"B"} {"title":"C","prompt":"D"}""")
        }
        // An array holding exactly one object is read as that object.
        assertEquals("A", planner.parse("""[{"title":"A","prompt":"B"}]""").title)
    }

    @Test
    fun almostJsonIsAnErrorTheQueueCanRetry() {
        val almost = listOf(
            "{'title': 'A', 'prompt': 'B'}", "{title: \"A\", prompt: \"B\"}", """{"title":"A","prompt":"B",}""",
            "{", "}", "}{", "{}", "", "   ", "null", "{\"title\":\"A\"", "{\"Title\":\"A\",\"Prompt\":\"B\"}",
        )
        for (raw in almost) {
            val error = assertFailsWith<ImageGenException>("'$raw'") { planner.parse(raw) }
            assertTrue(error.retryable, "'$raw'")
        }
    }

    @Test
    fun duplicateKeysTakeTheLastValueAndEscapesDecode() {
        val parsed = planner.parse("""{"title":"First","title":"Café","prompt":"Line1\nLine2"}""")
        assertEquals("Café", parsed.title)
        assertEquals("Line1\nLine2", parsed.prompt)
    }

    @Test
    fun aMegabyteOfNoiseWithoutBracesFailsFastAndTheMessageIsBounded() {
        val error = assertFailsWith<ImageGenException> { planner.parse("noise ".repeat(200_000)) }
        assertTrue(error.message!!.length < 400, "the error text is shown to the user and stored in the job file")
    }

    @Test
    fun aLoneSurrogateInATitleSurvivesWithoutCrashing() {
        val parsed = planner.parse("""{"title":"a\ud83d","prompt":"x"}""")
        assertTrue(parsed.title.startsWith("a"))
    }

    // ---- output_text -------------------------------------------------------------

    @Test
    fun aNullTopLevelOutputTextFallsBackToTheOutputArray() {
        // Some gateways send "output_text": null next to the real output; the
        // JSON null used to be read as the four-letter text "null".
        val body = """{"output_text":null,"output":[{"content":[{"type":"output_text","text":"{\"title\":\"Hat\",\"prompt\":\"Add a hat.\"}"}]}]}"""
        server.enqueue(reply(body))
        assertEquals("Hat", plan(MagicApi.RESPONSES).title)
    }

    @Test
    fun aBlankTopLevelOutputTextFallsBackToTheOutputArray() {
        server.enqueue(reply("""{"output_text":"  ","output":[{"content":[{"type":"output_text","text":"{\"title\":\"Hat\",\"prompt\":\"p\"}"}]}]}"""))
        assertEquals("Hat", plan(MagicApi.RESPONSES).title)
    }

    @Test
    fun aTopLevelOutputTextWinsWhenPresent() {
        server.enqueue(reply("""{"output_text":"{\"title\":\"Top\",\"prompt\":\"p\"}","output":[{"content":[{"type":"output_text","text":"{\"title\":\"Inner\",\"prompt\":\"p\"}"}]}]}"""))
        assertEquals("Top", plan(MagicApi.RESPONSES).title)
    }

    @Test
    fun partsThatAreNotOutputTextAreSkipped() {
        val body = """{"output":[
            {"type":"reasoning","content":null},
            {"type":"message","content":[
                {"type":"refusal","text":"I cannot"},
                {"type":"output_text","text":null},
                {"type":"output_text","text":"{\"title\":\"Ok\",\"prompt\":\"fine\"}"}]},
            "stray", 7, null]}"""
        server.enqueue(reply(body))
        assertEquals("Ok", plan(MagicApi.RESPONSES).title)
    }

    @Test
    fun aNullOutputTextPartAloneIsNoOutputNotTheWordNull() {
        server.enqueue(reply("""{"output":[{"content":[{"type":"output_text","text":null}]}]}"""))
        val error = assertFailsWith<ImageGenException> { plan(MagicApi.RESPONSES) }
        assertContains(error.message!!, "no magic prompt output")
    }

    @Test
    fun responsesShapesThatCarryNothingAreErrors() {
        val bodies = listOf("{}", "[]", "null", """{"output":null}""", """{"output":"x"}""", """{"output":[]}""", """{"output":[{"content":"x"}]}""")
        for (body in bodies) {
            server.enqueue(reply(body))
            val error = assertFailsWith<ImageGenException>(body) { plan(MagicApi.RESPONSES) }
            assertTrue(error.retryable, body)
        }
    }

    @Test
    fun chatContentThatIsNullOrMissingIsNoOutput() {
        for (body in listOf(
            """{"choices":[{"message":{"content":null}}]}""",
            """{"choices":[{"message":{}}]}""",
            """{"choices":[]}""",
            """{"choices":null}""",
            """{}""",
        )) {
            server.enqueue(reply(body))
            val error = assertFailsWith<ImageGenException>(body) { plan(MagicApi.CHAT) }
            assertContains(error.message!!, "no magic prompt output", message = body)
        }
    }

    @Test
    fun anEmptyReplyBodyIsAnImageGenException() {
        for (api in MagicApi.entries) {
            server.enqueue(MockResponse().setBody(""))
            assertFailsWith<ImageGenException> { plan(api) }
        }
    }

    @Test
    fun theTitleIsTrimmedAfterTruncationInTheFullFlow() {
        server.enqueue(reply(outputText("""{"title":"  ${"b".repeat(21)} zzzz  ","prompt":"  do it  "}""")))
        val result = plan(MagicApi.RESPONSES)
        assertEquals("b".repeat(21), result.title)
        assertEquals("do it", result.prompt)
    }
}
