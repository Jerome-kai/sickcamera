package io.github.jeromekai.latent.settings

import android.content.Context
import android.text.format.Formatter
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeromekai.latent.AppContainer
import io.github.jeromekai.latent.container
import io.github.jeromekai.latent.core.gallery.Gallery
import io.github.jeromekai.latent.core.gallery.GalleryItem
import io.github.jeromekai.latent.ui.GlassCard
import io.github.jeromekai.latent.ui.SectionTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "StorageSection"

private const val DAY_MS = 24L * 60L * 60L * 1000L

private const val DEFAULT_DAYS = 30
private val AGES = listOf(7, 30, 90)

/**
 * A file this young is never cleared. A shot writes its photo before it saves
 * the queue entry that needs it, and a magic seed photo sits on disk while the
 * planner (up to five minutes) writes the recipe that will point at it. Neither
 * is findable in the stores yet, so age is the only thing that protects them.
 */
private const val RECENT_MINUTES = 10
private const val RECENT_MS = RECENT_MINUTES * 60L * 1000L

// ---- what is on disk -------------------------------------------------------------------

/** What "Clear original photos" would do right now. Built fresh each time it is needed. */
private class OriginalsPlan(
    val remove: List<File>,
    val removeBytes: Long,
    val keptQueued: Int,
    val keptRecipe: Int,
    val keptRecent: Int,
) {
    val kept: Int get() = keptQueued + keptRecipe + keptRecent
}

/** What deleting finished photos older than some age would do right now. */
private class FinishedPlan(
    val remove: List<GalleryItem>,
    val removeBytes: Long,
    val keptQueued: Int,
)

private class StorageUsage(
    val finishedCount: Int,
    val finishedBytes: Long,
    val originalsCount: Int,
    val originalsBytes: Long,
    val referencesBytes: Long,
    val originals: OriginalsPlan,
    /** One plan per choice in [AGES], so changing the age needs no new scan. */
    val older: Map<Int, FinishedPlan>,
    val freeBytes: Long,
    val totalBytes: Long,
)

private sealed interface StorageMeasure {
    data object Loading : StorageMeasure
    data object Failed : StorageMeasure
    class Ready(val usage: StorageUsage) : StorageMeasure
}

private sealed interface StorageConfirm {
    class Originals(val plan: OriginalsPlan) : StorageConfirm
    class Older(val days: Int, val plan: FinishedPlan) : StorageConfirm
}

private class OriginalsCleared(val removed: Int, val bytes: Long, val failed: Int, val plan: OriginalsPlan)

private class FinishedDeleted(val removed: Int, val bytes: Long, val failed: Int, val keptQueued: Int)

private fun dirBytes(dir: File): Long =
    if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

/** Both sides are compared in canonical form, so a symlink cannot walk a file out of [dir]. */
private fun insideDir(file: File, dir: File): Boolean =
    file.canonicalFile.path.startsWith(dir.canonicalFile.path + File.separator)

/**
 * Every file a queued job still has to read, as canonical paths. A story job
 * reads a finished photo, so this guards the generated directory as well as
 * captures. Failed jobs count: they wait to be retried, and a retry needs
 * their photo.
 */
private fun queuedPaths(container: AppContainer): Set<String> {
    val paths = HashSet<String>()
    for (job in container.jobs.all()) {
        val stored = listOfNotNull(job.capturePath, job.sourceGeneratedPath) + job.referencePaths
        for (path in stored) container.gallery.resolve(path)?.let { paths.add(it.path) }
    }
    return paths
}

/** The photos behind the recipe the camera holds now and every recipe in the history. */
private fun recipePaths(container: AppContainer, activeRecipePath: String?): Set<String> {
    val stored = container.magicHistory.value.mapNotNull { it.referenceCapturePath } + listOfNotNull(activeRecipePath)
    return stored.mapNotNull { container.gallery.resolve(it)?.path }.toSet()
}

private fun planOriginals(container: AppContainer, activeRecipePath: String?): OriginalsPlan {
    val captures = container.gallery.capturesDir
    if (!captures.isDirectory) return OriginalsPlan(emptyList(), 0L, 0, 0, 0)

    val queued = queuedPaths(container)
    val recipes = recipePaths(container, activeRecipePath)
    val now = System.currentTimeMillis()

    val remove = ArrayList<File>()
    var removeBytes = 0L
    var keptQueued = 0
    var keptRecipe = 0
    var keptRecent = 0
    for (file in captures.walkTopDown()) {
        if (!Gallery.isImageFile(file)) continue
        val canonical = file.canonicalFile
        // Only ever delete inside captures/; a symlink must not lead out of it.
        if (!insideDir(canonical, captures)) continue
        when {
            canonical.path in queued -> keptQueued++
            canonical.path in recipes -> keptRecipe++
            now - canonical.lastModified() < RECENT_MS -> keptRecent++
            else -> {
                remove.add(canonical)
                removeBytes += canonical.length()
            }
        }
    }
    return OriginalsPlan(remove, removeBytes, keptQueued, keptRecipe, keptRecent)
}

/** A finished photo's size on disk includes the sidecar that says how it was made. */
private fun weigh(gallery: Gallery, item: GalleryItem): Long =
    item.sizeBytes + gallery.metadataFile(item.file).length()

private fun planFinished(gallery: Gallery, items: List<GalleryItem>, queued: Set<String>, days: Int): FinishedPlan {
    val cutoff = System.currentTimeMillis() - days * DAY_MS
    val remove = ArrayList<GalleryItem>()
    var removeBytes = 0L
    var keptQueued = 0
    for (item in items) {
        if (item.modifiedMillis >= cutoff) continue
        if (item.file.canonicalFile.path in queued) {
            keptQueued++
            continue
        }
        remove.add(item)
        removeBytes += weigh(gallery, item)
    }
    return FinishedPlan(remove, removeBytes, keptQueued)
}

private fun measure(container: AppContainer, activeRecipePath: String?): StorageUsage {
    val gallery = container.gallery
    val items = gallery.list()
    val queued = queuedPaths(container)
    val originals = planOriginals(container, activeRecipePath)
    return StorageUsage(
        finishedCount = items.size,
        finishedBytes = dirBytes(gallery.generatedDir),
        originalsCount = originals.remove.size + originals.kept,
        originalsBytes = dirBytes(gallery.capturesDir),
        referencesBytes = dirBytes(gallery.referencesDir),
        originals = originals,
        older = AGES.associateWith { planFinished(gallery, items, queued, it) },
        freeBytes = container.root.usableSpace,
        totalBytes = container.root.totalSpace,
    )
}

// ---- the two actions ---------------------------------------------------------------------

/** Plans again at the moment of deleting: the preview on screen may be a few seconds old. */
private fun clearOriginalPhotos(container: AppContainer, activeRecipePath: String?): OriginalsCleared {
    val plan = planOriginals(container, activeRecipePath)
    val captures = container.gallery.capturesDir
    var removed = 0
    var bytes = 0L
    var failed = 0
    for (file in plan.remove) {
        val size = file.length()
        val deleted = try {
            insideDir(file, captures) && file.delete()
        } catch (_: Exception) {
            false
        }
        if (deleted) {
            removed++
            bytes += size
        } else {
            failed++
        }
    }
    return OriginalsCleared(removed, bytes, failed, plan)
}

private fun deleteFinishedOlderThan(container: AppContainer, days: Int): FinishedDeleted {
    val gallery = container.gallery
    val plan = planFinished(gallery, gallery.list(), queuedPaths(container), days)
    var removed = 0
    var bytes = 0L
    var failed = 0
    for (item in plan.remove) {
        // Weighed first: deleting the photo takes its sidecar with it.
        val size = weigh(gallery, item)
        val deleted = try {
            gallery.delete(item.file)
        } catch (_: Exception) {
            false
        }
        if (deleted) {
            removed++
            bytes += size
        } else {
            failed++
        }
    }
    return FinishedDeleted(removed, bytes, failed, plan.keptQueued)
}

// ---- wording -----------------------------------------------------------------------------

private fun sizeText(context: Context, value: Long): String = Formatter.formatShortFileSize(context, value)

private fun originalPhotos(count: Int): String = if (count == 1) "1 original photo" else "$count original photos"

private fun finishedPhotos(count: Int): String = if (count == 1) "1 finished photo" else "$count finished photos"

/** "Kept 3: 2 waiting in the queue, 1 behind a magic recipe." Null when nothing was kept. */
private fun keptSummary(queued: Int, recipe: Int, recent: Int): String? {
    val reasons = buildList {
        if (queued > 0) add("$queued waiting in the queue")
        if (recipe > 0) add("$recipe behind magic recipes")
        if (recent > 0) add("$recent taken in the last $RECENT_MINUTES minutes")
    }
    if (reasons.isEmpty()) return null
    return "Kept ${queued + recipe + recent}: ${reasons.joinToString(", ")}."
}

private fun keptSummary(plan: OriginalsPlan): String? = keptSummary(plan.keptQueued, plan.keptRecipe, plan.keptRecent)

private fun keptSummary(plan: FinishedPlan): String? =
    if (plan.keptQueued == 0) null else "Kept ${plan.keptQueued} still needed by photos in the queue."

private fun clearMessage(context: Context, result: OriginalsCleared): String = buildString {
    if (result.removed > 0) {
        append("Removed ${originalPhotos(result.removed)} (${sizeText(context, result.bytes)}).")
    } else {
        append("Nothing was cleared.")
    }
    if (result.failed > 0) append(" ${originalPhotos(result.failed)} could not be removed.")
    keptSummary(result.plan)?.let { append(' ').append(it) }
}

private fun deleteMessage(context: Context, days: Int, result: FinishedDeleted): String = buildString {
    if (result.removed > 0) {
        append("Deleted ${finishedPhotos(result.removed)} older than $days days (${sizeText(context, result.bytes)}).")
    } else {
        append("Nothing older than $days days was deleted.")
    }
    if (result.failed > 0) append(" ${finishedPhotos(result.failed)} could not be removed.")
    if (result.keptQueued > 0) append(" Kept ${result.keptQueued} still needed by photos in the queue.")
}

private fun originalsWarning(context: Context, plan: OriginalsPlan): String = buildString {
    append("This deletes ${originalPhotos(plan.remove.size)} (${sizeText(context, plan.removeBytes)}) from this phone. ")
    append("Their finished versions stay in the album, but the Original view will have nothing to show for them. ")
    keptSummary(plan)?.let { append(it).append(' ') }
    append("This cannot be undone.")
}

private fun olderWarning(context: Context, days: Int, plan: FinishedPlan): String = buildString {
    append("This permanently deletes ${finishedPhotos(plan.remove.size)} older than $days days ")
    append("(${sizeText(context, plan.removeBytes)}), along with the notes on how each was made. ")
    append("Original photos, and copies already saved to your phone's gallery, are not touched. ")
    keptSummary(plan)?.let { append(it).append(' ') }
    append("This cannot be undone.")
}

// ---- the section -------------------------------------------------------------------------

/**
 * How much room the app's photos take, and ways to get it back. The camera
 * never deleted anything and its disk simply filled; a phone's does too.
 * Emits section titles and cards for whatever column it is placed in.
 */
@Composable
fun StorageSection() {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val version by container.galleryVersion.collectAsStateWithLifecycle()
    val queue by container.queue.collectAsStateWithLifecycle()

    var refresh by remember { mutableStateOf(0) }
    var days by remember { mutableStateOf(DEFAULT_DAYS) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<StorageConfirm?>(null) }
    var outcome by remember { mutableStateOf<String?>(null) }

    // Walking the folders is disk work, so it runs on IO, and again whenever a
    // photo lands or goes, the queue changes, or an action here finishes. The
    // old numbers stay up while the new ones are worked out.
    val measured by produceState<StorageMeasure>(StorageMeasure.Loading, version, refresh, queue) {
        // Read here, on the main thread: the session is the one place that knows
        // which recipe the camera is holding, and it may not be in the history.
        val activeRecipe = container.session.state.value.magic?.referenceCapturePath
        value = withContext(Dispatchers.IO) {
            try {
                StorageMeasure.Ready(measure(container, activeRecipe))
            } catch (error: Exception) {
                Log.w(TAG, "Could not measure storage", error)
                StorageMeasure.Failed
            }
        }
    }
    val usage = (measured as? StorageMeasure.Ready)?.usage

    fun runClear() {
        if (busy) return
        busy = true
        outcome = null
        val activeRecipe = container.session.state.value.magic?.referenceCapturePath
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { clearOriginalPhotos(container, activeRecipe) }
                outcome = clearMessage(context, result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Could not clear original photos", error)
                outcome = "The original photos could not be cleared."
            } finally {
                busy = false
                refresh++
            }
        }
    }

    fun runDelete(age: Int) {
        if (busy) return
        busy = true
        outcome = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { deleteFinishedOlderThan(container, age) }
                outcome = deleteMessage(context, age, result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Could not delete old finished photos", error)
                outcome = "The finished photos could not be deleted."
            } finally {
                busy = false
                // The album reloads on this, and so does the measuring above.
                container.onGalleryChanged()
            }
        }
    }

    // ---- what is stored ----------------------------------------------------------------
    SectionTitle("Storage")
    GlassCard {
        when (val state = measured) {
            StorageMeasure.Loading -> Note("Measuring…", top = 0.dp)
            StorageMeasure.Failed -> Note("Could not read this app's storage.", top = 0.dp)
            is StorageMeasure.Ready -> {
                val found = state.usage
                UsageRow(
                    label = "Finished photos (${found.finishedCount})",
                    size = sizeText(context, found.finishedBytes),
                    note = "The AI results in your album.",
                )
                Spacer(Modifier.height(14.dp))
                UsageRow(
                    label = "Original photos",
                    size = sizeText(context, found.originalsBytes),
                    note = "The photos as you took them. They power the Original view in the album, " +
                        "and they are what a photo waiting in the queue is made from.",
                )
                Spacer(Modifier.height(14.dp))
                UsageRow(
                    label = "Reference images",
                    size = sizeText(context, found.referencesBytes),
                    note = "The pictures your prompts send along with each photo. " +
                        "Remove one from Edit prompts to free its space.",
                )
                Spacer(Modifier.height(14.dp))
                UsageRow(
                    label = "Failed in the queue",
                    size = if (queue.failed == 0) "None" else queue.failed.toString(),
                    note = if (queue.failed == 0) {
                        "Photos the queue gave up on show up here. Manage them from the queue badge on the camera screen."
                    } else {
                        "Retry or dismiss them from the queue badge on the camera screen. " +
                            "Their original photos are kept while they are in the queue."
                    },
                )
                Note("${sizeText(context, found.freeBytes)} free of ${sizeText(context, found.totalBytes)} on this phone.", top = 14.dp)
            }
        }
    }

    // ---- getting it back ---------------------------------------------------------------
    SectionTitle("Free up space")
    GlassCard {
        val originals = usage?.originals
        Text("Original photos", style = MaterialTheme.typography.titleSmall)
        Note(
            "Clears the photos you took once nothing needs them. Photos waiting in the queue and the photos " +
                "behind your magic recipes are always kept; remove a recipe in Magic recipes to free its photo.",
        )
        Note(
            when {
                originals == null -> "Measuring…"
                originals.remove.isEmpty() -> "Nothing to clear right now. " + (keptSummary(originals) ?: "")
                else -> "${originalPhotos(originals.remove.size)} (${sizeText(context, originals.removeBytes)}) can be cleared. " +
                    (keptSummary(originals) ?: "")
            }.trim(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { if (originals != null) confirm = StorageConfirm.Originals(originals) },
            enabled = !busy && originals != null && originals.remove.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Clear original photos") }

        Spacer(Modifier.height(24.dp))
        Text("Finished photos", style = MaterialTheme.typography.titleSmall)
        Note("Deletes the AI results in your album that are older than the age you pick.")
        Spacer(Modifier.height(10.dp))
        AgeChoice(selected = days) { days = it }
        val older = usage?.older?.get(days)
        Note(
            when {
                older == null -> "Measuring…"
                older.remove.isEmpty() -> "Nothing is older than $days days. " + (keptSummary(older) ?: "")
                else -> "${finishedPhotos(older.remove.size)} (${sizeText(context, older.removeBytes)}) " +
                    "are older than $days days. " + (keptSummary(older) ?: "")
            }.trim(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { if (older != null) confirm = StorageConfirm.Older(days, older) },
            enabled = !busy && older != null && older.remove.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Delete finished photos older than $days days") }

        outcome?.let { message ->
            Spacer(Modifier.height(16.dp))
            Text(
                message,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    // ---- confirmations -----------------------------------------------------------------
    when (val pending = confirm) {
        null -> Unit
        is StorageConfirm.Originals -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Clear original photos?") },
            text = { Text(originalsWarning(context, pending.plan)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = null
                        runClear()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
        is StorageConfirm.Older -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete finished photos older than ${pending.days} days?") },
            text = { Text(olderWarning(context, pending.days, pending.plan)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = null
                        runDelete(pending.days)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

// ---- controls ------------------------------------------------------------------------------

@Composable
private fun UsageRow(label: String, size: String, note: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(12.dp))
        Text(size, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
    Note(note, top = 2.dp)
}

@Composable
private fun Note(text: String, top: Dp = 6.dp) {
    Text(
        text,
        modifier = Modifier.padding(top = top),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgeChoice(selected: Int, onSelect: (Int) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        AGES.forEachIndexed { index, age ->
            SegmentedButton(
                selected = age == selected,
                onClick = { onSelect(age) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = AGES.size),
                // The theme's accent rather than Material's default tint, and no
                // tick icon: it would only crowd the labels.
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = accent.copy(alpha = 0.16f),
                    activeContentColor = accent,
                    activeBorderColor = accent,
                ),
                icon = {},
                label = { Text("$age days", maxLines = 1) },
            )
        }
    }
}
