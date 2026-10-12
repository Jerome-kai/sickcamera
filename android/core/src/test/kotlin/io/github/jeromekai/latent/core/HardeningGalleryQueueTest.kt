package io.github.jeromekai.latent.core

import io.github.jeromekai.latent.core.gallery.Gallery
import io.github.jeromekai.latent.core.imaging.CropBox
import io.github.jeromekai.latent.core.imaging.Geometry
import io.github.jeromekai.latent.core.imaging.PixelSize
import io.github.jeromekai.latent.core.model.GenerationMetadata
import io.github.jeromekai.latent.core.model.ImageApi
import io.github.jeromekai.latent.core.queue.GenerationJob
import io.github.jeromekai.latent.core.queue.JobStore
import io.github.jeromekai.latent.core.queue.RetryPolicy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The file layout, the job queue's persistence, retry arithmetic and image geometry, at their limits. */
class HardeningGalleryQueueTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var gallery: Gallery

    @Before fun setUp() {
        root = File(tmp.root, "root").apply { mkdirs() }
        gallery = Gallery(root)
    }

    private fun symlink(link: File, target: File): Boolean = try {
        link.parentFile.mkdirs()
        Files.createSymbolicLink(link.toPath(), target.toPath())
        true
    } catch (_: Exception) {
        false
    }

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

    private fun insideRoot(file: File) = file.path.startsWith(root.canonicalPath + File.separator)

    // ---- Gallery.resolve ----------------------------------------------------------

    @Test
    fun resolveRefusesEveryTraversalSpelling() {
        val attempts = listOf(
            "..", "../", "../x", "a/../../x", "a/b/../../../x", "./../x", "captures/../../x",
            "captures/../../root-evil/x", "/../x", "//../x", "a/./../../x",
        )
        for (path in attempts) assertNull(gallery.resolve(path), path)
    }

    @Test
    fun resolveTreatsAbsolutePathsAsRelativeToTheRoot() {
        for (path in listOf("/etc/passwd", "//etc//passwd", "\\etc\\passwd", "/tmp")) {
            val resolved = gallery.resolve(path)
            assertTrue(resolved == null || insideRoot(resolved), "$path -> $resolved")
        }
        assertEquals(File(root, "etc/passwd").canonicalFile, gallery.resolve("/etc/passwd"))
    }

    @Test
    fun resolveRefusesBlankNamesAndTheRootItself() {
        for (path in listOf("", " ", "\t", "\n", "  \r\n ")) assertNull(gallery.resolve(path), "blank '${path.trim()}'")
        // These name the directory itself, not a file inside it.
        for (path in listOf(".", "./", "/", "//", "captures/..", "a/b/../..", "\\")) assertNull(gallery.resolve(path), path)
    }

    @Test
    fun resolveReturnsNullForAPathWithANulCharacterInsteadOfThrowing() {
        for (path in listOf("a\u0000b", "\u0000", "captures/x\u0000.jpg", "captures/\u0000/x.jpg")) {
            assertNull(gallery.resolve(path), path.replace("\u0000", "\\0"))
        }
    }

    @Test
    fun windowsSeparatorsNeverEscapeOnAnyPlatform() {
        // On Linux a backslash is an ordinary character; the result must still be inside the root.
        for (path in listOf("..\\..\\x", "captures\\..\\..\\x", "..\\", "C:\\Windows\\system32", "\\\\server\\share\\x")) {
            val resolved = gallery.resolve(path)
            assertTrue(resolved == null || insideRoot(resolved), "$path -> $resolved")
        }
    }

    @Test
    fun resolveNeverEscapesAndNeverThrowsForRandomPaths() {
        val random = Random(5)
        val alphabet = listOf(".", "..", "/", "\\", "a", "b", "%", " ", "\u0000", "é", "~", "-", "captures", "\n", "🧀")
        repeat(20_000) {
            val path = buildString { repeat(random.nextInt(0, 12)) { append(alphabet[random.nextInt(alphabet.size)]) } }
            val resolved = gallery.resolve(path)
            assertTrue(resolved == null || insideRoot(resolved), "'${path.replace("\u0000", "\\0")}' -> $resolved")
        }
    }

    @Test
    fun resolveRefusesASiblingDirectoryThatSharesTheRootsNamePrefix() {
        File(tmp.root, "root-evil").mkdirs()
        File(tmp.root, "root-evil/secret.txt").writeText("s")
        assertNull(gallery.resolve("../root-evil/secret.txt"))
    }

    @Test
    fun resolveFollowsALinkInsideTheRootButNotOutOfIt() {
        val outside = File(tmp.root, "outside").apply { mkdirs() }
        File(outside, "secret.txt").writeText("s")
        File(root, "captures").mkdirs()
        File(root, "captures/x.jpg").writeText("x")
        assumeTrue(symlink(File(root, "link-out"), outside))
        assumeTrue(symlink(File(root, "link-in"), File(root, "captures")))
        assumeTrue(symlink(File(root, "file-out.jpg"), File(outside, "secret.txt")))

        assertNull(gallery.resolve("link-out/secret.txt"))
        assertNull(gallery.resolve("file-out.jpg"))
        assertEquals(File(root, "captures/x.jpg").canonicalFile, gallery.resolve("link-in/x.jpg"))
    }

    @Test
    fun aSymlinkLoopDoesNotMakeResolveThrow() {
        assumeTrue(symlink(File(root, "loop"), File(root, "loop")))
        val resolved = gallery.resolve("loop/x.jpg")
        assertTrue(resolved == null || insideRoot(resolved))
    }

    @Test
    fun veryLongPathsAreRefusedOrResolvedNeverThrown() {
        for (path in listOf("a/".repeat(5000) + "x", "a".repeat(5000), "../".repeat(5000) + "x")) {
            val resolved = gallery.resolve(path)
            assertTrue(resolved == null || insideRoot(resolved))
        }
    }

    @Test
    fun aRootThatIsItselfALinkStillResolvesAndRelativises() {
        val link = File(tmp.root, "linked-root")
        assumeTrue(symlink(link, root))
        val viaLink = Gallery(link)
        val capture = File(viaLink.capturesDir, "x.jpg").apply { parentFile.mkdirs(); writeText("x") }

        val relative = viaLink.relative(capture)

        assertEquals("captures/x.jpg", relative)
        assertEquals(capture.canonicalFile, viaLink.resolve(relative))
    }

    @Test
    fun relativeAndResolveRoundTripAndAnOutsideFileDoesNotComeBack() {
        val inside = File(root, "captures/2026-10-11/x.jpg").apply { parentFile.mkdirs(); writeText("x") }
        assertEquals(inside.canonicalFile, gallery.resolve(gallery.relative(inside)))

        val outside = File(tmp.root, "elsewhere.jpg").apply { writeText("x") }
        assertNull(gallery.resolve(gallery.relative(outside)), "a path stored from outside the root must not resolve")
    }

    // ---- Gallery.delete -----------------------------------------------------------

    private fun generated(name: String, day: String = "2026-10-11") =
        File(gallery.generatedDir, "$day/$name").apply { parentFile.mkdirs(); writeText("img") }

    @Test
    fun deleteRefusesWhatIsNotAGeneratedImage() {
        val image = generated("a-generated.jpg")
        val sidecar = gallery.metadataFile(image).apply { writeText("{}") }
        val text = generated("notes.txt")
        val hidden = generated(".hidden.jpg")
        val directory = File(gallery.generatedDir, "2026-10-11/dir.jpg").apply { mkdirs() }
        File(directory, "inner.jpg").writeText("inner")
        val tmpFile = generated("a-generated.jpg.123.tmp")

        for (target in listOf(sidecar, text, hidden, directory, tmpFile, gallery.generatedDir, root, File(gallery.generatedDir, "2026-10-11/missing.jpg"))) {
            assertFalse(gallery.delete(target), target.path)
        }
        for (survivor in listOf(image, sidecar, text, hidden, directory, File(directory, "inner.jpg"), tmpFile)) {
            assertTrue(survivor.exists(), "${survivor.name} must survive")
        }
    }

    @Test
    fun deleteRefusesImagesOutsideTheGeneratedFolderEvenWhenSpelledWithDotDot() {
        val capture = File(gallery.capturesDir, "2026-10-11/x-source.jpg").apply { parentFile.mkdirs(); writeText("c") }
        val reference = File(gallery.referencesDir, "prompt-1.jpg").apply { parentFile.mkdirs(); writeText("r") }
        val rootLevel = File(root, "stray.jpg").apply { writeText("s") }
        val sibling = File(root, "generated-evil/x.jpg").apply { parentFile.mkdirs(); writeText("e") }
        generated("real.jpg")
        val sneaky = File(gallery.generatedDir, "../captures/2026-10-11/x-source.jpg")

        for (target in listOf(capture, reference, rootLevel, sibling, sneaky)) {
            assertFalse(gallery.delete(target), target.path)
        }
        assertTrue(capture.exists() && reference.exists() && rootLevel.exists() && sibling.exists())
    }

    @Test
    fun deleteRefusesALinkedImageThatPointsOutside() {
        val outside = File(tmp.root, "outside.jpg").apply { writeText("precious") }
        val link = File(gallery.generatedDir, "2026-10-11/link.jpg")
        assumeTrue(symlink(link, outside))

        assertFalse(gallery.delete(link))
        assertEquals("precious", outside.readText())
    }

    @Test
    fun deleteAcceptsAnyExtensionCaseRemovesTheSidecarAndIsFalseTheSecondTime() {
        val image = generated("Loud-generated.JPG")
        val sidecar = gallery.metadataFile(image).apply { writeText("{}") }

        assertTrue(gallery.delete(image))
        assertFalse(image.exists())
        assertFalse(sidecar.exists())
        assertFalse(gallery.delete(image))
    }

    @Test
    fun deleteWithANulPathIsFalseNotAnException() {
        assertFalse(gallery.delete(File(gallery.generatedDir, "2026-10-11/a\u0000b.jpg")))
    }

    // ---- Gallery.list --------------------------------------------------------------

    @Test
    fun listDoesNotReportALinkedImageThatLivesOutsideTheGeneratedFolder() {
        val outside = File(tmp.root, "outside.jpg").apply { writeText("precious") }
        assumeTrue(symlink(File(gallery.generatedDir, "2026-10-11/link.jpg"), outside))
        generated("real.jpg")

        val items = gallery.list()

        assertEquals(listOf("real.jpg"), items.map { it.file.name })
        assertTrue(items.none { it.relativePath.startsWith("..") })
    }

    @Test
    fun listBreaksTimestampTiesByNameDescendingAndIsStable() {
        val names = listOf("a-generated.jpg", "c-generated.jpg", "b-generated.jpg")
        for (name in names) generated(name).setLastModified(5_000_000)
        val first = gallery.list().map { it.file.name }
        assertEquals(listOf("c-generated.jpg", "b-generated.jpg", "a-generated.jpg"), first)
        assertEquals(first, gallery.list().map { it.file.name })
    }

    @Test
    fun listCopesWithAMissingGeneratedFolderOrAFileInItsPlace() {
        assertEquals(emptyList(), gallery.list())
        gallery.generatedDir.writeText("not a directory")
        assertEquals(emptyList(), gallery.list())
    }

    @Test
    fun listUsesForwardSlashesIncludesUppercaseExtensionsAndNestedFolders() {
        generated("A-generated.PNG", day = "2026-10-11")
        generated("b-generated.webp", day = "2026-10-12/extra")
        val paths = gallery.list().map { it.relativePath }.sorted()
        assertEquals(listOf("2026-10-11/A-generated.PNG", "2026-10-12/extra/b-generated.webp"), paths)
    }

    // ---- naming --------------------------------------------------------------------

    @Test
    fun slugsAreAlwaysShortNonEmptyFilenameSafeFragments() {
        assertEquals("prompt", Gallery.slug(""))
        assertEquals("prompt", Gallery.slug("🧀🧀"))
        assertEquals("prompt", Gallery.slug("../.."))
        assertEquals("a-b", Gallery.slug("A  B"))
        assertEquals(40, Gallery.slug("x".repeat(500)).length)
        val random = Random(3)
        val alphabet = "abcXYZ019 -_./\\%.\t\n\u00a0é日🧀\u0000".toList()
        repeat(5_000) {
            val slug = Gallery.slug(buildString { repeat(random.nextInt(0, 80)) { append(alphabet[random.nextInt(alphabet.size)]) } })
            assertTrue(Regex("[a-z0-9-]+").matches(slug) && slug.length <= 40, "'$slug'")
        }
    }

    @Test
    fun filenamesBuiltFromHostilePromptIdsStayInsideTheirDayFolder() {
        val now = LocalDateTime.of(2026, 10, 11, 12, 0, 0)
        val day = gallery.dayDir(gallery.capturesDir, now)
        for (promptId in listOf("../../etc/passwd", "/abs", "a/b", "..", "", "x\u0000y", "🧀")) {
            val capture = gallery.capturePath("id1", promptId, now)
            assertEquals(day, capture.parentFile, "capture for '$promptId'")
            val result = gallery.generatedPath("id1", promptId, ".jpg", now)
            assertEquals(gallery.dayDir(gallery.generatedDir, now), result.parentFile, "result for '$promptId'")
        }
    }

    @Test
    fun mediaIdsPadMicrosecondsTruncateNanosAndSortChronologically() {
        assertEquals("20260102-030405-000007", Gallery.mediaId(LocalDateTime.of(2026, 1, 2, 3, 4, 5, 7_000)))
        assertEquals("20260102-030405-123456", Gallery.mediaId(LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123_456_789)))
        assertEquals("20260102-000000-000000", Gallery.mediaId(LocalDateTime.of(2026, 1, 2, 0, 0, 0, 0)))
        assertEquals("20280229-235959-999999", Gallery.mediaId(LocalDateTime.of(2028, 2, 29, 23, 59, 59, 999_999_999)))

        val random = Random(8)
        val times = List(500) {
            LocalDateTime.of(2020, 1, 1, 0, 0).plusSeconds(random.nextLong(0, 400_000_000)).plusNanos(random.nextLong(0, 1_000_000_000))
        }
        val chronological = times.sortedBy { it.withNano(it.nano / 1000 * 1000) }.map { Gallery.mediaId(it) }
        assertEquals(chronological, times.map { Gallery.mediaId(it) }.sorted(), "ids must sort the way the moments do")
    }

    @Test
    fun namesAndFoldersAreAsciiWhateverTheDeviceLocale() {
        val saved = Locale.getDefault()
        try {
            val now = LocalDateTime.of(2026, 10, 11, 9, 8, 7, 654_321_000)
            for (tag in listOf("tr-TR", "ar-SA", "th-TH-u-nu-thai", "hi-IN-u-nu-deva", "fa-IR", "ja-JP-u-ca-japanese")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals("20261011-090807-654321", Gallery.mediaId(now), tag)
                assertEquals("2026-10-11", gallery.dayDir(gallery.capturesDir, now).name, tag)
                assertEquals("title-i", Gallery.slug("TITLE I"), "$tag: Turkish dotless i must not leak in")
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    // ---- metadata sidecars -----------------------------------------------------------

    @Test
    fun aDamagedSidecarReadsAsEmptyMetadataAndIsSetAside() {
        val image = generated("a-generated.jpg")
        gallery.metadataFile(image).writeText("""{"model": "m", "prompt_""")
        assertEquals(GenerationMetadata(), gallery.readMetadata(image))
        assertTrue(File(image.parentFile, "a-generated.jpg.json.corrupt").exists())
        assertEquals(GenerationMetadata(), gallery.readMetadata(image), "once set aside, a missing sidecar reads as empty too")
    }

    @Test
    fun sourceForRefusesUnsafeMissingAndNonFileCapturePaths() {
        val image = generated("a-generated.jpg")
        File(root, "captures/dir").mkdirs()
        val outside = File(tmp.root, "outside.jpg").apply { writeText("o") }
        for (stored in listOf("../outside.jpg", "../../etc/passwd", "captures/missing.jpg", "captures/dir", "captures/a\u0000b.jpg", "", "/", ".")) {
            gallery.writeMetadata(image, GenerationMetadata(capturePath = stored, model = "m"))
            assertNull(gallery.sourceFor(image), "'${stored.replace("\u0000", "\\0")}'")
        }
        assertTrue(outside.exists())
    }

    // ---- JobStore -----------------------------------------------------------------------

    private fun job(id: String = "20261011-120000-000001") = GenerationJob(
        id = id, createdAt = "2026-10-11T12:00:00", promptId = "prompt-2", promptTitle = "Turn to Cheese",
        promptBody = "Cheese.", capturePath = "captures/x-source.jpg", generatedPath = "generated/x-generated.jpg",
        referencePaths = listOf("references/prompt-2.jpg"), model = "google/gemini", api = ImageApi.CHAT,
        size = "1024x1536", quality = "low", outputFormat = "jpeg", outputCompression = 85,
    )

    private fun queueDir() = File(tmp.root, "queue")

    @Test
    fun savingTheSameIdTwiceKeepsOnlyTheLatest() {
        val store = JobStore(queueDir())
        store.save(job().copy(attempts = 1))
        store.save(job().copy(attempts = 2, lastError = "later"))
        assertEquals(1, store.all().size)
        assertEquals(2, store.load(job().id)!!.attempts)
        assertEquals(listOf("${job().id}.json"), queueDir().list()!!.toList())
    }

    @Test
    fun idsWithDotsAndHyphensWorkButDotsAloneAndPathCharactersDoNot() {
        val store = JobStore(queueDir())
        for (id in listOf("a.b.c", "x.", ".hidden", "...", "-rf", "A_b-C.9", "x.json")) {
            store.save(job(id))
            assertEquals(id, store.load(id)?.id, id)
        }
        for (id in listOf(".", "..", "", " ", "a/b", "a\\b", "a b", "é", "a\u0000", "../x", "x/..")) {
            assertFailsWith<IllegalArgumentException>("'${id.replace("\u0000", "\\0")}'") { store.save(job(id)) }
            assertNull(store.load(id), "load '$id'")
        }
    }

    @Test
    fun deleteWithAPathLikeIdTouchesNothingOutsideTheQueue() {
        val store = JobStore(queueDir())
        val bystander = File(tmp.root, "x.json").apply { writeText("keep") }
        store.delete("../x")
        store.delete("..")
        store.delete("")
        assertEquals("keep", bystander.readText())
    }

    @Test
    fun aJobFileWhoseInnerIdIsNotAFilenameIsDroppedNotTrustedLater() {
        // all() hands these ids straight back to save() and delete().
        val dir = queueDir().apply { mkdirs() }
        val forged = JobStore.encode(job()).let { it as JsonObject }.toMutableMap().apply { put("id", JsonPrimitive("../../evil")) }
        File(dir, "ok.json").writeText(JsonObject(forged).toString())

        val store = JobStore(dir)

        assertEquals(emptyList(), store.all().map { it.id })
        assertNull(store.load("ok"))
    }

    @Test
    fun aHalfWrittenJobFileIsSetAsideAndTheOthersSurvive() {
        val store = JobStore(queueDir())
        store.save(job("good"))
        File(queueDir(), "half.json").writeText("""{"id":"half","capture_path":"x""")

        assertEquals(listOf("good"), store.all().map { it.id })
        assertTrue(File(queueDir(), "half.json.corrupt").exists())
        assertEquals(listOf("good"), store.all().map { it.id }, "the second pass must not trip over it again")
        assertEquals(1, store.pendingCount())
        assertNull(store.load("half"))
    }

    @Test
    fun leftoversThatAreNotJobsAreIgnored() {
        val dir = queueDir().apply { mkdirs() }
        File(dir, "a.json.tmp").writeText("{}")
        File(dir, "a.json.7.tmp").writeText("{}")
        File(dir, "b.json.corrupt").writeText("{}")
        File(dir, "notes.txt").writeText("{}")
        File(dir, "dir.json").mkdirs()
        assertEquals(emptyList(), JobStore(dir).all())
    }

    @Test
    fun jobsMissingAnEssentialAreDroppedWhateverFormTheGapTakes() {
        val dir = queueDir().apply { mkdirs() }
        val store = JobStore(dir)
        val base = JobStore.encode(job("base")) as JsonObject
        for (essential in listOf("id", "capture_path", "generated_path", "model")) {
            val gaps: List<Pair<String, JsonElement?>> = listOf(
                "absent" to null, "null" to JsonNull, "blank" to JsonPrimitive("   "),
                "empty" to JsonPrimitive(""), "zero" to JsonPrimitive(0), "false" to JsonPrimitive(false),
            )
            for ((label, value) in gaps) {
                val broken = base.toMutableMap().apply { if (value == null) remove(essential) else put(essential, value) }
                File(dir, "case.json").writeText(JsonObject(broken).toString())
                assertEquals(emptyList(), store.all(), "$essential $label")
                File(dir, "case.json").delete()
            }
        }
    }

    @Test
    fun everyJobFieldSurvivesGarbageWithoutThrowing() {
        val junk: List<JsonElement> = listOf(
            JsonNull, JsonPrimitive(true), JsonPrimitive(0), JsonPrimitive(-7), JsonPrimitive(1.5), JsonPrimitive(Long.MAX_VALUE),
            JsonPrimitive(""), JsonPrimitive("  "), JsonPrimitive("../../x"), JsonPrimitive("a\u0000b"), JsonPrimitive("🧀".repeat(300)),
            JsonArray(emptyList()), JsonArray(listOf(JsonNull, JsonPrimitive(1))), JsonObject(emptyMap()),
        )
        val encoded = JobStore.encode(job()) as JsonObject
        val validId = Regex("[A-Za-z0-9._-]+")
        for (key in encoded.keys) {
            for (value in junk) {
                val decoded = JobStore.decode(JsonObject(encoded.toMutableMap().apply { put(key, value) }))
                if (decoded != null) {
                    assertTrue(validId.matches(decoded.id) && decoded.id != "." && decoded.id != "..", "$key=$value id '${decoded.id}'")
                    assertTrue(decoded.attempts >= 0 && decoded.outputCompression in 0..100, "$key=$value")
                    assertTrue(decoded.capturePath.isNotBlank() && decoded.generatedPath.isNotBlank() && decoded.model.isNotBlank(), "$key=$value")
                }
            }
        }
        for (shape in junk) assertNull(JobStore.decode(shape))
        assertNull(JobStore.decode(null))
    }

    @Test
    fun numbersWrittenAsTextAndNegativeCountersAreRepaired() {
        val encoded = JobStore.encode(job()) as JsonObject
        fun decode(vararg pairs: Pair<String, JsonElement>) = JobStore.decode(JsonObject(encoded.toMutableMap().apply { putAll(pairs) }))!!

        assertEquals(5, decode("attempts" to JsonPrimitive("5")).attempts)
        assertEquals(0, decode("attempts" to JsonPrimitive(-5)).attempts)
        assertEquals(50, decode("output_compression" to JsonPrimitive("50")).outputCompression)
        assertEquals(100, decode("output_compression" to JsonPrimitive(500)).outputCompression)
        assertEquals(0, decode("output_compression" to JsonPrimitive(-5)).outputCompression)
        assertTrue(decode("failed" to JsonPrimitive(true)).failed)
        assertTrue(decode("failed" to JsonPrimitive("true")).failed)
        assertFalse(decode("failed" to JsonPrimitive("no")).failed)
    }

    @Test
    fun aJobWithEveryOptionalFieldAndAwkwardTextRoundTripsThroughDisk() {
        val awkward = job("awkward").copy(
            promptTitle = "Café 🧀 \"q\"", promptBody = "line1\nline2\ttab \\ back 日本語", referencePaths = listOf("references/a b.jpg", "references/é.jpg"),
            magicHistoryId = "magic-1", variant = "story", sourceGeneratedPath = "generated/2026/a.jpg", attempts = 7,
            lastError = "HTTP 429: \"slow\"\n down", failed = true,
        )
        val store = JobStore(queueDir())
        store.save(awkward)
        assertEquals(awkward, JobStore(queueDir()).load("awkward"))
    }

    @Test
    fun allIsOrderedByCreationThenIdWithBlankDatesFirst() {
        val store = JobStore(queueDir())
        store.save(job("c").copy(createdAt = "2026-10-11T12:00:00"))
        store.save(job("b").copy(createdAt = "2026-10-11T12:00:00"))
        store.save(job("a").copy(createdAt = "2026-10-11T13:00:00"))
        store.save(job("z").copy(createdAt = ""))
        assertEquals(listOf("z", "b", "c", "a"), store.all().map { it.id })
    }

    @Test
    fun theQueueDirectoryRecreatesItselfIfItVanishes() {
        val store = JobStore(queueDir())
        store.save(job("one"))
        assertTrue(queueDir().deleteRecursively())
        assertEquals(emptyList(), store.all())
        assertEquals(0, store.pendingCount())
        store.save(job("two"))
        assertEquals(listOf("two"), store.all().map { it.id })
    }

    @Test
    fun twoStoreInstancesSavingTheSameJobConcurrentlyNeverFailOrCorruptIt() {
        // The app may hold two JobStore objects over one directory; their locks are separate.
        val first = JobStore(queueDir())
        val second = JobStore(queueDir())
        val id = "shared"
        runConcurrently(8) { n ->
            val store = if (n % 2 == 0) first else second
            repeat(40) { round -> store.save(job(id).copy(attempts = n * 100 + round, lastError = "x".repeat(20_000))) }
        }
        val loaded = assertNotNull(first.load(id))
        assertEquals(20_000, loaded.lastError!!.length)
        assertEquals(listOf("$id.json"), queueDir().list()!!.toList(), "no temp files left behind")
    }

    @Test
    fun aReaderNeverFindsAJobHalfWrittenOrLostWhileAnotherStoreRewritesIt() {
        val writer = JobStore(queueDir())
        val reader = JobStore(queueDir())
        writer.save(job("busy"))
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val misses = ConcurrentLinkedQueue<String>()
        val poller = thread {
            while (!stop.get()) {
                try {
                    if (reader.load("busy") == null) misses += "missing"
                } catch (error: Throwable) {
                    misses += "threw $error"
                }
            }
        }
        try {
            repeat(300) { writer.save(job("busy").copy(attempts = it, lastError = "y".repeat(5_000))) }
        } finally {
            stop.set(true)
            poller.join()
        }
        assertTrue(misses.isEmpty(), "the job vanished ${misses.size} times while being rewritten")
        assertFalse(File(queueDir(), "busy.json.corrupt").exists(), "a half-written file was mistaken for damage")
    }

    @Test
    fun deletingAndCountingAreIdempotent() {
        val store = JobStore(queueDir())
        store.save(job("a"))
        store.save(job("b").copy(failed = true))
        store.delete("a")
        store.delete("a")
        store.delete("never-existed")
        assertEquals(listOf("b"), store.all().map { it.id })
        assertEquals(0, store.pendingCount())
    }

    // ---- RetryPolicy ------------------------------------------------------------------------

    @Test
    fun delayIsBoundedAndNeverShrinksForAnyAttemptCount() {
        val counts = (-5..100).toList() + listOf(Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE - 1, 29, 30, 31, 32, 33, 62, 63, 64, 65, 1_000_000)
        for (attempts in counts) {
            val delay = RetryPolicy.delaySeconds(attempts)
            assertTrue(delay in RetryPolicy.BASE_SECONDS..RetryPolicy.MAX_SECONDS, "attempts $attempts -> $delay")
        }
        val ordered = (0..200).map { RetryPolicy.delaySeconds(it) }
        assertEquals(ordered.sorted(), ordered)
        assertEquals(15L, RetryPolicy.delaySeconds(1))
        assertEquals(30L, RetryPolicy.delaySeconds(2))
        assertEquals(480L, RetryPolicy.delaySeconds(6))
        assertEquals(900L, RetryPolicy.delaySeconds(7), "960 is capped at 15 minutes")
        assertEquals(15L, RetryPolicy.delaySeconds(Int.MIN_VALUE))
        assertEquals(900L, RetryPolicy.delaySeconds(Int.MAX_VALUE))
    }

    @Test
    fun theCapIsExactForEveryMaxAttemptsSetting() {
        for (max in listOf(1, 2, 3, 12, 100)) {
            var previous = 0
            var failures = 0
            while (true) {
                failures += 1
                when (val outcome = RetryPolicy.afterFailure(previous, retryable = true, maxAttempts = max)) {
                    is RetryPolicy.Outcome.Retry -> {
                        assertEquals(failures, outcome.attempts)
                        assertEquals(RetryPolicy.delaySeconds(failures), outcome.delaySeconds)
                        previous = outcome.attempts
                    }
                    is RetryPolicy.Outcome.GiveUp -> {
                        assertEquals(max, failures, "max $max must give up on the ${max}th failure")
                        assertEquals(max, outcome.attempts)
                        assertTrue("$max" in outcome.reason)
                        break
                    }
                }
                assertTrue(failures <= max, "max $max never gave up")
            }
        }
    }

    @Test
    fun zeroOrNegativeMaxAttemptsMeansRetryForever() {
        for (max in listOf(0, -1, Int.MIN_VALUE)) {
            var previous = 0
            repeat(2_000) {
                val outcome = RetryPolicy.afterFailure(previous, retryable = true, maxAttempts = max)
                previous = assertIs<RetryPolicy.Outcome.Retry>(outcome, "max $max").attempts
            }
            assertEquals(2_000, previous)
        }
    }

    @Test
    fun aPermanentErrorGivesUpWhateverTheCounters() {
        for (previous in listOf(0, 1, 11, Int.MAX_VALUE, Int.MIN_VALUE)) {
            for (max in listOf(0, 1, 12, 100)) {
                val outcome = RetryPolicy.afterFailure(previous, retryable = false, maxAttempts = max)
                val gaveUp = assertIs<RetryPolicy.Outcome.GiveUp>(outcome, "previous $previous max $max")
                assertTrue(gaveUp.attempts >= 1, "attempts ${gaveUp.attempts}")
                assertEquals("permanent error", gaveUp.reason)
            }
        }
    }

    @Test
    fun theAttemptCountSaturatesInsteadOfWrappingNegative() {
        // A hand-edited job file can carry any Int.
        val atLimit = RetryPolicy.afterFailure(Int.MAX_VALUE, retryable = true, maxAttempts = 12)
        assertIs<RetryPolicy.Outcome.GiveUp>(atLimit, "2 billion attempts is certainly past a cap of 12")

        val forever = RetryPolicy.afterFailure(Int.MAX_VALUE, retryable = true, maxAttempts = 0)
        val retry = assertIs<RetryPolicy.Outcome.Retry>(forever)
        assertEquals(Int.MAX_VALUE, retry.attempts, "the count must not wrap to ${Int.MIN_VALUE}")
        assertEquals(RetryPolicy.MAX_SECONDS, retry.delaySeconds)

        val negative = RetryPolicy.afterFailure(Int.MIN_VALUE, retryable = true, maxAttempts = 12)
        assertEquals(1, assertIs<RetryPolicy.Outcome.Retry>(negative).attempts, "a nonsense negative counter starts over")
    }

    // ---- Geometry ----------------------------------------------------------------------------

    private fun assertWellFormed(box: CropBox, width: Int, height: Int, what: String) {
        assertTrue(box.left >= 0 && box.top >= 0, "$what: $box starts outside")
        assertTrue(box.right <= width && box.bottom <= height, "$what: $box ends outside ${width}x$height")
        assertTrue(box.width >= 1 && box.height >= 1, "$what: $box is empty")
    }

    @Test
    fun aOnePixelImageStillHasANonEmptyCropBox() {
        assertEquals(CropBox(0, 0, 1, 1), Geometry.generationCrop(1, 1))
        assertEquals(CropBox(0, 0, 1, 1), Geometry.cropBox(1, 1, 4, 3))
        assertEquals(CropBox(0, 0, 1, 1), Geometry.cropBox(1, 1, 1, 2))
    }

    @Test
    fun noImageSizeAndRatioEverYieldsAnEmptyOrOutOfBoundsBox() {
        val ratios = listOf(4 to 3, 3 to 4, 16 to 9, 9 to 16, 1 to 1, 1 to 2, 2 to 1, 21 to 9)
        for (width in 1..40) {
            for (height in 1..40) {
                for ((rw, rh) in ratios) {
                    assertWellFormed(Geometry.cropBox(width, height, rw, rh), width, height, "${width}x$height at $rw:$rh")
                }
                assertWellFormed(Geometry.generationCrop(width, height), width, height, "generationCrop ${width}x$height")
            }
        }
    }

    @Test
    fun extremeAspectRatiosAndHugeImagesStayInBounds() {
        val sizes = listOf(
            1 to 100_000, 100_000 to 1, 2 to 100_000, 1 to Int.MAX_VALUE, Int.MAX_VALUE to 1,
            Int.MAX_VALUE to Int.MAX_VALUE, Int.MAX_VALUE to 3, 7680 to 4320, 4320 to 7680, 3 to 1, 1 to 3,
        )
        for ((width, height) in sizes) {
            assertWellFormed(Geometry.generationCrop(width, height), width, height, "generationCrop ${width}x$height")
            assertWellFormed(Geometry.cropBox(width, height, 4, 3), width, height, "cropBox ${width}x$height")
        }
        assertEquals(CropBox(0, 49_999, 1, 50_000), Geometry.generationCrop(1, 100_000), "a 1-pixel-wide strip keeps its centre pixel")
    }

    @Test
    fun theCropIsCentredAndCloseToTheRatioForRealisticSizes() {
        val random = Random(21)
        repeat(2_000) {
            val width = random.nextInt(100, 9000)
            val height = random.nextInt(100, 9000)
            val box = Geometry.generationCrop(width, height)
            assertWellFormed(box, width, height, "${width}x$height")
            assertTrue(kotlin.math.abs(box.left - (width - box.right)) <= 1, "${width}x$height horizontally off-centre: $box")
            assertTrue(kotlin.math.abs(box.top - (height - box.bottom)) <= 1, "${width}x$height vertically off-centre: $box")
            val target = if (height > width) 3.0 / 4 else 4.0 / 3
            assertTrue(kotlin.math.abs(box.width.toDouble() / box.height - target) < 0.02, "${width}x$height ratio ${box.width}/${box.height}")
            assertEquals(height > width, box.height > box.width, "${width}x$height keeps its orientation: $box")
        }
    }

    @Test
    fun zeroAndNegativeSizesAreRefusedClearlyNotDividedByZero() {
        for ((width, height) in listOf(0 to 0, 0 to 5, 5 to 0, -1 to 5, 5 to -1, -3 to -3, Int.MIN_VALUE to 1)) {
            assertFailsWith<IllegalArgumentException>("${width}x$height") { Geometry.cropBox(width, height, 4, 3) }
            assertFailsWith<IllegalArgumentException>("${width}x$height") { Geometry.generationCrop(width, height) }
        }
    }

    @Test
    fun aRatioThatCannotBeMetIsRefusedNotTurnedIntoAnEmptyBox() {
        for ((rw, rh) in listOf(0 to 3, 4 to 0, 0 to 0, -4 to 3, 4 to -3, -1 to -1)) {
            assertFailsWith<IllegalArgumentException>("$rw:$rh") { Geometry.cropBox(640, 480, rw, rh) }
        }
    }

    @Test
    fun sampleSizeIsAPowerOfTwoThatNeverUndershootsAndIsTheLargestSuchValue() {
        val random = Random(34)
        repeat(5_000) {
            val source = PixelSize(random.nextInt(1, 20_000), random.nextInt(1, 20_000))
            val target = PixelSize(random.nextInt(1, 5_000), random.nextInt(1, 5_000))
            val sample = Geometry.sampleSize(source, target)
            assertTrue(sample >= 1 && sample and (sample - 1) == 0, "$sample is not a power of two")
            if (source.width >= target.width && source.height >= target.height) {
                assertTrue(source.width / sample >= target.width && source.height / sample >= target.height, "$source at 1/$sample undershoots $target")
            }
            val twice = sample * 2
            assertTrue(source.width / twice < target.width || source.height / twice < target.height, "$source could still shrink for $target (sample $sample)")
        }
    }

    @Test
    fun sampleSizeSurvivesZeroNegativeAndTinyTargets() {
        // BitmapFactory reports -1 sizes when a decode fails; a zero target used to double
        // until the Int overflowed and then divide by zero.
        val sources = listOf(PixelSize(8000, 6000), PixelSize(1, 1), PixelSize(0, 0), PixelSize(-1, -1), PixelSize(Int.MAX_VALUE, Int.MAX_VALUE))
        val targets = listOf(PixelSize(0, 0), PixelSize(0, 5), PixelSize(5, 0), PixelSize(-1, -1), PixelSize(1, 1), PixelSize(Int.MIN_VALUE, Int.MIN_VALUE))
        for (source in sources) {
            for (target in targets) {
                val sample = Geometry.sampleSize(source, target)
                assertTrue(sample >= 1 && sample and (sample - 1) == 0 && sample <= 1 shl 30, "$source for $target gave $sample")
            }
        }
    }

    @Test
    fun aSourceSmallerThanTheTargetIsNeverShrunk() {
        assertEquals(1, Geometry.sampleSize(PixelSize(640, 480), PixelSize(1280, 960)))
        assertEquals(1, Geometry.sampleSize(PixelSize(2000, 700), PixelSize(1280, 960)), "one short side stops the shrinking")
    }

    @Test
    fun pixelSizesParseTheWayPeopleTypeThemAndRefuseTheRest() {
        assertEquals(PixelSize(1536, 1024), PixelSize.parse("1536x1024"))
        assertEquals(PixelSize(1536, 1024), PixelSize.parse("1536X1024"))
        assertEquals(PixelSize(1536, 1024), PixelSize.parse(" 1536 x 1024 "))
        for (junk in listOf("0x5", "5x0", "-5x5", "5x-5", "1x2x3", "x", "x5", "5x", "", "auto", "99999999999x5", "1536\u00d71024", "1.5x2", "1536*1024")) {
            assertNull(PixelSize.parse(junk), "'$junk'")
        }
        assertEquals("1536x1024", PixelSize(1536, 1024).toString())
        assertEquals(PixelSize(7, 9), PixelSize.parse(PixelSize(7, 9).toString()))
    }

    @Test
    fun orientationHelpersAreIdempotentAndSquareSafe() {
        val landscape = PixelSize(1536, 1024)
        val portrait = PixelSize(1024, 1536)
        val square = PixelSize(1024, 1024)
        assertEquals(landscape, landscape.turned().turned())
        assertEquals(portrait, landscape.orientedLike(portrait))
        assertEquals(portrait, portrait.orientedLike(portrait))
        assertEquals(landscape, portrait.orientedLike(landscape))
        assertEquals(landscape, portrait.orientedLike(square), "a square photo counts as landscape")
        assertEquals(square, square.orientedLike(portrait), "a square size has no orientation to turn")
        for (size in listOf(landscape, portrait, square)) {
            for (reference in listOf(landscape, portrait, square)) {
                val once = size.orientedLike(reference)
                assertEquals(once, once.orientedLike(reference), "$size like $reference")
            }
        }
    }

    @Test
    fun outputAndInputSizesFollowThePhotoAndLeaveUnparseableConfigAlone() {
        for (junk in listOf("auto", "", "0x0", "-1x5", "1536x1024x2", "1536")) {
            assertEquals(junk, Geometry.outputSize(junk, PixelSize(3000, 4000)), "'$junk'")
        }
        assertEquals("1024x1536", Geometry.outputSize("1024x1536", PixelSize(3000, 4000)))
        assertEquals("1536x1024", Geometry.outputSize("1024x1536", PixelSize(4000, 3000)), "a portrait setting is turned for a landscape photo")
        assertEquals("1024x1024", Geometry.outputSize("1024x1024", PixelSize(3000, 4000)))

        assertEquals(PixelSize(1280, 960), Geometry.generationInputSize(PixelSize(4000, 3000), 960, 1280), "swapped edges are put in order")
        assertEquals(PixelSize(960, 1280), Geometry.generationInputSize(PixelSize(3000, 4000), 960, 1280))
        assertEquals(PixelSize(1280, 960), Geometry.generationInputSize(PixelSize(3000, 3000), 1280, 960), "square photo -> landscape")
        assertEquals(PixelSize(1024, 1024), Geometry.generationInputSize(PixelSize(3000, 4000), 1024, 1024))
    }
}
