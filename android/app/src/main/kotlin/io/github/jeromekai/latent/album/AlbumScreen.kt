package io.github.jeromekai.latent.album

import android.Manifest
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.jeromekai.latent.container
import io.github.jeromekai.latent.core.gallery.GalleryItem
import io.github.jeromekai.latent.data.PhoneGallery
import io.github.jeromekai.latent.ui.ThemedBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The album and the viewer both count as "the album is open": that clears the
 * camera's sparkle and stops new results adding to it. Moving between them
 * composes both at once, and the screen leaving is disposed AFTER the one
 * arriving has started, so a plain flag would be switched off with the album
 * on screen. Count the screens holding it instead.
 */
private var albumHolds = 0

@Composable
internal fun HoldAlbumOpen() {
    val container = LocalContext.current.container
    DisposableEffect(container) {
        albumHolds++
        container.albumOpen = true
        onDispose {
            albumHolds = (albumHolds - 1).coerceAtLeast(0)
            container.albumOpen = albumHolds > 0
        }
    }
}

/**
 * Copies images into Pictures/Latent. Android 9 and older must ask for storage
 * permission first; the images wait through the dialog so that the tap that
 * asked is not lost.
 */
@Composable
internal fun rememberSaveToPhone(): (List<File>) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun save(files: List<File>) {
        scope.launch {
            val saved = withContext(Dispatchers.IO) { files.count { PhoneGallery.saveToPictures(context, it) != null } }
            val where = "Pictures/${PhoneGallery.ALBUM}"
            val text = when {
                saved == 0 -> "Could not save"
                saved < files.size -> "Saved $saved of ${files.size} to $where"
                files.size == 1 -> "Saved to $where"
                else -> "Saved $saved to $where"
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    var pending by remember { mutableStateOf<List<File>?>(null) }
    val askStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val files = pending
        pending = null
        if (files != null) {
            if (granted) save(files)
            else Toast.makeText(context, "Allow storage access to save to your phone", Toast.LENGTH_SHORT).show()
        }
    }

    return { files ->
        if (PhoneGallery.needsLegacyPermission(context)) {
            pending = files
            askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            save(files)
        }
    }
}

/** The share sheet, without dying when it cannot open (no app to take them, or too many images for one hand-off). */
internal fun shareFiles(context: Context, files: List<File>) {
    try {
        PhoneGallery.shareIntent(context, files)?.let { context.startActivity(it) }
    } catch (error: RuntimeException) {
        Toast.makeText(context, "Could not share", Toast.LENGTH_SHORT).show()
    }
}

private val SelectionSaver = listSaver<Set<String>, String>(
    save = { it.toList() },
    restore = { it.toSet() },
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumScreen(onBack: () -> Unit, onOpen: (GalleryItem) -> Unit, onOpenMagic: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val version by container.galleryVersion.collectAsStateWithLifecycle()
    val saveToPhone = rememberSaveToPhone()

    // Opening the album clears the "new results" sparkle, as on the camera.
    HoldAlbumOpen()

    // Null until the first read finishes: an empty list would flash "Album
    // Empty" at an album that is not. A later failed read keeps what is shown.
    val loaded = produceState<List<GalleryItem>?>(initialValue = null, version) {
        val fresh = withContext(Dispatchers.IO) { runCatching { container.gallery.list() }.getOrNull() }
        value = fresh ?: value ?: emptyList()
    }
    val items = loaded.value.orEmpty()

    // Saved across a rotation: the activity is rebuilt, and a long selection
    // should not be thrown away with it.
    var selection by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf(emptySet<String>()) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    // Results that vanish (deleted from Settings, say) leave the selection, as
    // on the camera's web page; otherwise the count would include ghosts.
    LaunchedEffect(loaded.value) {
        if (loaded.value == null) return@LaunchedEffect
        val live = items.mapTo(HashSet<String>()) { it.relativePath }
        val kept = selection intersect live
        if (kept.size != selection.size) {
            selection = kept
            if (kept.isEmpty()) confirmDelete = false
        }
    }

    // Back leaves selection mode first, rather than the whole album.
    BackHandler(enabled = selecting) {
        selecting = false
        selection = emptySet()
    }

    // A second tap while the first is still sliding the next screen in would
    // stack it twice (or, going back, pop through to the camera). Only a
    // screen that has finished arriving acts on navigation taps.
    fun settled() = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    fun selectedItems() = items.filter { it.relativePath in selection }

    ThemedBackground(settings.theme) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    title = {
                        Text(
                            when {
                                selecting -> "${selection.size} of ${items.size} selected"
                                loaded.value == null -> "Album"
                                else -> "Album (${items.size})"
                            },
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (selecting) { selecting = false; selection = emptySet() } else if (settled()) onBack()
                        }) {
                            Icon(
                                if (selecting) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack,
                                if (selecting) "Cancel selection" else "Back",
                            )
                        }
                    },
                    actions = {
                        if (selecting) {
                            IconButton(enabled = selection.isNotEmpty(), onClick = {
                                shareFiles(context, selectedItems().map { it.file })
                            }) { Icon(Icons.Filled.Share, "Share") }
                            IconButton(enabled = selection.isNotEmpty(), onClick = {
                                saveToPhone(selectedItems().map { it.file })
                            }) { Icon(Icons.Filled.Download, "Save to phone") }
                            IconButton(enabled = selection.isNotEmpty(), onClick = { confirmDelete = true }) {
                                Icon(Icons.Filled.Delete, "Delete")
                            }
                        } else {
                            IconButton(onClick = { if (settled()) onOpenMagic() }) {
                                Icon(Icons.Filled.AutoAwesome, "Magic recipes")
                            }
                            // The menu hangs off its own button, not off the end of the bar.
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Select") }, enabled = items.isNotEmpty(), onClick = {
                                        menu = false; selecting = true
                                    })
                                    DropdownMenuItem(text = { Text("Save all to phone") }, enabled = items.isNotEmpty(), onClick = {
                                        menu = false; saveToPhone(items.map { it.file })
                                    })
                                    DropdownMenuItem(text = { Text("Share all") }, enabled = items.isNotEmpty(), onClick = {
                                        menu = false; shareFiles(context, items.map { it.file })
                                    })
                                }
                            }
                        }
                    },
                )
            },
        ) { padding ->
            if (items.isEmpty()) {
                if (loaded.value != null) {
                    Column(
                        Modifier.fillMaxSize().padding(padding),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Album Empty", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text("Take a photo to add one.")
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(items, key = { it.relativePath }) { item ->
                        val selected = item.relativePath in selection
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(10.dp))
                                // Only when selected: a zero-width border is a hairline, not nothing.
                                .then(
                                    if (selected) {
                                        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                                    } else {
                                        Modifier
                                    },
                                )
                                .combinedClickable(
                                    onClick = {
                                        if (selecting) {
                                            selection = if (selected) selection - item.relativePath else selection + item.relativePath
                                        } else if (settled()) {
                                            onOpen(item)
                                        }
                                    },
                                    onLongClick = {
                                        selecting = true
                                        selection = selection + item.relativePath
                                    },
                                ),
                        ) {
                            AsyncImage(
                                model = item.file,
                                contentDescription = item.file.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.matchParentSize(),
                            )
                            if (selected) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp)
                                        .background(Color.White, CircleShape),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        val count = selectedItems().size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete $count photo${if (count == 1) "" else "s"}?") },
            text = { Text("The AI results are removed. The photos you took are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val targets = selectedItems()
                    scope.launch {
                        val (deleted, stuck) = withContext(Dispatchers.IO) {
                            val removed = targets.count { container.gallery.delete(it.file) }
                            // Refreshed from here: this scope ends with the screen, the deletions do not.
                            container.onGalleryChanged()
                            // Already gone (deleted elsewhere) is not a failure, as on the camera's web page.
                            val remaining = targets.filter { it.file.exists() }.mapTo(HashSet<String>()) { it.relativePath }
                            removed to remaining
                        }
                        val message = when {
                            stuck.isNotEmpty() -> "Deleted $deleted. ${stuck.size} could not be deleted."
                            deleted > 0 -> "Deleted $deleted."
                            else -> "Already deleted."
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        // What could not be removed stays selected so it can be tried again.
                        selection = selection intersect stuck
                        if (selection.isEmpty()) selecting = false
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
