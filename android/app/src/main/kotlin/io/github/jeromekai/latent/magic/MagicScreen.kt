package io.github.jeromekai.latent.magic

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.jeromekai.latent.AppContainer
import io.github.jeromekai.latent.container
import io.github.jeromekai.latent.core.model.MagicHistoryEntry
import io.github.jeromekai.latent.ui.Amber
import io.github.jeromekai.latent.ui.GlassCard
import io.github.jeromekai.latent.ui.ScreenScaffold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The camera's magic history, which its web API served but its page never
 * showed: every recipe the planner has invented, newest first, each with the
 * photo that seeded it. A recipe can be put back to work on the camera or kept
 * as a regular prompt.
 */
@Composable
fun MagicScreen(onBack: () -> Unit, onUsed: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val entries by container.magicHistory.entries.collectAsStateWithLifecycle()
    val session by container.session.state.collectAsStateWithLifecycle()

    var pendingDelete by remember { mutableStateOf<MagicHistoryEntry?>(null) }
    // Entries being saved right now, so a double tap cannot add the prompt twice
    // before the store reports the first save.
    var saving by remember { mutableStateOf(setOf<String>()) }

    fun save(entry: MagicHistoryEntry) {
        if (entry.promotedPromptId != null || entry.id in saving) return
        saving = saving + entry.id
        scope.launch {
            try {
                withContext(Dispatchers.IO) { promote(container, entry) }
                Toast.makeText(context, "Saved as a prompt", Toast.LENGTH_SHORT).show()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Toast.makeText(context, "Could not save the prompt: ${error.message}", Toast.LENGTH_SHORT).show()
            } finally {
                saving = saving - entry.id
            }
        }
    }

    fun delete(entry: MagicHistoryEntry) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { removeRecipe(container, entry) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Toast.makeText(context, "Could not delete the recipe: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    ScreenScaffold(title = "Magic recipes", onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "A recipe is only as good as the photo that seeded it.",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            if (entries.isEmpty()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "No magic recipes yet.",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Hold the shutter button on the camera screen, or hold a volume key, " +
                            "and a vision model will look at the photo and invent a funny recipe from it.",
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        RecipeCard(
                            entry = entry,
                            // Held-but-idle (restored at start, or after leaving magic
                            // mode) is not "in use"; only the active recipe is.
                            inUse = session.magicActive && isCurrent(entry, session.magic),
                            saving = entry.id in saving,
                            onUse = {
                                container.session.useMagic(entry)
                                onUsed()
                            },
                            onSave = { save(entry) },
                            onDelete = { pendingDelete = entry },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        val keepsPhoto = isCurrent(entry, session.magic)
        val detail = if (keepsPhoto) {
            "The photo stays because the camera still has this recipe loaded."
        } else {
            "Its reference photo is removed too, unless a queued shot still needs it."
        }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this recipe?") },
            text = { Text("\"${entry.title}\" is removed from your magic recipes. $detail Prompts you saved from it are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    delete(entry)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RecipeCard(
    entry: MagicHistoryEntry,
    inUse: Boolean,
    saving: Boolean,
    onUse: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    val container = LocalContext.current.container
    // Resolving a path touches the disk, so it happens off the main thread. A
    // null result covers both "no photo recorded" and "photo since deleted".
    val photo by produceState<File?>(null, entry.referenceCapturePath) {
        value = withContext(Dispatchers.IO) {
            entry.referenceCapturePath
                ?.let { container.gallery.resolve(it) }
                ?.takeIf { it.isFile }
        }
    }
    val promoted = entry.promotedPromptId != null

    GlassCard {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (photo != null) {
                    AsyncImage(
                        model = photo,
                        contentDescription = "Reference photo for ${entry.title}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                } else {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    )
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                formatDate(entry.createdAt)?.let { date ->
                    Text(
                        date,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                if (inUse || promoted) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (inUse) {
                            Pill("In use", Amber.copy(alpha = 0.5f), MaterialTheme.colorScheme.onSurface)
                        }
                        if (promoted) {
                            Pill(
                                "Saved as prompt",
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(entry.body, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Delete") }
            Spacer(Modifier.weight(1f))
            // Tight padding so all three fit across a narrow phone.
            OutlinedButton(
                onClick = onSave,
                enabled = !promoted && !saving,
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) { Text(if (promoted) "Saved" else "Save as prompt") }
            Button(onClick = onUse, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text("Use") }
        }
    }
}

@Composable
private fun Pill(text: String, background: Color, content: Color) {
    Text(
        text,
        modifier = Modifier
            .background(background, RoundedCornerShape(percent = 50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = content,
    )
}

/**
 * Whether [entry] is the recipe the camera currently holds. Matched on the
 * photo as well as the id: the store may re-suffix an id when it reloads, but
 * the photo path is what the recipe cannot lose.
 */
private fun isCurrent(entry: MagicHistoryEntry, current: MagicHistoryEntry?): Boolean {
    if (current == null) return false
    val path = entry.referenceCapturePath
    return entry.id == current.id || (path != null && path == current.referenceCapturePath)
}

/** The camera's add_magic_history_entry_as_prompt: a new prompt on top, then the entry remembers it. */
private fun promote(container: AppContainer, entry: MagicHistoryEntry) {
    val prompt = container.prompts.add(entry.title, entry.body)
    container.magicHistory.markPromoted(entry.id, prompt.id)
}

/**
 * Remove the entry, then its reference photo -- unless the camera is still
 * holding the recipe (it would lose its image), a queued shot is waiting to
 * send the photo, or another entry shares the file. The history row goes first
 * so a failed file delete cannot leave a row pointing at nothing.
 */
private fun removeRecipe(container: AppContainer, entry: MagicHistoryEntry) {
    container.magicHistory.remove(entry.id)
    val path = entry.referenceCapturePath ?: return
    val heldByCamera = container.session.state.value.magic?.referenceCapturePath == path
    val sharedWithOther = container.magicHistory.value.any { it.referenceCapturePath == path }
    // The worker skips a missing reference without complaint, so a shot queued
    // with this recipe would be generated without the photo that seeded it.
    val neededByQueue = container.jobs.all().any { path in it.referencePaths }
    if (heldByCamera || sharedWithOther || neededByQueue) return

    val file = container.gallery.resolve(path) ?: return
    // Only ever delete inside captures/; the stored path is data, not trusted.
    val captures = container.gallery.capturesDir.canonicalFile
    if (file.path.startsWith(captures.path + File.separator)) file.delete()
}

/** created_at is local ISO text, sometimes without seconds; blank or odd text is shown as is, or not at all. */
private fun formatDate(raw: String): String? {
    if (raw.isBlank()) return null
    return runCatching {
        LocalDateTime.parse(raw).format(
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault()),
        )
    }.getOrDefault(raw)
}
