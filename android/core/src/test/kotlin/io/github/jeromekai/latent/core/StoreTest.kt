package io.github.jeromekai.latent.core

import io.github.jeromekai.latent.core.model.AppTheme
import io.github.jeromekai.latent.core.model.MagicHistoryEntry
import io.github.jeromekai.latent.core.model.Normalize
import io.github.jeromekai.latent.core.model.OutputFormat
import io.github.jeromekai.latent.core.store.JsonFiles
import io.github.jeromekai.latent.core.store.MagicHistoryStore
import io.github.jeromekai.latent.core.store.PromptStore
import io.github.jeromekai.latent.core.store.SettingsStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun aFreshPromptStoreSeedsTheFiveDefaults() {
        val file = File(tmp.root, "prompts.json")
        val store = PromptStore(file)
        assertEquals(5, store.value.size)
        assertTrue(file.exists(), "seeded to disk on first run, as the camera did")
    }

    @Test
    fun aTruncatedFileFallsBackAndIsKeptAside() {
        val file = File(tmp.root, "prompts.json").apply { writeText("""[{"id": "a", "ti""") }
        val store = PromptStore(file)
        assertEquals(5, store.value.size)
        assertEquals("""[{"id": "a", "ti""", File(tmp.root, "prompts.json.corrupt").readText())
    }

    @Test
    fun writesLeaveNoTempFileBehind() {
        val store = PromptStore(File(tmp.root, "prompts.json"))
        store.add("Hat", "Add a hat.")
        assertEquals(listOf("prompts.json"), tmp.root.list()!!.sorted())
    }

    @Test
    fun newPromptsGoOnTopAndTheLastOneCannotBeRemoved() {
        val store = PromptStore(File(tmp.root, "prompts.json"))
        val added = store.add("Hat", "Add a hat.")
        assertEquals(added.id, store.value.first().id)

        store.value.drop(1).forEach { assertTrue(store.remove(it.id)) }
        assertEquals(1, store.value.size)
        assertFalse(store.remove(store.value.single().id), "the shutter always needs a prompt")
    }

    @Test
    fun aDeletedPromptsIdIsNeverIssuedAgain() {
        // The id names the reference image and is held by queued jobs and the
        // selected-prompt setting; reusing one hands all of that to a stranger.
        val store = PromptStore(File(tmp.root, "prompts.json"))
        val first = store.add("A", "a")
        store.remove(first.id)
        val ids = (1..50).map { store.add("N$it", "n").id }
        assertFalse(first.id in ids)
        assertEquals(ids.size, ids.toSet().size, "ids issued in the same millisecond must still differ")
    }

    @Test
    fun everyWriteIsNormalised() {
        val store = PromptStore(File(tmp.root, "prompts.json"))
        store.edit(store.value.first().id) { it.copy(title = "   ", body = "") }
        val edited = store.value.first()
        assertEquals(Normalize.DEFAULT_NEW_PROMPT_TITLE, edited.title)
        assertEquals(Normalize.DEFAULT_NEW_PROMPT_BODY, edited.body)
    }

    @Test
    fun referenceImagesPersistAndSurviveAReload() {
        val file = File(tmp.root, "prompts.json")
        val store = PromptStore(file)
        val id = store.value.first().id
        store.edit(id) { it.copy(referenceImage = "$id.jpg") }
        assertEquals("$id.jpg", PromptStore(file).value.first { it.id == id }.referenceImage)
    }

    @Test
    fun referenceImageNamesCannotEscapeTheirDirectory() {
        assertEquals("passwd", Normalize.referenceImage(JsonPrimitive("../../etc/passwd")))
        assertEquals("evil.jpg", Normalize.referenceImage(JsonPrimitive("C:\\\\dir\\\\evil.jpg")))
        assertEquals("", Normalize.referenceImage(JsonPrimitive("..")))
        // The Python original sanitised "..%" into ".."; re-checked here.
        assertEquals("", Normalize.referenceImage(JsonPrimitive("..%")))
    }

    @Test
    fun concurrentMagicHistoryUpdatesLoseNothing() {
        // The camera's add_entry read and saved under separate locks, so a
        // promote racing an add could drop the new entry.
        val store = MagicHistoryStore(File(tmp.root, "magic_history.json"))
        val threads = (1..20).map { n ->
            thread {
                store.add(MagicHistoryEntry("m$n", "t", "Title $n", "Body", null, null))
            }
        }
        threads.forEach { it.join() }
        assertEquals(20, store.value.size)
        assertEquals(20, store.value.map { it.id }.toSet().size)
    }

    @Test
    fun settingsClampAndToleratePythonStyleValues() {
        val file = File(tmp.root, "settings.json")
        JsonFiles.writeAtomic(file, buildJsonObject {
            put("app_background_theme", "sunset")
            put("output_compression", 400)
            put("output_format", "WEBP")
            put("magic_mode_enabled", "0")
            put("size", " 1024 x 1536 ")
            put("queue_max_attempts", "-3")
            put("unknown_key", "ignored")
        })
        val s = SettingsStore(file).value
        assertEquals(AppTheme.SUNSET, s.theme)
        assertEquals(100, s.outputCompression)
        assertEquals(OutputFormat.WEBP, s.outputFormat)
        assertFalse(s.magicEnabled)
        assertEquals("1024x1536", s.size)
        assertEquals(0, s.maxAttempts)
    }

    @Test
    fun settingsRoundTripExactly() {
        val file = File(tmp.root, "settings.json")
        val store = SettingsStore(file)
        val changed = store.update { it.copy(baseUrl = "https://ai-gateway.vercel.sh/v1/", cameraUsername = "Jerome K") }
        assertEquals("https://ai-gateway.vercel.sh/v1", changed.baseUrl)
        assertEquals("jerome-k", changed.cameraUsername)
        assertEquals(changed, SettingsStore(file).value)
    }

    @Test
    fun legacyCameraPromptsJsonLoads() {
        // The camera's own file format, including the map form it also read.
        val file = File(tmp.root, "prompts.json")
        file.writeText(Json.encodeToString(mapOf("legacy" to "Just a body")))
        val prompts = PromptStore(file).value
        assertEquals("legacy", prompts.single().id)
        assertEquals("Just a body", prompts.single().body)
    }
}
