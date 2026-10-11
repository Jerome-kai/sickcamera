package io.github.jeromekai.latent.album

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.jeromekai.latent.container
import io.github.jeromekai.latent.core.gallery.GalleryItem
import io.github.jeromekai.latent.data.PhoneGallery
import io.github.jeromekai.latent.ui.ThemedBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumScreen(onBack: () -> Unit, onOpen: (GalleryItem) -> Unit, onOpenMagic: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val version by container.galleryVersion.collectAsStateWithLifecycle()

    // Opening the album clears the "new results" sparkle, as on the camera.
    DisposableEffect(Unit) {
        container.albumOpen = true
        onDispose { container.albumOpen = false }
    }

    val items by produceState(initialValue = emptyList<GalleryItem>(), version) {
        value = withContext(Dispatchers.IO) { container.gallery.list() }
    }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var selecting by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val legacyStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun selectedItems() = items.filter { it.relativePath in selection }

    fun saveToPhone(targets: List<GalleryItem>) {
        if (PhoneGallery.needsLegacyPermission(context)) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) legacyStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        scope.launch {
            val saved = withContext(Dispatchers.IO) { targets.count { PhoneGallery.saveToPictures(context, it.file) != null } }
            Toast.makeText(context, "Saved $saved to Pictures/${PhoneGallery.ALBUM}", Toast.LENGTH_SHORT).show()
        }
    }

    ThemedBackground(settings.theme) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    title = {
                        Text(if (selecting) "${selection.size} selected" else "Album (${items.size})")
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (selecting) { selecting = false; selection = emptySet() } else onBack()
                        }) {
                            Icon(if (selecting) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        if (selecting) {
                            IconButton(enabled = selection.isNotEmpty(), onClick = {
                                PhoneGallery.shareIntent(context, selectedItems().map { it.file })?.let(context::startActivity)
                            }) { Icon(Icons.Filled.Share, "Share") }
                            IconButton(enabled = selection.isNotEmpty(), onClick = { saveToPhone(selectedItems()) }) {
                                Icon(Icons.Filled.Download, "Save to phone")
                            }
                            IconButton(enabled = selection.isNotEmpty(), onClick = { confirmDelete = true }) {
                                Icon(Icons.Filled.Delete, "Delete")
                            }
                        } else {
                            IconButton(onClick = onOpenMagic) { Icon(Icons.Filled.AutoAwesome, "Magic recipes") }
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Select") }, enabled = items.isNotEmpty(), onClick = {
                                    menu = false; selecting = true
                                })
                                DropdownMenuItem(text = { Text("Save all to phone") }, enabled = items.isNotEmpty(), onClick = {
                                    menu = false; saveToPhone(items)
                                })
                                DropdownMenuItem(text = { Text("Share all") }, enabled = items.isNotEmpty(), onClick = {
                                    menu = false
                                    PhoneGallery.shareIntent(context, items.map { it.file })?.let(context::startActivity)
                                })
                            }
                        }
                    },
                )
            },
        ) { padding ->
            if (items.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(padding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Album Empty", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("Take a photo to add one.")
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(items, key = { it.relativePath }) { item ->
                        val selected = item.relativePath in selection
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                                .combinedClickable(
                                    onClick = {
                                        if (selecting) {
                                            selection = if (selected) selection - item.relativePath else selection + item.relativePath
                                        } else {
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
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${selection.size} image(s)?") },
            text = { Text("The AI results are removed. The photos you took are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val targets = selectedItems()
                    scope.launch {
                        val deleted = withContext(Dispatchers.IO) { targets.count { container.gallery.delete(it.file) } }
                        val failed = targets.size - deleted
                        Toast.makeText(
                            context,
                            if (failed == 0) "Deleted $deleted" else "Deleted $deleted, $failed could not be removed",
                            Toast.LENGTH_SHORT,
                        ).show()
                        selection = emptySet()
                        selecting = false
                        container.onGalleryChanged()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
