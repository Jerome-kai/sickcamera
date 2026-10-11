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
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueueGalleryGeometryTest {
    @get:Rule val tmp = TemporaryFolder()

    // ---- retry policy ----------------------------------------------------------

    @Test
    fun retryDelaysMatchTheCamera() {
        // QUEUE_RETRY_BASE_SECONDS=15 doubling, capped at 15 minutes.
        assertEquals(listOf(15L, 30, 60, 120, 240, 480, 900, 900), (1..8).map { RetryPolicy.delaySeconds(it) })
        assertEquals(15L, RetryPolicy.delaySeconds(0))
        assertEquals(900L, RetryPolicy.delaySeconds(10_000))
    }

    @Test
    fun aPermanentErrorGivesUpImmediately() {
        assertIs<RetryPolicy.Outcome.GiveUp>(RetryPolicy.afterFailure(0, retryable = false, maxAttempts = 12))
    }

    @Test
    fun retriesStopAtTheCap() {
        assertIs<RetryPolicy.Outcome.Retry>(RetryPolicy.afterFailure(10, retryable = true, maxAttempts = 12))
        assertIs<RetryPolicy.Outcome.GiveUp>(RetryPolicy.afterFailure(11, retryable = true, maxAttempts = 12))
    }

    @Test
    fun zeroMeansRetryForever() {
        assertIs<RetryPolicy.Outcome.Retry>(RetryPolicy.afterFailure(500, retryable = true, maxAttempts = 0))
    }

    // ---- job store -------------------------------------------------------------

    private fun job(id: String = "20261011-120000-000001") = GenerationJob(
        id = id, createdAt = "2026-10-11T12:00:00", promptId = "prompt-2", promptTitle = "Turn to Cheese",
        promptBody = "Cheese.", capturePath = "captures/x-source.jpg", generatedPath = "generated/x-generated.jpg",
        referencePaths = listOf("references/prompt-2.jpg"), model = "google/gemini", api = ImageApi.CHAT,
        size = "1024x1536", quality = "low", outputFormat = "jpeg", outputCompression = 85,
    )

    @Test
    fun jobsRoundTripThroughDisk() {
        val store = JobStore(File(tmp.root, "queue"))
        store.save(job())
        assertEquals(job(), store.load("20261011-120000-000001"))
        assertEquals(1, store.pendingCount())
    }

    @Test
    fun failedJobsDoNotCountAsPending() {
        val store = JobStore(File(tmp.root, "queue"))
        store.save(job().copy(failed = true, lastError = "bad key"))
        assertEquals(0, store.pendingCount())
        assertEquals(1, store.all().size)
    }

    @Test
    fun aJobIdCannotNameAFileOutsideTheQueue() {
        val store = JobStore(File(tmp.root, "queue"))
        assertFailsWith<IllegalArgumentException> { store.save(job(id = "../escape")) }
        assertNull(store.load("../escape"))
    }

    @Test
    fun aDamagedJobFileIsSkipped() {
        val dir = File(tmp.root, "queue").apply { mkdirs() }
        File(dir, "broken.json").writeText("{ nope")
        File(dir, "empty.json").writeText("{}")
        assertTrue(JobStore(dir).all().isEmpty())
    }

    // ---- geometry --------------------------------------------------------------

    @Test
    fun cropBoxMatchesPillowsForTheCamerasRatio() {
        // _build_crop_box against 320:240, the values PIL would crop to.
        assertEquals(CropBox(240, 0, 1680, 1080), Geometry.cropBox(1920, 1080, 4, 3))
        assertEquals(CropBox(0, 0, 640, 480), Geometry.cropBox(640, 480, 4, 3))
        assertEquals(CropBox(0, 300, 1200, 1200), Geometry.cropBox(1200, 1500, 4, 3))
    }

    @Test
    fun aPortraitPhotoKeepsItsOrientation() {
        val box = Geometry.generationCrop(3000, 4000)
        assertEquals(3, box.width * 4 / box.height) // 3:4, not cropped to landscape
        assertEquals(PixelSize(960, 1280), Geometry.generationInputSize(PixelSize(3000, 4000), 1280, 960))
        assertEquals(PixelSize(1280, 960), Geometry.generationInputSize(PixelSize(4000, 3000), 1280, 960))
        assertEquals("1024x1536", Geometry.outputSize("1536x1024", PixelSize(3000, 4000)))
        assertEquals("1536x1024", Geometry.outputSize("1536x1024", PixelSize(4000, 3000)))
    }

    @Test
    fun sampleSizeNeverUndershootsTheTarget() {
        assertEquals(4, Geometry.sampleSize(PixelSize(8000, 6000), PixelSize(1280, 960)))
        assertEquals(1, Geometry.sampleSize(PixelSize(1280, 960), PixelSize(1280, 960)))
    }

    // ---- gallery ---------------------------------------------------------------

    @Test
    fun aPhotoAndItsResultShareAnId() {
        val gallery = Gallery(tmp.root)
        val now = LocalDateTime.of(2026, 10, 11, 12, 30, 45, 123_456_000)
        val id = Gallery.mediaId(now)
        assertEquals("20261011-123045-123456", id)
        assertTrue(gallery.capturePath(id, "Prompt 2!", now).path.endsWith("captures/2026-10-11/20261011-123045-123456-prompt-2-source.jpg"))
        assertTrue(gallery.generatedPath(id, "prompt-2", ".jpg", now).path.endsWith("generated/2026-10-11/20261011-123045-123456-prompt-2-generated.jpg"))
    }

    @Test
    fun theCompareViewFindsTheOriginal() {
        val gallery = Gallery(tmp.root)
        val now = LocalDateTime.now()
        val capture = gallery.capturePath("id1", "p", now).apply { parentFile.mkdirs(); writeText("photo") }
        val result = gallery.generatedPath("id1", "p", ".jpg", now).apply { parentFile.mkdirs(); writeText("result") }
        gallery.writeMetadata(result, GenerationMetadata(capturePath = gallery.relative(capture), model = "m"))

        assertEquals(capture.canonicalFile, gallery.sourceFor(result))
        assertEquals("m", gallery.readMetadata(result).model)
    }

    @Test
    fun listingIsNewestFirstAndSkipsSidecarsAndTempFiles() {
        val gallery = Gallery(tmp.root)
        val day = File(gallery.generatedDir, "2026-10-11").apply { mkdirs() }
        File(day, "a-generated.jpg").apply { writeText("a"); setLastModified(1_000_000) }
        File(day, "b-generated.png").apply { writeText("b"); setLastModified(2_000_000) }
        File(day, "b-generated.png.json").writeText("{}")
        File(day, "c-generated.jpg.part").writeText("partial")
        File(day, ".hidden.jpg").writeText("h")

        assertEquals(listOf("b-generated.png", "a-generated.jpg"), gallery.list().map { it.file.name })
    }

    @Test
    fun deletionIsConfinedToGeneratedImages() {
        val gallery = Gallery(tmp.root)
        val outside = File(tmp.root, "prompts.json").apply { writeText("[]") }
        val capture = File(gallery.capturesDir, "x-source.jpg").apply { parentFile.mkdirs(); writeText("c") }
        val result = File(gallery.generatedDir, "d/x-generated.jpg").apply { parentFile.mkdirs(); writeText("g") }
        gallery.writeMetadata(result, GenerationMetadata(model = "m"))

        assertFalse(gallery.delete(outside))
        assertFalse(gallery.delete(capture))
        assertTrue(gallery.delete(result))
        assertFalse(gallery.metadataFile(result).exists())
        assertTrue(outside.exists() && capture.exists())
    }

    @Test
    fun storedPathsCannotResolveOutsideTheRoot() {
        val gallery = Gallery(tmp.root)
        assertNull(gallery.resolve("../../etc/passwd"))
        assertNull(gallery.resolve(""))
        assertEquals(File(tmp.root, "captures/x.jpg").canonicalFile, gallery.resolve("captures/x.jpg"))
    }
}
