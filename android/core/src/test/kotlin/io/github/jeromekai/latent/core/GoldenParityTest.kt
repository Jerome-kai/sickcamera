package io.github.jeromekai.latent.core

import io.github.jeromekai.latent.core.api.OpenAiImageEditor
import io.github.jeromekai.latent.core.api.OpenAiMagicPlanner
import io.github.jeromekai.latent.core.model.Defaults
import io.github.jeromekai.latent.core.model.ModelPreset
import io.github.jeromekai.latent.core.model.Normalize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The port against the Python camera's own output, recorded by
 * android/tools/export_golden.py running the real functions. A drift in a
 * prompt string or a normalisation rule fails here instead of quietly
 * changing what the AI is told.
 */
class GoldenParityTest {
    private val golden: JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/golden/python.json")!!.bufferedReader().readText(),
    ).jsonObject

    private fun str(key: String) = golden[key]!!.jsonPrimitive.content

    @Test
    fun wrapperPromptsMatchVerbatim() {
        assertEquals(str("wrapper_prompt_no_refs"), OpenAiImageEditor.wrapPrompt("Turn everyone into cheese.", hasReferences = false))
        assertEquals(str("wrapper_prompt_with_refs"), OpenAiImageEditor.wrapPrompt("Turn everyone into cheese.", hasReferences = true))
    }

    @Test
    fun magicInstructionMatchesVerbatim() {
        assertEquals(str("magic_instruction"), OpenAiMagicPlanner().instructionText())
    }

    @Test
    fun storyPromptMatchesVerbatim() {
        assertEquals(str("story_prompt"), OpenAiImageEditor.STORY_PROMPT)
    }

    @Test
    fun defaultPromptsMatchVerbatim() {
        val expected = golden["default_prompts"]!!.jsonArray.map { it.jsonObject }
        val actual = Defaults.prompts()
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (want, got) ->
            assertEquals(want["id"]!!.jsonPrimitive.content, got.id)
            assertEquals(want["title"]!!.jsonPrimitive.content, got.title)
            assertEquals(want["body"]!!.jsonPrimitive.content, got.body)
        }
    }

    @Test
    fun promptNormalisationMatches() {
        for (case in golden["prompt_cases"]!!.jsonArray) {
            val input = case.jsonObject["input"]
            val expected = case.jsonObject["output"]!!.jsonArray.map {
                Triple(it.jsonObject["id"]!!.jsonPrimitive.content, it.jsonObject["title"]!!.jsonPrimitive.content, it.jsonObject["body"]!!.jsonPrimitive.content)
            }
            val actual = Normalize.prompts(input).map { Triple(it.id, it.title, it.body) }
            assertEquals(expected, actual, "input: $input")
        }
    }

    @Test
    fun usernameNormalisationMatches() {
        for (case in golden["username_cases"]!!.jsonArray) {
            val input = case.jsonObject["input"]!!.jsonPrimitive.content
            assertEquals(case.jsonObject["output"]!!.jsonPrimitive.content, Normalize.cameraUsername(input), "input: '$input'")
        }
    }

    @Test
    fun modelPresetNormalisationMatches() {
        val sentinel = listOf(ModelPreset("default", "fallback", "fallback", io.github.jeromekai.latent.core.model.ImageApi.EDITS))
        for (case in golden["model_cases"]!!.jsonArray) {
            val input = case.jsonObject["input"]
            val expected = (case.jsonObject["output"] as JsonArray).map {
                val o = it.jsonObject
                listOf("id", "label", "model", "api").map { k -> o[k]!!.jsonPrimitive.content }
            }
            val actual = Normalize.modelPresets(input, sentinel).map { listOf(it.id, it.label, it.model, it.api.wire) }
            assertEquals(expected, actual, "input: $input")
        }
    }

    @Test
    fun magicPayloadParsingMatches() {
        val planner = OpenAiMagicPlanner()
        for (case in golden["magic_parse_cases"]!!.jsonArray) {
            val input = case.jsonObject["input"]!!.jsonPrimitive.content
            val want = case.jsonObject["output"]!!.jsonObject
            val got = planner.parse(input)
            assertEquals((want["title"] as JsonPrimitive).content, got.title, "input: $input")
            assertEquals((want["prompt"] as JsonPrimitive).content, got.prompt, "input: $input")
        }
    }
}
