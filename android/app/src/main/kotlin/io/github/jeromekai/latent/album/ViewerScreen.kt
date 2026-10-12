package io.github.jeromekai.latent.album

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import io.github.jeromekai.latent.container
import io.github.jeromekai.latent.core.gallery.GalleryItem
import io.github.jeromekai.latent.core.model.GenerationMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToLong

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(startPath: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val version by container.galleryVersion.collectAsStateWithLifecycle()
    val saveToPhone = rememberSaveToPhone()

    HoldAlbumOpen()

    // Null until the first read finishes, so a not-yet-loaded album does not
    // read "Album Empty". A later failed read keeps what is shown.
    val loaded = produceState<List<GalleryItem>?>(initialValue = null, version) {
        val fresh = withContext(Dispatchers.IO) { runCatching { container.gallery.list() }.getOrNull() }
        value = fresh ?: value ?: emptyList()
    }
    val items = loaded.value.orEmpty()

    // The photo being looked at, by path, so a new result landing at the
    // front of the list does not shove the view onto it (album-stays-on-
    // viewed-photo). Saved, so a rotation keeps the place rather than going
    // back to the photo this screen was opened on.
    var currentPath by rememberSaveable { mutableStateOf(startPath) }
    val pager = rememberPagerState(pageCount = { items.size })

    // The pager starts on page 0 and is moved to the opened photo once the list
    // is read; until then it is drawn invisible so the newest photo does not
    // flash up first.
    var positioned by remember { mutableStateOf(false) }
    LaunchedEffect(items) {
        if (items.isEmpty()) return@LaunchedEffect
        val index = items.indexOfFirst { it.relativePath == currentPath }
        // A photo that has gone (deleted elsewhere) leaves the view on the page it was on.
        val target = if (index >= 0) index else pager.currentPage.coerceIn(0, items.size - 1)
        if (pager.currentPage != target) pager.scrollToPage(target)
        currentPath = items[target].relativePath
        positioned = true
    }
    // A swipe that has come to rest is the new photo. Followed from the settled
    // page rather than the moving one, and only after the first placement, so a
    // list that changes underneath cannot rename the photo being looked at.
    LaunchedEffect(positioned) {
        if (!positioned) return@LaunchedEffect
        snapshotFlow { pager.settledPage }.collect { page ->
            loaded.value?.getOrNull(page)?.let { currentPath = it.relativePath }
        }
    }

    // The photo whose original is showing, by path: moving to another photo
    // ends the comparison, as on the camera, and a new result landing does not.
    var comparing by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(currentPath) { if (comparing != currentPath) comparing = null }
    var confirmDelete by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }

    val current = if (positioned) items.getOrNull(pager.currentPage) else null

    // A second tap on Back while the first is still sliding the screen away
    // would pop through the album to the camera.
    fun settled() = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (items.isEmpty()) {
            if (loaded.value != null) {
                Text("Album Empty", color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
        } else {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize().alpha(if (positioned) 1f else 0f),
                key = { items.getOrNull(it)?.relativePath ?: it },
            ) { page ->
                items.getOrNull(page)?.let { item ->
                    ViewerPage(item, comparing = comparing == item.relativePath)
                }
            }
        }

        Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (settled()) onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            if (positioned && items.isNotEmpty()) {
                Text("${pager.currentPage + 1} / ${items.size}", color = Color.White)
            }
        }

        if (current != null) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000))
                    .navigationBarsPadding().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                val showingOriginal = comparing == current.relativePath
                ViewerAction(Icons.Filled.CompareArrows, "Original", showingOriginal) {
                    comparing = if (showingOriginal) null else current.relativePath
                }
                ViewerAction(Icons.Filled.Share, "Share") { shareFiles(context, listOf(current.file)) }
                ViewerAction(Icons.Filled.Download, "Save") { saveToPhone(listOf(current.file)) }
                ViewerAction(Icons.Filled.Smartphone, "9:16") {
                    if (container.session.recreateStory(current.file)) {
                        Toast.makeText(context, "Recreating as a 9:16 story", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, container.session.state.value.status?.text ?: "Cannot recreate now", Toast.LENGTH_SHORT).show()
                    }
                }
                ViewerAction(Icons.Filled.Info, "Info") { showInfo = true }
                ViewerAction(Icons.Filled.Delete, "Delete") { confirmDelete = true }
            }
        }
    }

    if (confirmDelete && current != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this photo?") },
            text = { Text("The AI result is removed. The photo you took is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        // Land on the neighbour rather than jumping to the newest.
                        val index = items.indexOfFirst { it.relativePath == current.relativePath }
                        val neighbour = items.getOrNull(index + 1) ?: items.getOrNull(index - 1)
                        // Already gone (deleted elsewhere) is as good as deleted.
                        val gone = withContext(Dispatchers.IO) {
                            container.gallery.delete(current.file) || !current.file.exists()
                        }
                        if (!gone) {
                            Toast.makeText(context, "Could not delete", Toast.LENGTH_SHORT).show()
                        } else if (neighbour == null) {
                            container.onGalleryChanged()
                            onBack()
                        } else {
                            currentPath = neighbour.relativePath
                            container.onGalleryChanged()
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    if (showInfo && current != null) {
        val metadata by produceState(GenerationMetadata(), current.relativePath) {
            value = withContext(Dispatchers.IO) { container.gallery.readMetadata(current.file) }
        }
        ModalBottomSheet(onDismissRequest = { showInfo = false }) {
            Column(
                Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(metadata.promptTitle ?: current.file.name, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                InfoLine("Model", listOfNotNull(metadata.model, metadata.api).joinToString(" · ").ifEmpty { "Unknown" })
                InfoLine("Made", DateFormat.getDateTimeInstance().format(Date(current.modifiedMillis)))
                InfoLine("Size", formatBytes(current.sizeBytes))
                if (metadata.variant == "story") InfoLine("Variant", "9:16 story")
                if (metadata.referencePaths.isNotEmpty()) InfoLine("Reference images", metadata.referencePaths.size.toString())
                InfoLine("File", current.file.name)
                metadata.promptBody?.let {
                    Spacer(Modifier.height(10.dp))
                    Text("Prompt", fontWeight = FontWeight.SemiBold)
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** One photo in the pager: the AI result, or the original it was made from. */
@Composable
private fun ViewerPage(item: GalleryItem, comparing: Boolean) {
    val container = LocalContext.current.container
    // Looked up as soon as the page exists, so the first tap on Original is
    // instant. The flag says whether the lookup has finished: "No Source
    // Found" must not flash up while the sidecar is still being read.
    val lookup by produceState<Pair<Boolean, File?>>(initialValue = false to null, item.relativePath) {
        value = true to withContext(Dispatchers.IO) { container.gallery.sourceFor(item.file) }
    }
    val looked = lookup.first
    val source = lookup.second

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (comparing && source == null) {
            if (looked) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No Source Found", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("Tap Original again to go back.", color = Color.White.copy(alpha = 0.8f))
                }
            }
        } else {
            SubcomposeAsyncImage(
                model = if (comparing) source else item.file,
                contentDescription = item.file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                error = {
                    // A damaged file: say so rather than crash or vanish.
                    Text(
                        "This image could not be opened.",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                },
            )
        }
        if (comparing && source != null) {
            Text(
                "Original",
                color = Color.Black,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.85f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/** The web page's own wording: megabytes from 1 MB up, otherwise whole kilobytes (at least 1). */
private fun formatBytes(bytes: Long): String =
    if (bytes >= 1_048_576L) "%.1f MB".format(bytes / 1_048_576.0)
    else "${(bytes / 1024.0).roundToLong().coerceAtLeast(1L)} KB"

@Composable
private fun ViewerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(icon, label, tint = if (active) Color(0xFFFFC23D) else Color.White)
        }
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.4f))
        Text(value, modifier = Modifier.weight(0.6f))
    }
}
