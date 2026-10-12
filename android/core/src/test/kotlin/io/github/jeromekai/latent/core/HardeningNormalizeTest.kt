package io.github.jeromekai.latent.core

import io.github.jeromekai.latent.core.model.AppTheme
import io.github.jeromekai.latent.core.model.Defaults
import io.github.jeromekai.latent.core.model.GenerationMetadata
import io.github.jeromekai.latent.core.model.ImageApi
import io.github.jeromekai.latent.core.model.MagicHistoryEntry
import io.github.jeromekai.latent.core.model.MagicApi
import io.github.jeromekai.latent.core.model.ModelPreset
import io.github.jeromekai.latent.core.model.Normalize
import io.github.jeromekai.latent.core.model.OutputFormat
import io.github.jeromekai.latent.core.model.Prompt
import io.github.jeromekai.latent.core.model.Settings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Normalisation fed everything a hand-edited, truncated or hostile file can hold. */
class HardeningNormalizeTest {
    private val ID_SHAPE = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    private val FILENAME_SHAPE = Regex("[A-Za-z0-9._-]*")
    private val USERNAME_SHAPE = Regex("[a-z0-9._-]*")

    /** One of every JSON shape a field could unexpectedly hold. */
    private val garbage: List<Pair<String, JsonElement>> = listOf(
        "null" to JsonNull,
        "true" to JsonPrimitive(true),
        "false" to JsonPrimitive(false),
        "0" to JsonPrimitive(0),
        "1" to JsonPrimitive(1),
        "-1" to JsonPrimitive(-1),
        "1.5" to JsonPrimitive(1.5),
        "1e300" to JsonPrimitive(1e300),
        "long max" to JsonPrimitive(Long.MAX_VALUE),
        "empty string" to JsonPrimitive(""),
        "blank string" to JsonPrimitive("  \n\t "),
        "string null" to JsonPrimitive("null"),
        "string false" to JsonPrimitive("false"),
        "string 0" to JsonPrimitive("0"),
        "string 12" to JsonPrimitive(" 12 "),
        "string 1e3" to JsonPrimitive("1e3"),
        "traversal" to JsonPrimitive("../../etc/passwd"),
        "backslashes" to JsonPrimitive("..\\..\\x"),
        "nul" to JsonPrimitive("a\u0000b"),
        "emoji" to JsonPrimitive("🧀".repeat(50)),
        "lone surrogate" to JsonPrimitive("a\uD83Db"),
        "long" to JsonPrimitive("x".repeat(100_000)),
        "empty array" to JsonArray(emptyList()),
        "mixed array" to JsonArray(listOf(JsonNull, JsonPrimitive(1), JsonArray(emptyList()), JsonPrimitive("s"))),
        "empty object" to JsonObject(emptyMap()),
        "nested object" to buildJsonObject { put("a", buildJsonObject { putJsonArray("b") { add(JsonNull) } }) },
        "deep array" to nest(300),
    )

    private fun nest(depth: Int): JsonElement {
        var element: JsonElement = JsonArray(emptyList())
        repeat(depth) { element = JsonArray(listOf(element)) }
        return element
    }

    private fun codePoints(text: String) = text.codePointCount(0, text.length)

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

    private val alphabet = "abcXYZ019 -_./\\%.\t\n\u00a0\u3000\u200b\u202e\u0000\u0301é日🧀\uD83D".toList()

    private fun randomText(random: Random, maxLength: Int = 40) =
        buildString { repeat(random.nextInt(0, maxLength)) { append(alphabet[random.nextInt(alphabet.size)]) } }

    // ---- scalars --------------------------------------------------------------

    @Test
    fun textMirrorsPythonsStrOfValueOrEmpty() {
        assertEquals("", Normalize.text(null))
        assertEquals("", Normalize.text(JsonNull))
        assertEquals("", Normalize.text(JsonPrimitive(false)))
        assertEquals("", Normalize.text(JsonPrimitive(0)))
        assertEquals("", Normalize.text(JsonPrimitive(0.0)))
        assertEquals("true", Normalize.text(JsonPrimitive(true)))
        assertEquals("12", Normalize.text(JsonPrimitive(12)))
        // A STRING "0" or "false" is truthy in Python and is kept.
        assertEquals("0", Normalize.text(JsonPrimitive("0")))
        assertEquals("false", Normalize.text(JsonPrimitive("false")))
        assertEquals("null", Normalize.text(JsonPrimitive("null")))
        assertEquals("[1,2]", Normalize.text(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2)))))
    }

    @Test
    fun titlesAreCutByCodePointNeverInsideASurrogatePair() {
        for (length in 0..60) {
            for (prefix in listOf("", "a")) {
                val input = prefix + "🧀".repeat(length)
                val title = Normalize.promptTitle(JsonPrimitive(input))
                assertTrue(codePoints(title) <= Normalize.PROMPT_TITLE_MAX_LENGTH, "length $length")
                assertFalse(hasLoneSurrogate(title), "length $length")
                assertTrue(input.startsWith(title) || title == Normalize.DEFAULT_NEW_PROMPT_TITLE, "length $length")
            }
        }
    }

    @Test
    fun astralCharactersCountOnceNotTwice() {
        val twentyTwo = "🧀".repeat(22)
        assertEquals(44, twentyTwo.length)
        assertEquals(twentyTwo, Normalize.promptTitle(JsonPrimitive(twentyTwo)), "22 code points is within the limit")
        assertEquals(twentyTwo, Normalize.promptTitle(JsonPrimitive(twentyTwo + "🧀")), "the 23rd is cut")
        assertEquals("a".repeat(22), Normalize.promptTitle(JsonPrimitive("a".repeat(22))))
        assertEquals("a".repeat(22), Normalize.promptTitle(JsonPrimitive("a".repeat(23))))
    }

    @Test
    fun aTitleThatTrimsToNothingFallsBackToTheDefault() {
        assertEquals(Normalize.DEFAULT_NEW_PROMPT_TITLE, Normalize.promptTitle(JsonPrimitive("   ")))
        assertEquals(Normalize.DEFAULT_NEW_PROMPT_TITLE, Normalize.promptTitle(JsonNull))
        assertEquals(Normalize.DEFAULT_NEW_PROMPT_TITLE, Normalize.promptTitle(JsonPrimitive(0)))
        assertEquals(Normalize.DEFAULT_NEW_PROMPT_BODY, Normalize.promptBody(JsonPrimitive(false)))
        assertEquals(Normalize.DEFAULT_NEW_PROMPT_BODY, Normalize.promptBody(JsonPrimitive("\n\t ")))
    }

    @Test
    fun hugeInputsAreHandledQuickly() {
        val huge = JsonPrimitive("ab c\u00a0d-_".repeat(500_000)) // 4 million characters
        val started = System.nanoTime()
        assertEquals(22, codePoints(Normalize.promptTitle(huge)))
        assertEquals(4_000_000, Normalize.promptBody(huge).length)
        Normalize.uniqueId(huge, mutableSetOf(), "prompt", 1)
        assertTrue(Normalize.referenceImage(huge).length <= Normalize.REFERENCE_IMAGE_MAX_LENGTH)
        assertTrue(Normalize.cameraUsername((huge.content)).length <= Normalize.CAMERA_USERNAME_MAX_LENGTH)
        assertTrue(codePoints(Normalize.modelLabel(huge, "fallback")) <= Normalize.MODEL_LABEL_MAX_LENGTH)
        assertTrue((System.nanoTime() - started) / 1e9 < 10, "regexes must stay linear")
    }

    @Test
    fun tenThousandPromptsNormaliseQuickly() {
        val raw = buildJsonArray {
            repeat(10_000) { n ->
                add(buildJsonObject { put("id", "p$n"); put("title", "Title $n"); put("body", "Body $n") })
            }
        }
        val started = System.nanoTime()
        val prompts = Normalize.prompts(raw)
        assertEquals(10_000, prompts.size)
        assertEquals(10_000, prompts.map { it.id }.toSet().size)
        assertTrue((System.nanoTime() - started) / 1e9 < 5)
    }

    // ---- ids ------------------------------------------------------------------

    @Test
    fun duplicateIdsGetIncreasingSuffixesWithoutCollidingWithLaterExplicitOnes() {
        val used = mutableSetOf<String>()
        val ids = listOf("x", "x-2", "x", "X", " x ").map { Normalize.uniqueId(JsonPrimitive(it), used, "prompt", 1) }
        assertEquals(listOf("x", "x-2", "x-3", "x-4", "x-5"), ids)
    }

    @Test
    fun missingIdsFallBackToTheirPositionAndStayUniqueAgainstExplicitOnes() {
        val prompts = Normalize.prompts(
            buildJsonArray {
                add(buildJsonObject { put("id", "prompt-2"); put("body", "a") })
                add(buildJsonObject { put("body", "b") }) // fallback prompt-2 collides
                add(buildJsonObject { put("id", JsonNull); put("body", "c") })
            },
        )
        assertEquals(listOf("prompt-2", "prompt-2-2", "prompt-3"), prompts.map { it.id })
    }

    @Test
    fun idsAreReducedToLowercaseAsciiWordsAndHyphens() {
        val cases = mapOf(
            "Hello World!" to "hello-world",
            "  --a--  " to "a",
            "A.B/C\\D" to "a-b-c-d",
            "É" to "prompt-1",
            "🧀🧀" to "prompt-1",
            "" to "prompt-1",
            "   " to "prompt-1",
            "---" to "prompt-1",
            "ПРИВЕТ" to "prompt-1",
            "x_y" to "x-y",
        )
        for ((input, expected) in cases) {
            assertEquals(expected, Normalize.uniqueId(JsonPrimitive(input), mutableSetOf(), "prompt", 1), "'$input'")
        }
    }

    @Test
    fun randomIdsAreAlwaysWellFormedAndUnique() {
        val random = Random(7)
        val used = mutableSetOf<String>()
        repeat(5_000) { n ->
            val id = Normalize.uniqueId(JsonPrimitive(randomText(random)), used, "prompt", n + 1)
            assertTrue(ID_SHAPE.matches(id), "'$id'")
        }
        assertEquals(5_000, used.size, "every call must have produced a fresh id")
    }

    // ---- usernames and filenames ------------------------------------------------

    @Test
    fun usernamesHandleUnicodeWhitespaceAndInvisibles() {
        assertEquals("jerome-k", Normalize.cameraUsername("Jerome\u00a0K"))
        assertEquals("jerome-k", Normalize.cameraUsername("Jerome\u3000K"))
        assertEquals("jerome-k", Normalize.cameraUsername("  Jerome \t\n K "))
        assertEquals("ab", Normalize.cameraUsername("a\u200Bb"))
        assertEquals("ab", Normalize.cameraUsername("a\u202Eb"))
        assertEquals("", Normalize.cameraUsername("🧀🧀🧀"))
        assertEquals("", Normalize.cameraUsername("日本語"))
        assertEquals("", Normalize.cameraUsername("---..__"))
        assertEquals("a..b", Normalize.cameraUsername("..a..b.."))
        assertEquals("b", Normalize.cameraUsername("ÀB"))
    }

    @Test
    fun usernamesAreCappedAndNeverStartOrEndWithSeparators() {
        val capped = Normalize.cameraUsername("a".repeat(10_000))
        assertEquals(Normalize.CAMERA_USERNAME_MAX_LENGTH, capped.length)
        // A cut that lands right after a hyphen must not leave it dangling.
        val cutOnHyphen = Normalize.cameraUsername("a".repeat(31) + "-bbbb")
        assertEquals("a".repeat(31), cutOnHyphen)
    }

    @Test
    fun randomUsernamesAlwaysMatchTheirShape() {
        val random = Random(11)
        repeat(5_000) {
            val name = Normalize.cameraUsername(randomText(random, 80))
            assertTrue(USERNAME_SHAPE.matches(name), "'$name'")
            assertTrue(name.length <= Normalize.CAMERA_USERNAME_MAX_LENGTH)
            assertTrue(name.isEmpty() || (name.first() !in ".-_" && name.last() !in ".-_"), "'$name'")
        }
    }

    @Test
    fun referenceImageNamesCollapseToASafeBasename() {
        val cases = mapOf(
            "../x.jpg" to "x.jpg",
            "..\\..\\x.jpg" to "x.jpg",
            "/abs/path/pic.png" to "pic.png",
            "C:\\pics\\a.jpg" to "a.jpg",
            "dir/" to "",
            "dir/." to "",
            "dir/.." to "",
            "a/b/.." to "",
            ".." to "",
            "." to "",
            "..%" to "",
            ".%." to "",
            "..\u0000" to "",
            "" to "",
            "   " to "",
            "a b.jpg" to "ab.jpg",
            "a\u0000b.jpg" to "ab.jpg",
            "%2e%2e" to "2e2e",
            "日本語.jpg" to ".jpg",
        )
        for ((input, expected) in cases) {
            assertEquals(expected, Normalize.referenceImage(JsonPrimitive(input)), "'${input.replace("\u0000", "\\0")}'")
        }
        assertEquals("", Normalize.referenceImage(JsonNull))
        assertEquals("", Normalize.referenceImage(null))
        assertEquals("5", Normalize.referenceImage(JsonPrimitive(5)))
        assertEquals("", Normalize.referenceImage(JsonPrimitive(0)))
    }

    @Test
    fun aLongReferenceNameIsCutToItsLimit() {
        val name = Normalize.referenceImage(JsonPrimitive("a".repeat(300) + ".jpg"))
        assertEquals(Normalize.REFERENCE_IMAGE_MAX_LENGTH, name.length)
    }

    @Test
    fun randomReferenceNamesAreAlwaysSafeFilenames() {
        val random = Random(13)
        repeat(20_000) {
            val name = Normalize.referenceImage(JsonPrimitive(randomText(random, 60)))
            assertTrue(FILENAME_SHAPE.matches(name), "'$name'")
            assertTrue(name != "." && name != "..", "'$name' is a traversal component")
            assertTrue(name.length <= Normalize.REFERENCE_IMAGE_MAX_LENGTH)
        }
    }

    // ---- model labels and presets -----------------------------------------------

    @Test
    fun labelsCollapseWhitespaceAndCutByCodePoint() {
        assertEquals("a b c", Normalize.modelLabel(JsonPrimitive("a \t\n b\u00a0\u00a0c"), "fb"))
        assertEquals("🧀".repeat(18), Normalize.modelLabel(JsonPrimitive("🧀".repeat(40)), "fb"))
        assertEquals("fb", Normalize.modelLabel(JsonPrimitive("  \n "), "fb"))
        assertEquals("fb", Normalize.modelLabel(JsonNull, "fb"))
        assertEquals("a".repeat(17), Normalize.modelLabel(JsonPrimitive("a".repeat(17) + " " + "b".repeat(20)), "fb"), "a cut before a space drops the space")
    }

    @Test
    fun aModelNameEndingInASlashStillGetsALabel() {
        // The default label is the part after the last "/", which is empty here.
        for (model in listOf("vendor/", "/", "a/b/")) {
            val preset = Normalize.modelPresets(buildJsonArray { add(buildJsonObject { put("model", model) }) }).single()
            assertTrue(preset.label.isNotBlank(), "label for '$model' is '${preset.label}'")
        }
    }

    @Test
    fun presetsDropEntriesWithoutAModelAndCoerceTheRest() {
        val raw = buildJsonArray {
            add(buildJsonObject { put("model", 5) })
            add(buildJsonObject { put("model", JsonNull); put("label", "ghost") })
            add(buildJsonObject { put("model", "  m  "); put("api", " CHAT ") })
            add(buildJsonObject { put("model", "n"); put("api", "nonsense") })
            add(JsonPrimitive("a string"))
            add(JsonNull)
            add(buildJsonArray { })
        }
        val presets = Normalize.modelPresets(raw)
        assertEquals(listOf("5", "m", "n"), presets.map { it.model })
        assertEquals(listOf(ImageApi.EDITS, ImageApi.CHAT, ImageApi.EDITS), presets.map { it.api })
    }

    @Test
    fun presetsOfTheWrongTypeFallBackToTheGivenDefaults() {
        val defaults = listOf(ModelPreset("d", "d", "d", ImageApi.EDITS))
        for ((name, value) in garbage) {
            if (value is JsonArray) continue
            assertEquals(defaults, Normalize.modelPresets(value, defaults), name)
        }
        assertEquals(defaults, Normalize.modelPresets(buildJsonArray { add(JsonNull); add(JsonPrimitive(1)) }, defaults))
    }

    // ---- garbage in every field --------------------------------------------------

    @Test
    fun everyPromptFieldSurvivesEveryKindOfGarbage() {
        for (key in listOf("id", "title", "body", "reference_image")) {
            for ((name, value) in garbage) {
                val raw = buildJsonArray {
                    add(buildJsonObject {
                        put("id", "one"); put("title", "One"); put("body", "Body one"); put("reference_image", "one.jpg")
                        put(key, value)
                    })
                    add(buildJsonObject { put("id", "one"); put("title", "Two"); put("body", "Body two") })
                }
                val prompts = Normalize.prompts(raw)
                assertEquals(2, prompts.size, "$key=$name")
                assertEquals(2, prompts.map { it.id }.toSet().size, "$key=$name ids must stay unique")
                for (prompt in prompts) {
                    assertTrue(ID_SHAPE.matches(prompt.id), "$key=$name id '${prompt.id}'")
                    assertTrue(prompt.title.isNotBlank() && prompt.title == prompt.title.trim(), "$key=$name title")
                    assertTrue(codePoints(prompt.title) <= Normalize.PROMPT_TITLE_MAX_LENGTH, "$key=$name title length")
                    assertTrue(prompt.body.isNotBlank(), "$key=$name body")
                    assertTrue(FILENAME_SHAPE.matches(prompt.referenceImage) && prompt.referenceImage != ".." && prompt.referenceImage != ".", "$key=$name ref")
                }
            }
        }
    }

    @Test
    fun theLegacyMapFormSurvivesGarbageToo() {
        for ((name, value) in garbage) {
            val flat = Normalize.prompts(buildJsonObject { put("legacy", value); put("Legacy!", value) })
            assertEquals(2, flat.size, name)
            assertEquals(2, flat.map { it.id }.toSet().size, name)
            val nested = Normalize.prompts(buildJsonObject {
                put("k", buildJsonObject { put("id", value); put("title", value); put("body", value); put("reference_image", value) })
            })
            assertEquals(1, nested.size, name)
            assertTrue(nested.single().body.isNotBlank(), name)
        }
    }

    @Test
    fun aListOfNothingUsableBecomesTheDefaultsAndStaysEmptyWhenAsked() {
        val junk = buildJsonArray { add(JsonNull); add(JsonPrimitive(1)); add(JsonPrimitive("x")); add(JsonArray(emptyList())) }
        assertEquals(Defaults.prompts(), Normalize.prompts(junk))
        assertEquals(emptyList<Prompt>(), Normalize.prompts(junk, ensureDefaultsWhenEmpty = false))
        for ((name, value) in garbage) {
            if (value is JsonObject && value.isNotEmpty()) continue // a non-empty object is the legacy map form
            assertEquals(Defaults.prompts(), Normalize.prompts(value), name)
        }
    }

    @Test
    fun everyPresetFieldSurvivesEveryKindOfGarbage() {
        for (key in listOf("id", "label", "model", "api")) {
            for ((name, value) in garbage) {
                val raw = buildJsonArray {
                    add(buildJsonObject { put("id", "a"); put("label", "A"); put("model", "model-a"); put("api", "chat"); put(key, value) })
                    add(buildJsonObject { put("id", "a"); put("model", "model-b") })
                }
                val presets = Normalize.modelPresets(raw)
                assertTrue(presets.isNotEmpty(), "$key=$name")
                assertEquals(presets.size, presets.map { it.id }.toSet().size, "$key=$name")
                for (preset in presets) {
                    assertTrue(ID_SHAPE.matches(preset.id), "$key=$name id '${preset.id}'")
                    assertTrue(preset.model.isNotBlank() && preset.model == preset.model.trim(), "$key=$name model")
                    assertTrue(preset.label.isNotBlank() && codePoints(preset.label) <= Normalize.MODEL_LABEL_MAX_LENGTH, "$key=$name label '${preset.label}'")
                }
            }
        }
    }

    @Test
    fun everyMagicHistoryFieldSurvivesEveryKindOfGarbage() {
        for (key in listOf("id", "created_at", "title", "body", "reference_capture_path", "promoted_prompt_id")) {
            for ((name, value) in garbage) {
                val raw = buildJsonArray {
                    add(buildJsonObject {
                        put("id", "m1"); put("created_at", "2026-10-11T12:00:00"); put("title", "T"); put("body", "B")
                        put("reference_capture_path", "captures/x.jpg"); put("promoted_prompt_id", "prompt-1")
                        put(key, value)
                    })
                    add(buildJsonObject { put("id", "m1") })
                }
                val entries = Normalize.magicHistory(raw)
                assertEquals(2, entries.size, "$key=$name")
                assertEquals(2, entries.map { it.id }.toSet().size, "$key=$name")
                for (entry in entries) {
                    assertTrue(ID_SHAPE.matches(entry.id), "$key=$name id '${entry.id}'")
                    assertTrue(entry.title.isNotBlank() && codePoints(entry.title) <= Normalize.PROMPT_TITLE_MAX_LENGTH, "$key=$name title")
                    assertTrue(entry.body.isNotBlank(), "$key=$name body")
                    assertTrue(entry.referenceCapturePath == null || entry.referenceCapturePath.isNotBlank(), "$key=$name ref")
                    assertTrue(entry.promotedPromptId == null || entry.promotedPromptId.isNotBlank(), "$key=$name promoted")
                }
                assertEquals(entries, Normalize.magicHistory(Normalize.magicHistoryToJson(entries)), "$key=$name must be a fixed point")
            }
        }
        for ((name, value) in garbage) assertEquals(emptyList<MagicHistoryEntry>(), Normalize.magicHistory(value), name)
    }

    @Test
    fun everyMetadataFieldSurvivesEveryKindOfGarbage() {
        val keys = listOf(
            "capture_path", "prompt_id", "prompt_title", "prompt_body", "model", "api", "created_at",
            "magic_reference_path", "magic_history_id", "variant", "source_generated_path", "reference_paths",
        )
        for (key in keys) {
            for ((name, value) in garbage) {
                val meta = Normalize.metadata(buildJsonObject { put(key, value) })
                assertEquals(meta, Normalize.metadata(Normalize.metadataToJson(meta)), "$key=$name must be a fixed point")
                for (path in meta.referencePaths) assertTrue(path.isNotBlank() && path == path.trim(), "$key=$name")
            }
        }
        for ((name, value) in garbage) assertEquals(GenerationMetadata(), Normalize.metadata(value), name)
    }

    @Test
    fun referencePathsKeepOnlyNonBlankTrimmedEntries() {
        val meta = Normalize.metadata(buildJsonObject {
            put("reference_paths", buildJsonArray {
                add(JsonNull); add(JsonPrimitive(" a ")); add(JsonPrimitive("")); add(JsonPrimitive(0)); add(JsonPrimitive(7))
                add(JsonArray(emptyList())); add(JsonPrimitive("b"))
            })
        })
        assertEquals(listOf("a", "7", "[]", "b"), meta.referencePaths)
    }

    // ---- settings ---------------------------------------------------------------

    private class IntField(val key: String, val min: Int, val max: Int, val read: (Settings) -> Int)

    private val intFields = listOf(
        IntField("output_compression", 0, 100) { it.outputCompression },
        IntField("image_timeout_seconds", 10, 600) { it.imageTimeoutSeconds },
        IntField("input_width", 256, 4096) { it.inputWidth },
        IntField("input_height", 256, 4096) { it.inputHeight },
        IntField("magic_mode_timeout_seconds", 5, 300) { it.magicTimeoutSeconds },
        IntField("queue_max_attempts", 0, 100) { it.maxAttempts },
        IntField("max_pending_generations", 0, 1000) { it.maxPendingGenerations },
    )

    private fun settingsWith(key: String, value: JsonElement) = Normalize.settings(buildJsonObject { put(key, value) })

    @Test
    fun everyBoundedSettingClampsAtBothEdges() {
        for (field in intFields) {
            fun read(value: JsonElement) = field.read(settingsWith(field.key, value))
            assertEquals(field.min, read(JsonPrimitive(field.min - 1)), "${field.key} below")
            assertEquals(field.min, read(JsonPrimitive(field.min)), "${field.key} at min")
            assertEquals(field.max, read(JsonPrimitive(field.max)), "${field.key} at max")
            assertEquals(field.max, read(JsonPrimitive(field.max + 1)), "${field.key} above")
            assertEquals(field.max, read(JsonPrimitive(Int.MAX_VALUE)), "${field.key} Int.MAX")
            assertEquals(field.min, read(JsonPrimitive(Int.MIN_VALUE)), "${field.key} Int.MIN")
            val mid = (field.min + field.max) / 2
            assertEquals(mid, read(JsonPrimitive(mid)), "${field.key} mid")
        }
    }

    @Test
    fun numbersBeyondIntRangeSaturateInsteadOfFallingBackToTheDefault() {
        // Python's int() has no upper limit and the camera then clamped; a huge
        // value used to land on the default, which can be nowhere near the limit.
        for (field in intFields) {
            fun read(value: JsonElement) = field.read(settingsWith(field.key, value))
            assertEquals(field.max, read(JsonPrimitive(Long.MAX_VALUE)), "${field.key} Long.MAX")
            assertEquals(field.min, read(JsonPrimitive(Long.MIN_VALUE)), "${field.key} Long.MIN")
            assertEquals(field.max, read(JsonPrimitive(2_147_483_648L)), "${field.key} Int.MAX+1")
            assertEquals(field.max, read(JsonPrimitive("99999999999999999999999")), "${field.key} beyond Long, as text")
            assertEquals(field.min, read(JsonPrimitive("-99999999999999999999999")), "${field.key} below Long, as text")
        }
    }

    @Test
    fun numbersWrittenAsTextAreAcceptedWithPaddingAndSign() {
        for (field in intFields) {
            fun read(value: JsonElement) = field.read(settingsWith(field.key, value))
            val mid = (field.min + field.max) / 2
            assertEquals(mid, read(JsonPrimitive("$mid")), field.key)
            assertEquals(mid, read(JsonPrimitive("  $mid\n")), field.key)
            assertEquals(mid, read(JsonPrimitive("+$mid")), field.key)
        }
    }

    @Test
    fun unparseableSettingsFallBackToTheirDefaultAndNothingEverEscapesItsBounds() {
        val defaults = Settings()
        for (field in intFields) {
            val default = field.read(defaults)
            for ((name, value) in garbage) {
                val read = field.read(settingsWith(field.key, value))
                assertTrue(read in field.min..field.max, "${field.key}=$name -> $read")
            }
            for (junk in listOf("abc", "", "  ", "1,5", "0x10", "twelve", "--5", "5-")) {
                assertEquals(default, field.read(settingsWith(field.key, JsonPrimitive(junk))), "${field.key}='$junk'")
            }
            assertEquals(default, field.read(settingsWith(field.key, JsonNull)), "${field.key} null")
            assertEquals(default, field.read(settingsWith(field.key, JsonPrimitive(true))), "${field.key} true")
            assertEquals(default, field.read(settingsWith(field.key, JsonArray(emptyList()))), "${field.key} []")
            assertEquals(default, field.read(settingsWith(field.key, JsonObject(emptyMap()))), "${field.key} {}")
        }
    }

    @Test
    fun theFlagsAcceptTheSpellingsPythonDid() {
        fun flag(value: JsonElement) = settingsWith("magic_mode_enabled", value).magicEnabled
        for (yes in listOf("1", "true", "TRUE", " Yes ", "on", "ON")) assertTrue(flag(JsonPrimitive(yes)), yes)
        for (no in listOf("0", "false", "False", " NO ", "off")) assertFalse(flag(JsonPrimitive(no)), no)
        assertTrue(flag(JsonPrimitive(true)))
        assertFalse(flag(JsonPrimitive(false)))
        assertTrue(flag(JsonPrimitive(1)))
        assertFalse(flag(JsonPrimitive(0)))
        // Anything else keeps the default (true for this one).
        for (junk in listOf("", "maybe", "2", "y")) assertTrue(flag(JsonPrimitive(junk)), "'$junk'")
        assertTrue(flag(JsonNull))
        assertTrue(flag(JsonArray(emptyList())))
        // And a default-false flag stays false on junk.
        assertFalse(settingsWith("tutorial_seen", JsonPrimitive("maybe")).tutorialSeen)
    }

    @Test
    fun theSizeIsAcceptedHowPeopleActuallyTypeIt() {
        fun size(value: JsonElement) = settingsWith("size", value).size
        assertEquals("1024x1536", size(JsonPrimitive(" 1024 x 1536 ")))
        assertEquals("1024x1536", size(JsonPrimitive("1024x1536\n")))
        // A capital X or a multiplication sign is what a phone keyboard or a pasted doc produces.
        assertEquals("1024x1536", size(JsonPrimitive("1024X1536")))
        assertEquals("1024x1536", size(JsonPrimitive("1024\u00d71536")))
    }

    @Test
    fun aMalformedSizeKeepsTheDefault() {
        val default = Settings().size
        for (junk in listOf("1024*1536", "1024x", "x1024", "x", "5x5", "123456x123456", "1e3x1e3", "1024 1536", "", "auto", "-1024x1536")) {
            assertEquals(default, settingsWith("size", JsonPrimitive(junk)).size, "'$junk'")
        }
        assertEquals(default, settingsWith("size", JsonPrimitive(1536)).size)
        assertEquals(default, settingsWith("size", JsonNull).size)
    }

    @Test
    fun enumeratedSettingsAcceptCaseAndPaddingOrKeepTheirDefault() {
        assertEquals("high", settingsWith("quality", JsonPrimitive(" HIGH ")).quality)
        assertEquals("auto", settingsWith("quality", JsonPrimitive("Auto")).quality)
        assertEquals("low", settingsWith("quality", JsonPrimitive("ultra")).quality)
        assertEquals("low", settingsWith("quality", JsonNull).quality)
        assertEquals("low", settingsWith("quality", JsonPrimitive(5)).quality)

        assertEquals(OutputFormat.WEBP, settingsWith("output_format", JsonPrimitive("WEBP ")).outputFormat)
        assertEquals(OutputFormat.PNG, settingsWith("output_format", JsonPrimitive("Png")).outputFormat)
        assertEquals(OutputFormat.JPEG, settingsWith("output_format", JsonPrimitive("gif")).outputFormat)

        assertEquals(MagicApi.CHAT, settingsWith("magic_mode_api", JsonPrimitive(" CHAT")).magicApi)
        assertEquals(MagicApi.RESPONSES, settingsWith("magic_mode_api", JsonPrimitive("completions")).magicApi)

        assertEquals(AppTheme.MINT, settingsWith("app_background_theme", JsonPrimitive(" mint ")).theme)
        assertEquals(AppTheme.AQUA, settingsWith("app_background_theme", JsonPrimitive("neon")).theme)
        assertEquals(AppTheme.AQUA, settingsWith("app_background_theme", JsonNull).theme)
    }

    @Test
    fun theBaseUrlLosesPaddingAndTrailingSlashesAndNothingElse() {
        assertEquals("https://x/v1", settingsWith("base_url", JsonPrimitive("https://x/v1///")).baseUrl)
        assertEquals("https://x/v1", settingsWith("base_url", JsonPrimitive("  https://x/v1/  ")).baseUrl)
        assertEquals("https://x/v1?key=a/b", settingsWith("base_url", JsonPrimitive("https://x/v1?key=a/b")).baseUrl)
        assertEquals("", settingsWith("base_url", JsonNull).baseUrl)
        assertEquals("", settingsWith("base_url", JsonPrimitive("/")).baseUrl)
    }

    @Test
    fun selectedIdsAreTrimmedTextAndNullIsEmpty() {
        assertEquals("prompt-2", settingsWith("selected_prompt_id", JsonPrimitive("  prompt-2 ")).selectedPromptId)
        assertEquals("", settingsWith("selected_prompt_id", JsonNull).selectedPromptId)
        assertEquals("", settingsWith("selected_model_id", JsonPrimitive(0)).selectedModelId)
    }

    @Test
    fun everySettingSurvivesEveryKindOfGarbageAndTheResultIsAFixedPoint() {
        val keys = Normalize.settingsToJson(Settings()).let { (it as JsonObject).keys }
        for (key in keys) {
            for ((name, value) in garbage) {
                val settings = settingsWith(key, value)
                assertTrue(USERNAME_SHAPE.matches(settings.cameraUsername), "$key=$name")
                assertEquals(settings, Normalize.settings(Normalize.settingsToJson(settings)), "$key=$name must be a fixed point")
            }
        }
    }

    @Test
    fun settingsOfTheWrongTopLevelTypeAreTheDefaults() {
        assertEquals(Settings(), Normalize.settings(null))
        for ((name, value) in garbage) {
            if (value is JsonObject && value.isNotEmpty()) continue
            assertEquals(Settings(), Normalize.settings(value), name)
        }
    }

    @Test
    fun randomValidSettingsRoundTripExactly() {
        val random = Random(99)
        repeat(500) {
            val settings = Settings(
                theme = AppTheme.entries.random(random),
                cameraUsername = Normalize.cameraUsername(randomText(random)),
                tutorialSeen = random.nextBoolean(),
                baseUrl = listOf("", "https://gw.example/v1", "http://192.168.1.5:8080/v1").random(random),
                quality = listOf("low", "medium", "high", "auto").random(random),
                size = "${random.nextInt(10, 99999)}x${random.nextInt(10, 99999)}",
                outputFormat = OutputFormat.entries.random(random),
                outputCompression = random.nextInt(0, 101),
                imageTimeoutSeconds = random.nextInt(10, 601),
                inputWidth = random.nextInt(256, 4097),
                inputHeight = random.nextInt(256, 4097),
                magicEnabled = random.nextBoolean(),
                magicApi = MagicApi.entries.random(random),
                magicModel = "model-${random.nextInt()}",
                magicTimeoutSeconds = random.nextInt(5, 301),
                maxAttempts = random.nextInt(0, 101),
                maxPendingGenerations = random.nextInt(0, 1001),
                selectedPromptId = listOf("", "prompt-1", "x").random(random),
                selectedModelId = listOf("", "default").random(random),
                saveToPhoneGallery = random.nextBoolean(),
                volumeKeyShutter = random.nextBoolean(),
            )
            assertEquals(settings, Normalize.settings(Normalize.settingsToJson(settings)))
        }
    }

    // ---- the shipped defaults ------------------------------------------------------

    @Test
    fun theShippedDefaultsAreFixedPointsOfNormalisation() {
        val prompts = Defaults.prompts()
        assertEquals(5, prompts.size)
        assertEquals(prompts, Normalize.prompts(Normalize.promptsToJson(prompts)))
        assertEquals(prompts.size, prompts.map { it.id }.toSet().size)
        assertTrue(prompts.all { codePoints(it.title) <= Normalize.PROMPT_TITLE_MAX_LENGTH && it.referenceImage.isEmpty() })

        val presets = Defaults.modelPresets()
        assertEquals(presets, Normalize.modelPresets(Normalize.modelPresetsToJson(presets)))
    }

    @Test
    fun everyProviderRecipeSurvivesTheNormalisationItWillBeStoredThrough() {
        val recipes = Defaults.providerRecipes
        assertEquals(recipes.size, recipes.map { it.id }.toSet().size)
        for (recipe in recipes) {
            assertEquals(
                listOf(recipe.model),
                Normalize.modelPresets(Normalize.modelPresetsToJson(listOf(recipe.model))),
                "${recipe.id}: the preset would be altered when saved",
            )
            assertTrue(recipe.magicModel.isNotBlank(), recipe.id)
            assertTrue(recipe.baseUrl.isEmpty() || (recipe.baseUrl.startsWith("https://") && !recipe.baseUrl.endsWith("/")), recipe.id)
            val applied = Normalize.settings(Normalize.settingsToJson(Settings(baseUrl = recipe.baseUrl, magicApi = recipe.magicApi, magicModel = recipe.magicModel)))
            assertEquals(recipe.baseUrl, applied.baseUrl, recipe.id)
            assertEquals(recipe.magicModel, applied.magicModel, recipe.id)
            assertEquals(recipe.magicApi, applied.magicApi, recipe.id)
        }
    }

    @Test
    fun wireNamesRoundTripAndUnknownNamesNeverThrow() {
        for (api in ImageApi.entries) assertEquals(api, ImageApi.fromWire(api.wire))
        for (api in MagicApi.entries) assertEquals(api, MagicApi.fromWire(api.wire))
        for (format in OutputFormat.entries) assertEquals(format, OutputFormat.fromWire(format.wire))
        for (theme in AppTheme.entries) assertEquals(theme, AppTheme.fromWire(theme.wire))
        assertEquals(ImageApi.EDITS, ImageApi.fromWire(null))
        assertEquals(MagicApi.RESPONSES, MagicApi.fromWire(null))
        assertEquals(OutputFormat.JPEG, OutputFormat.fromWire(null))
        assertEquals(AppTheme.AQUA, AppTheme.fromWire(null))
        assertNull(ImageApi.entries.firstOrNull { it.wire != it.wire.lowercase() }, "wire names are lowercase")
    }
}
