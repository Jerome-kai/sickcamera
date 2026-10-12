package io.github.jeromekai.latent.core

import io.github.jeromekai.latent.core.model.ImageApi
import io.github.jeromekai.latent.core.model.MagicHistoryEntry
import io.github.jeromekai.latent.core.model.ModelPreset
import io.github.jeromekai.latent.core.model.Normalize
import io.github.jeromekai.latent.core.model.Prompt
import io.github.jeromekai.latent.core.store.JsonFiles
import io.github.jeromekai.latent.core.store.MagicHistoryStore
import io.github.jeromekai.latent.core.store.ModelPresetStore
import io.github.jeromekai.latent.core.store.PromptStore
import io.github.jeromekai.latent.core.store.SettingsStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Durable files and the stores on top of them, under duress. */
class HardeningStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(name: String) = File(tmp.root, name)

    /** Run [block] on [count] threads released together; rethrow the first failure. */
    private fun runConcurrently(count: Int, block: (Int) -> Unit) {
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val threads = (0 until count).map { n ->
            thread {
                start.await()
                try { block(n) } catch (error: Throwable) { failures += error }
            }
        }
        start.countDown()
        threads.forEach { it.join(120_000) }
        failures.firstOrNull()?.let { throw AssertionError("a worker thread failed: $it", it) }
    }

    // ---- JsonFiles.writeAtomic -------------------------------------------------

    @Test
    fun writeAtomicCreatesMissingParentDirectories() {
        val target = File(tmp.root, "a/b/c/x.json")
        JsonFiles.writeAtomic(target, buildJsonObject { put("k", 1) })
        assertEquals(1, Json.parseToJsonElement(target.readText()).jsonObject["k"]!!.jsonPrimitive.int)
        assertEquals(listOf("x.json"), target.parentFile.list()!!.toList())
    }

    @Test
    fun writeAtomicRecoversWhenTheDirectoryVanishesBetweenWrites() {
        val dir = File(tmp.root, "data")
        val target = File(dir, "x.json")
        JsonFiles.writeAtomic(target, buildJsonObject { put("n", 1) })
        assertTrue(dir.deleteRecursively())

        JsonFiles.writeAtomic(target, buildJsonObject { put("n", 2) })

        assertEquals(2, JsonFiles.readOrNull(target)!!.jsonObject["n"]!!.jsonPrimitive.int)
        assertEquals(listOf("x.json"), dir.list()!!.toList())
    }

    @Test
    fun writeAtomicIntoAParentThatIsAFileFailsWithoutDebris() {
        val blocker = file("blocker").apply { writeText("I am a file") }
        assertFailsWith<IOException> { JsonFiles.writeAtomic(File(blocker, "x.json"), buildJsonObject { put("k", 1) }) }
        assertEquals("I am a file", blocker.readText())
        assertEquals(listOf("blocker"), tmp.root.list()!!.toList())
    }

    @Test
    fun writeAtomicOverANonEmptyDirectoryFailsWithoutDebris() {
        val target = file("x.json").apply { mkdirs() }
        File(target, "keep.txt").writeText("keep")
        assertFailsWith<IOException> { JsonFiles.writeAtomic(target, buildJsonObject { put("k", 1) }) }
        assertEquals("keep", File(target, "keep.txt").readText())
        assertEquals(listOf("x.json"), tmp.root.list()!!.toList(), "no .tmp left beside the directory")
    }

    @Test
    fun writeAtomicOutputIsIndentedJsonEndingInANewline() {
        val target = file("x.json")
        JsonFiles.writeAtomic(target, buildJsonObject { put("a", 1) })
        val text = target.readText()
        assertTrue(text.endsWith("}\n"), text)
        assertTrue("\n  \"a\": 1" in text, text)
    }

    @Test
    fun unicodeAndControlCharactersSurviveARoundTrip() {
        val tricky = "emoji 🧀 é line\nbreak \"quote\" back\\slash \u0000 nul   sep \u007f del 日本語"
        val value = buildJsonObject { put("s", tricky); put("k$tricky", tricky) }
        val target = file("x.json")
        JsonFiles.writeAtomic(target, value)
        assertEquals(value, JsonFiles.readOrNull(target))
    }

    @Test
    fun concurrentWritersToOneFileNeverCorruptItAndNeverFail() {
        val target = file("shared.json")
        JsonFiles.writeAtomic(target, payload(0))
        val stop = AtomicBoolean(false)
        val readerFailures = ConcurrentLinkedQueue<String>()
        val reader = thread {
            // A plain read: readOrNull would quarantine a bad file and hide the evidence.
            while (!stop.get()) {
                try {
                    val parsed = Json.parseToJsonElement(target.readText())
                    if (!consistent(parsed)) readerFailures += "mixed payload"
                } catch (error: Exception) {
                    readerFailures += "unreadable: $error"
                }
            }
        }

        try {
            runConcurrently(8) { n -> repeat(40) { round -> JsonFiles.writeAtomic(target, payload(n * 100 + round + 1)) } }
        } finally {
            stop.set(true)
            reader.join()
        }

        assertTrue(readerFailures.isEmpty(), "a reader saw a damaged file: ${readerFailures.take(3)}")
        assertTrue(consistent(Json.parseToJsonElement(target.readText())))
        assertEquals(listOf("shared.json"), tmp.root.list()!!.toList(), "no temp files left behind")
    }

    @Test
    fun concurrentByteWritersToOneFileEndWithExactlyOneWritersBytes() {
        val target = file("shared.bin")
        val sizes = (0 until 8).map { 50_000 + it * 1_000 }
        runConcurrently(8) { n ->
            repeat(30) { JsonFiles.writeBytesAtomic(target, ByteArray(sizes[n]) { n.toByte() }) }
        }
        val result = target.readBytes()
        val writer = result[0].toInt()
        assertContentEquals(ByteArray(sizes[writer]) { writer.toByte() }, result)
        assertEquals(listOf("shared.bin"), tmp.root.list()!!.toList())
    }

    private fun payload(writer: Int): JsonElement = buildJsonObject {
        put("writer", writer)
        putJsonArray("filler") { repeat(3000) { add(JsonPrimitive(writer)) } }
    }

    private fun consistent(element: JsonElement): Boolean {
        val obj = element as? JsonObject ?: return false
        val writer = obj["writer"]?.jsonPrimitive?.int ?: return false
        val filler = obj["filler"] as? JsonArray ?: return false
        return filler.size == 3000 && filler.all { it.jsonPrimitive.int == writer }
    }

    // ---- JsonFiles.readOrNull ---------------------------------------------------

    @Test
    fun aMissingFileIsNullWithoutACallback() {
        var calls = 0
        assertNull(JsonFiles.readOrNull(file("nope.json")) { _, _ -> calls++ })
        assertEquals(0, calls)
    }

    @Test
    fun aDamagedFileIsReportedOnceAndKeptAside() {
        val damaged = file("x.json").apply { writeText("""{"a": [1, 2""") }
        val reported = mutableListOf<File>()

        assertNull(JsonFiles.readOrNull(damaged) { f, _ -> reported += f })

        assertEquals(listOf(damaged), reported)
        assertFalse(damaged.exists())
        assertEquals("""{"a": [1, 2""", file("x.json.corrupt").readText())
        assertNull(JsonFiles.readOrNull(damaged) { f, _ -> reported += f }, "already moved aside")
        assertEquals(1, reported.size)
    }

    @Test
    fun aSecondCorruptionReplacesTheEarlierQuarantine() {
        file("x.json").writeText("first {")
        JsonFiles.readOrNull(file("x.json"))
        file("x.json").writeText("second {")
        JsonFiles.readOrNull(file("x.json"))
        assertEquals("second {", file("x.json.corrupt").readText())
    }

    @Test
    fun anEmptyFileCountsAsDamaged() {
        val empty = file("x.json").apply { writeText("") }
        assertNull(JsonFiles.readOrNull(empty))
        assertTrue(file("x.json.corrupt").exists())
    }

    @Test
    fun invalidUtf8BytesAreHandledNotThrown() {
        val target = file("x.json").apply { writeBytes(byteArrayOf('{'.code.toByte(), 0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte())) }
        assertNull(JsonFiles.readOrNull(target))
    }

    @Test
    fun aFileWithAByteOrderMarkStillParses() {
        // Windows editors add one when someone hand-edits prompts.json.
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val target = file("x.json").apply { writeBytes(bom + """[{"id":"a","title":"A","body":"B"}]""".toByteArray()) }

        val parsed = JsonFiles.readOrNull(target)

        assertNotNull(parsed, "a BOM is not damage")
        assertFalse(file("x.json.corrupt").exists())
    }

    @Test
    fun anyValidJsonIsReturnedAsIsWithoutQuarantine() {
        for (text in listOf("null", "42", "\"x\"", "true", "[]", "{}", "[[],{}]")) {
            val target = file("v.json").apply { writeText(text) }
            assertEquals(Json.parseToJsonElement(text), JsonFiles.readOrNull(target), text)
            assertFalse(file("v.json.corrupt").exists(), text)
        }
    }

    @Test
    fun aDirectoryWhereTheFileShouldBeIsSetAsideNotThrown() {
        val dir = file("x.json").apply { mkdirs() }
        File(dir, "inside.txt").writeText("hello")
        assertNull(JsonFiles.readOrNull(dir))
        assertFalse(dir.exists() && dir.isDirectory, "the way must be clear for the next save")
    }

    // ---- stores under concurrent update -----------------------------------------

    @Test
    fun concurrentSettingsUpdatesLoseNothingAndDiskMatchesMemory() {
        val target = file("settings.json")
        val store = SettingsStore(target)
        runConcurrently(100) {
            store.update { it.copy(maxPendingGenerations = it.maxPendingGenerations + 1) }
        }
        assertEquals(100, store.value.maxPendingGenerations)
        assertEquals(store.value, SettingsStore(target).value)
    }

    @Test
    fun concurrentPromptAddsKeepEveryPromptWithUniqueIds() {
        val target = file("prompts.json")
        val store = PromptStore(target)
        val created = ConcurrentLinkedQueue<Prompt>()
        runConcurrently(40) { n -> created += store.add("Title $n", "Body $n") }

        assertEquals(45, store.value.size)
        assertEquals(45, store.value.map { it.id }.toSet().size)
        assertEquals(40, created.map { it.id }.toSet().size, "every add must report its own prompt")
        for (prompt in created) assertEquals(prompt.title.removePrefix("Title ").let { "Body $it" }, store.value.first { it.id == prompt.id }.body)
        assertEquals(store.value, PromptStore(target).value)
    }

    @Test
    fun randomPromptTrafficKeepsTheInvariants() {
        val target = file("prompts.json")
        val store = PromptStore(target)
        runConcurrently(8) { n ->
            val random = Random(n)
            repeat(150) {
                when (random.nextInt(3)) {
                    0 -> store.add("T$n", "B$it")
                    1 -> store.value.randomOrNull(random)?.let { store.remove(it.id) }
                    else -> store.value.randomOrNull(random)?.let { p -> store.edit(p.id) { it.copy(body = "edited by $n") } }
                }
            }
        }
        val ids = store.value.map { it.id }
        assertTrue(ids.isNotEmpty(), "the shutter always needs a prompt")
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(store.value, PromptStore(target).value)
    }

    @Test
    fun twoThreadsRemovingTheLastTwoPromptsLeaveExactlyOne() {
        val store = PromptStore(file("prompts.json"))
        repeat(150) {
            store.replaceAll(listOf(Prompt("a", "A", "a"), Prompt("b", "B", "b")))
            val barrier = CyclicBarrier(2)
            val results = ConcurrentLinkedQueue<Boolean>()
            val threads = listOf("a", "b").map { id -> thread { barrier.await(); results += store.remove(id) } }
            threads.forEach { it.join() }
            assertEquals(1, store.value.size, "round $it")
            assertEquals(1, results.count { removed -> removed }, "round $it")
        }
    }

    @Test
    fun removingAnUnknownPromptChangesNothing() {
        val store = PromptStore(file("prompts.json"))
        val before = store.value
        assertFalse(store.remove("no-such-prompt"))
        assertEquals(before, store.value)
    }

    @Test
    fun updatingToAnEmptyListNeverLeavesTheStoreEmpty() {
        val store = PromptStore(file("prompts.json"))
        assertEquals(5, store.update { emptyList() }.size)
        assertEquals(5, store.replaceAll(emptyList()).size)
    }

    @Test
    fun addingBlankHugeAndEmojiPromptsIsNormalised() {
        val store = PromptStore(file("prompts.json"))

        val blank = store.add("", "")
        assertEquals(Normalize.DEFAULT_NEW_PROMPT_TITLE, blank.title)
        assertEquals(Normalize.DEFAULT_NEW_PROMPT_BODY, blank.body)

        val huge = store.add("🧀".repeat(500) + "tail", "x".repeat(1_000_000))
        assertEquals("🧀".repeat(Normalize.PROMPT_TITLE_MAX_LENGTH), huge.title)
        assertEquals(1_000_000, huge.body.length, "bodies are not truncated")
        assertEquals(huge, store.value.first { it.id == huge.id })
    }

    @Test
    fun freshIdsNeverCollideAfterRemovalsAndEdits() {
        val store = PromptStore(file("prompts.json"))
        val first = store.add("A", "a")
        val second = store.add("B", "b")
        store.remove(first.id)
        val third = store.add("C", "c")
        store.edit(second.id) { it.copy(id = third.id) } // an edit that collides with another id
        val fourth = store.add("D", "d")

        val ids = store.value.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "ids: $ids")
        assertTrue(fourth.id in ids)
    }

    @Test
    fun aThrowingTransformChangesNothingAndLeavesTheStoreUsable() {
        val target = file("settings.json")
        val store = SettingsStore(target)
        val before = store.value
        val onDisk = target.readText()

        assertFailsWith<IllegalStateException> { store.update { error("boom") } }

        assertEquals(before, store.value)
        assertEquals(onDisk, target.readText())
        assertEquals(7, store.update { it.copy(maxAttempts = 7) }.maxAttempts, "the lock must not be left held")
    }

    @Test
    fun aFailedWriteLeavesMemoryAndDiskInAgreement() {
        val target = file("settings.json")
        val store = SettingsStore(target)
        store.update { it.copy(maxAttempts = 5) }

        // Make the save impossible: a non-empty directory where the file lives.
        assertTrue(target.delete())
        target.mkdirs()
        File(target, "blocker").writeText("x")
        assertFailsWith<IOException> { store.update { it.copy(maxAttempts = 9) } }
        assertEquals(5, store.value.maxAttempts, "memory must not run ahead of the disk")

        target.deleteRecursively()
        assertEquals(9, store.update { it.copy(maxAttempts = 9) }.maxAttempts)
        assertEquals(9, SettingsStore(target).value.maxAttempts)
    }

    @Test
    fun everyStoreSurvivesAFileOfTheWrongShape() {
        val shapes = listOf("null", "42", "\"x\"", "true", "[]", "{}", "[1,2,3]", "[null]", """{"a":1}""", "[[[]]]", """[{"id":null,"title":[],"body":{}}]""")
        for (shape in shapes) {
            val prompts = PromptStore(file("p.json").apply { writeText(shape) })
            assertTrue(prompts.value.isNotEmpty(), shape)
            assertEquals(prompts.value.size, prompts.value.map { it.id }.toSet().size, shape)

            val presets = ModelPresetStore(file("m.json").apply { writeText(shape) })
            assertTrue(presets.value.isNotEmpty(), shape)

            MagicHistoryStore(file("h.json").apply { writeText(shape) })

            val settings = SettingsStore(file("s.json").apply { writeText(shape) })
            assertTrue(settings.value.maxAttempts in 0..100, shape)

            // And each still accepts a write afterwards.
            prompts.add("After", "shape $shape")
        }
    }

    @Test
    fun aDirectoryWhereAStoreFileShouldBeDoesNotStopStartup() {
        val dir = file("prompts.json").apply { mkdirs() }
        File(dir, "inside.txt").writeText("x")
        val store = PromptStore(dir)
        assertEquals(5, store.value.size)
        assertTrue(dir.isFile, "the seeded file replaces the directory that was set aside")
    }

    @Test
    fun awkwardTextRoundTripsThroughEveryStore() {
        val title = "Café 🧀 \"q\" \\"
        val body = "line1\nline2\ttab   emoji 👨‍👩‍👧‍👦 \"quoted\" back\\slash"
        val promptsFile = file("p.json")
        PromptStore(promptsFile).add(title, body)
        val reloaded = PromptStore(promptsFile).value.first()
        assertEquals(title, reloaded.title)
        assertEquals(body, reloaded.body)

        val presetFile = file("m.json")
        ModelPresetStore(presetFile).update { listOf(ModelPreset("x", "Café 🧀", "vendor/café-1", ImageApi.CHAT)) }
        assertEquals(ModelPreset("x", "Café 🧀", "vendor/café-1", ImageApi.CHAT), ModelPresetStore(presetFile).value.single())
    }

    // ---- settings / presets --------------------------------------------------

    @Test
    fun settingsAreClampedOnTheWayInAndPersistedClamped() {
        val target = file("settings.json")
        val store = SettingsStore(target)
        val saved = store.update {
            it.copy(
                outputCompression = 500, imageTimeoutSeconds = -3, inputWidth = 1, inputHeight = Int.MAX_VALUE,
                maxAttempts = -1, maxPendingGenerations = 5000, magicTimeoutSeconds = 0, cameraUsername = "  A B!  ",
            )
        }
        assertEquals(100, saved.outputCompression)
        assertEquals(10, saved.imageTimeoutSeconds)
        assertEquals(256, saved.inputWidth)
        assertEquals(4096, saved.inputHeight)
        assertEquals(0, saved.maxAttempts)
        assertEquals(1000, saved.maxPendingGenerations)
        assertEquals(5, saved.magicTimeoutSeconds)
        assertEquals("a-b", saved.cameraUsername)
        assertEquals(saved, SettingsStore(target).value)
    }

    @Test
    fun modelPresetsAreNormalisedAndNeverEmpty() {
        val store = ModelPresetStore(file("m.json"))
        val cleaned = store.update {
            listOf(
                ModelPreset("same", "One", "m1", ImageApi.EDITS),
                ModelPreset("same", "Two", "m2", ImageApi.CHAT),
                ModelPreset("blank", "Blank", "   ", ImageApi.EDITS),
            )
        }
        assertEquals(listOf("same", "same-2"), cleaned.map { it.id })
        assertEquals(1, store.update { listOf(ModelPreset("x", "x", "", ImageApi.EDITS)) }.size, "all-invalid falls back to the default")
        assertEquals(1, store.update { emptyList() }.size)
    }

    @Test
    fun concurrentPresetUpdatesLoseNothing() {
        val store = ModelPresetStore(file("m.json"))
        runConcurrently(30) { n ->
            store.update { it + ModelPreset("p$n", "Label $n", "model-$n", ImageApi.EDITS) }
        }
        assertEquals(31, store.value.size) // the default plus 30
        assertEquals(31, store.value.map { it.id }.toSet().size)
    }

    // ---- magic history ----------------------------------------------------------

    private fun entry(id: String, title: String = "T $id") = MagicHistoryEntry(id, "2026-10-11T12:00:00", title, "Body $id", null, null)

    @Test
    fun addingAnEntryWithATakenIdRenamesTheNewcomerNotTheEstablishedEntry() {
        // Ids are referenced from queued jobs and promoted prompts; renaming the
        // OLD entry would silently repoint them at the new one.
        val store = MagicHistoryStore(file("h.json"))
        store.add(entry("m1", "Old"))

        val added = store.add(entry("m1", "New"))

        assertEquals("m1-2", added.id)
        assertEquals("New", added.title)
        val byId = store.value.associateBy { it.id }
        assertEquals("Old", byId["m1"]!!.title)
        assertEquals("New", byId["m1-2"]!!.title)
        assertEquals(listOf("m1-2", "m1"), store.value.map { it.id }, "newest first")
    }

    @Test
    fun theReturnedEntryIsAlwaysTheOneThatWasAdded() {
        val store = MagicHistoryStore(file("h.json"))
        repeat(5) { assertEquals("same", store.add(entry("same")).title.removePrefix("T ")) }
        assertEquals(5, store.value.map { it.id }.toSet().size)
    }

    @Test
    fun promotingAndRemovingAreExactUnderConcurrency() {
        val store = MagicHistoryStore(file("h.json"))
        runConcurrently(40) { n -> store.add(entry("m$n")) }
        assertEquals(40, store.value.size)

        runConcurrently(40) { n ->
            when (n % 4) {
                0 -> store.markPromoted("m$n", "prompt-$n")
                1 -> store.remove("m$n")
                else -> store.add(entry("extra$n"))
            }
        }

        val byId = store.value.associateBy { it.id }
        for (n in 0 until 40) {
            when (n % 4) {
                0 -> assertEquals("prompt-$n", byId["m$n"]?.promotedPromptId, "m$n")
                1 -> assertNull(byId["m$n"], "m$n was removed")
                2, 3 -> assertNotNull(byId["m$n"], "m$n untouched")
            }
        }
        assertEquals(10 + 10 + 10 + 20, store.value.size, "10 promoted + 10 plain + 10 plain + 20 extras")
        assertEquals(store.value.size, store.value.map { it.id }.toSet().size)
    }

    @Test
    fun markPromotedAcceptsBlankAndUnknownIds() {
        val store = MagicHistoryStore(file("h.json"))
        store.add(entry("m1"))
        assertNull(store.markPromoted("m1", "   ").single().promotedPromptId)
        assertNull(store.markPromoted("m1", null).single().promotedPromptId)
        assertEquals("p", store.markPromoted("m1", "  p ").single().promotedPromptId)
        assertEquals(1, store.markPromoted("ghost", "p").size)
        assertEquals(1, store.remove("ghost").size)
    }

    @Test
    fun magicHistoryJsonNullsReadBackAsNull() {
        val target = file("h.json")
        target.writeText("""[{"id":"a","created_at":null,"title":null,"body":null,"reference_capture_path":null,"promoted_prompt_id":""}]""")
        val only = MagicHistoryStore(target).value.single()
        assertEquals("a", only.id)
        assertEquals("", only.createdAt)
        assertEquals(Normalize.DEFAULT_NEW_PROMPT_TITLE, only.title)
        assertNull(only.referenceCapturePath)
        assertNull(only.promotedPromptId)
    }
}
