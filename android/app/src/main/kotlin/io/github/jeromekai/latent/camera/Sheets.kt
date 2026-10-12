package io.github.jeromekai.latent.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeromekai.latent.AppContainer
import io.github.jeromekai.latent.core.model.Prompt
import io.github.jeromekai.latent.core.queue.GenerationJob
import io.github.jeromekai.latent.core.queue.RetryPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The prompt picker: the camera's scrollable list of saved prompts. Tapping
 * one selects it and returns to the viewfinder; the selection is remembered
 * across restarts, which the camera's was not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptPickerSheet(
    prompts: List<Prompt>,
    selectedId: String,
    onSelect: (Prompt) -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
    hasReference: (Prompt) -> Boolean,
) {
    // The camera opened its picker on the current prompt; with a long list that
    // one could otherwise start below the fold.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (prompts.indexOfFirst { it.id == selectedId } - 1).coerceAtLeast(0),
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Prompt", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onEdit) { Text("Edit prompts") }
            }
            LazyColumn(Modifier.fillMaxWidth().padding(bottom = 16.dp), state = listState) {
                items(prompts, key = { it.id }) { prompt ->
                    val selected = prompt.id == selectedId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 3.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable { onSelect(prompt) }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(prompt.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                prompt.body,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                        if (hasReference(prompt)) {
                            Icon(Icons.Filled.Image, "Has a reference image", modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        if (selected) Icon(Icons.Filled.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/**
 * Photos still on their way, and ones the queue gave up on. The camera kept
 * given-up jobs in data/queue/failed/ where only a terminal could reach them;
 * here they can be retried (say, after fixing the API key) or dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(container: AppContainer, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val queue by container.queue.collectAsStateWithLifecycle()
    var jobs by remember { mutableStateOf<List<GenerationJob>>(emptyList()) }
    var version by remember { mutableStateOf(0) }
    // Reload when our own edit lands and when the badge's counts move, so a
    // photo that finishes while the sheet is open drops off the list.
    LaunchedEffect(version, queue) { jobs = withContext(Dispatchers.IO) { container.jobs.all() } }

    // retry/dismiss read and write job files, so they run off the main thread.
    fun act(change: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { change() }
            version++
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text("Queue", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            if (jobs.isEmpty()) {
                Text("Nothing waiting.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(jobs, key = { it.id }) { job ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(12.dp),
                    ) {
                        Text("${job.promptTitle} · ${job.model.substringAfterLast('/')}", fontWeight = FontWeight.SemiBold)
                        val state = when {
                            job.failed -> "Gave up after ${job.attempts} attempt(s)"
                            job.attempts > 0 -> "Retrying (attempt ${job.attempts + 1}, waits up to ${RetryPolicy.delaySeconds(job.attempts)}s)"
                            else -> "Waiting for its turn"
                        }
                        Text(state, style = MaterialTheme.typography.bodySmall)
                        job.lastError?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB3261E), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        Row {
                            if (job.failed) {
                                TextButton(onClick = { act { container.scheduler.retry(job.id) } }) { Text("Retry") }
                            }
                            TextButton(onClick = { act { container.scheduler.dismiss(job.id) } }) {
                                Text(if (job.failed) "Dismiss" else "Cancel")
                            }
                        }
                    }
                }
            }
        }
    }
}
