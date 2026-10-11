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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import io.github.jeromekai.latent.container
import io.github.jeromekai.latent.core.gallery.GalleryItem
import io.github.jeromekai.latent.core.model.GenerationMetadata
import io.github.jeromekai.latent.data.PhoneGallery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(startPath: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val version by container.galleryVersion.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        container.albumOpen = true
        onDispose { container.albumOpen = false }
    }

    val items by produceState(initialValue = emptyList<GalleryItem>(), version) {
        value = withContext(Dispatchers.IO) { container.gallery.list() }
    }
    // The photo being looked at, by path, so a new result landing at the
    // front of the list does not shove the view onto it (album-stays-on-
    // viewed-photo).
    var currentPath by remember { mutableStateOf(startPath) }
    val pager = rememberPagerState(pageCount = { items.size })

    LaunchedEffect(items) {
        if (items.isEmpty()) return@LaunchedEffect
        val index = items.indexOfFirst { it.relativePath == currentPath }
        val target = if (index >= 0) index else pager.currentPage.coerceIn(0, items.size - 1)
        if (pager.currentPage != target) pager.scrollToPage(target)
    }
    LaunchedEffect(pager.currentPage, items) {
        items.getOrNull(pager.currentPage)?.let { currentPath = it.relativePath }
    }

    var showOriginal by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) { showOriginal = false }
    var confirmDelete by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }

    val current = items.getOrNull(pager.currentPage)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (items.isEmpty()) {
            Text("Album Empty", color = Color.White, modifier = Modifier.align(Alignment.Center))
        } else {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { items[it].relativePath }) { page ->
                val item = items[page]
                val source by produceState<File?>(initialValue = null, item.relativePath) {
                    value = withContext(Dispatchers.IO) { container.gallery.sourceFor(item.file) }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val showingSource = showOriginal && page == pager.currentPage
                    if (showingSource && source == null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No Source Found", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text("Tap Original again to go back.", color = Color.White.copy(alpha = 0.8f))
                        }
                    } else {
                        SubcomposeAsyncImage(
                            model = if (showingSource) source else item.file,
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
                    if (showingSource && source != null) {
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
        }

        Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            if (items.isNotEmpty()) {
                Text("${pager.currentPage + 1} / ${items.size}", color = Color.White)
            }
        }

        if (current != null) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000))
                    .navigationBarsPadding().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ViewerAction(Icons.Filled.CompareArrows, "Original", showOriginal) { showOriginal = !showOriginal }
                ViewerAction(Icons.Filled.Share, "Share") {
                    PhoneGallery.shareIntent(context, listOf(current.file))?.let(context::startActivity)
                }
                ViewerAction(Icons.Filled.Download, "Save") {
                    scope.launch {
                        val uri = withContext(Dispatchers.IO) { PhoneGallery.saveToPictures(context, current.file) }
                        Toast.makeText(
                            context,
                            if (uri != null) "Saved to Pictures/${PhoneGallery.ALBUM}" else "Could not save (storage permission?)",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
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
            title = { Text("Delete this image?") },
            text = { Text("The AI result is removed. The photo you took is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        // Land on the neighbour rather than jumping to the newest.
                        val index = pager.currentPage
                        val neighbour = items.getOrNull(index + 1) ?: items.getOrNull(index - 1)
                        val deleted = withContext(Dispatchers.IO) { container.gallery.delete(current.file) }
                        if (!deleted) {
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
                InfoLine("Size", "%.1f KB".format(current.sizeBytes / 1024.0))
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
