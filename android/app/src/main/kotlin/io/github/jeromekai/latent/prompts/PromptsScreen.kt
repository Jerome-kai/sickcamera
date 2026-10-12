package io.github.jeromekai.latent.prompts

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.jeromekai.latent.container
import io.github.jeromekai.latent.core.model.Normalize
import io.github.jeromekai.latent.core.model.Prompt
import io.github.jeromekai.latent.ui.GlassCard
import io.github.jeromekai.latent.ui.ScreenScaffold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** The web editor's debounce: a save fires this long after the last change. */
private const val AUTOSAVE_DELAY_MS = 500L

/** What a card's fields show. The store only ever sees it after the debounce. */
private data class Draft(val title: String, val body: String)

/**
 * The prompt editor: the camera's Prompts tab and its on-device picker in one.
 * There is no save button -- every change autosaves.
 */
@Composable
fun PromptsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val prompts by container.prompts.prompts.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Text lives here, keyed by prompt id, and is never overwritten from the
    // store while the screen is open. The store trims and re-defaults what it
    // is given, so echoing it back would eat a trailing space mid-sentence and
    // refill a title the owner just cleared to retype. Keying by id also keeps
    // adding or removing a prompt from scrambling the other cards.
    val drafts = remember { mutableStateMapOf<String, Draft>() }
    // Ids whose draft has not reached the store yet. Touched on the main thread only.
    val dirty = remember { mutableSetOf<String>() }
    var edits by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    // Set once a back tap found the save failing: asking again means leave anyway.
    var giveUpOnSave by remember { mutableStateOf(false) }
    var scrollTo by remember { mutableStateOf<String?>(null) }
    // Survives the activity being recreated while the system picker is up.
    var pickTarget by rememberSaveable { mutableStateOf<String?>(null) }
    // Bumped when a prompt's image file changes. Replacing keeps the file name,
    // so the prompt (and the list) is equal before and after and nothing else
    // would tell the thumbnail to reload.
    val imageVersions = remember { mutableStateMapOf<String, Int>() }

    val selectedId = (prompts.firstOrNull { it.id == settings.selectedPromptId } ?: prompts.firstOrNull())?.id

    /** True when nothing is left unsaved by a failed write. */
    suspend fun flush(): Boolean {
        val pending = dirty.mapNotNull { id -> drafts[id]?.let { id to it } }
        if (pending.isEmpty()) return true
        try {
            withContext(Dispatchers.IO) {
                // Through edit() so a prompt's reference image is never disturbed by a text edit.
                pending.forEach { (id, draft) ->
                    container.prompts.edit(id) { it.copy(title = draft.title, body = draft.body) }
                }
            }
        } catch (_: IOException) {
            status = "Save failed."
            return false
        }
        // Typing during the write leaves a newer draft behind; that one stays dirty.
        pending.forEach { (id, draft) ->
            val latest = drafts[id]
            if (latest == null || latest == draft) dirty.remove(id)
        }
        if (dirty.isEmpty()) status = "Saved."
        return true
    }

    fun edit(prompt: Prompt, change: (Draft) -> Draft) {
        val current = drafts[prompt.id] ?: Draft(prompt.title, prompt.body)
        val next = change(current)
        if (next == current) return
        drafts[prompt.id] = next
        dirty += prompt.id
        giveUpOnSave = false
        status = "Saving..."
        edits += 1
    }

    // A back tap inside the debounce window would otherwise lose the last edit.
    fun leave() {
        if (leaving) return
        leaving = true
        if (dirty.isEmpty()) onBack() else scope.launch {
            // A failed write keeps the screen, and the text on it, for another
            // try; going back regardless would throw the edit away unseen.
            if (flush() || giveUpOnSave) {
                onBack()
            } else {
                giveUpOnSave = true
                leaving = false
                status = "Save failed. Go back again to leave without saving."
            }
        }
    }

    fun addPrompt() {
        scope.launch {
            val created = try {
                withContext(Dispatchers.IO) { container.prompts.add() }
            } catch (_: IOException) {
                null
            }
            if (created != null) scrollTo = created.id else status = "Could not add a prompt."
        }
    }

    fun usePrompt(prompt: Prompt) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { container.settings.update { it.copy(selectedPromptId = prompt.id) } }
            } catch (_: IOException) {
                status = "Could not select the prompt."
            }
        }
    }

    fun deletePrompt(prompt: Prompt) {
        scope.launch {
            if (PromptReferences.removePrompt(container, prompt)) {
                drafts.remove(prompt.id)
                dirty.remove(prompt.id)
                imageVersions.remove(prompt.id)
                // The camera falls back to the first prompt when the one in use
                // is removed. Record that: the store reuses ids, so a dangling
                // selection would silently land on the next prompt added under
                // the removed one's id.
                if (container.settings.value.selectedPromptId == prompt.id) {
                    try {
                        withContext(Dispatchers.IO) {
                            container.settings.update { current ->
                                if (current.selectedPromptId != prompt.id) current
                                else current.copy(selectedPromptId = container.prompts.value.first().id)
                            }
                        }
                    } catch (_: IOException) {
                        status = "Could not select the prompt."
                    }
                }
            } else {
                status = "Could not remove the prompt."
            }
        }
    }

    fun attach(promptId: String, uri: Uri) {
        scope.launch {
            busy = true
            status = "Uploading image..."
            when (val outcome = PromptReferences.attach(container, promptId, uri, context)) {
                is PromptReferences.Outcome.Attached -> {
                    imageVersions[promptId] = (imageVersions[promptId] ?: 0) + 1
                    status = "Image attached."
                }
                is PromptReferences.Outcome.Failed -> status = outcome.message
            }
            busy = false
        }
    }

    fun removeImage(prompt: Prompt) {
        scope.launch {
            busy = true
            status = "Removing image..."
            if (PromptReferences.remove(container, prompt)) {
                imageVersions[prompt.id] = (imageVersions[prompt.id] ?: 0) + 1
                status = "Image removed."
            } else {
                status = "Could not remove the image."
            }
            busy = false
        }
    }

    // The system photo picker needs no storage permission.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = pickTarget
        pickTarget = null
        if (uri != null && target != null) attach(target, uri)
    }

    // Debounce: each change restarts the wait, and only the last one saves.
    LaunchedEffect(Unit) {
        snapshotFlow { edits }.collectLatest { count ->
            if (count == 0) return@collectLatest
            delay(AUTOSAVE_DELAY_MS)
            flush()
        }
    }

    // Leaving by any route but our own back handling (a rotation, a
    // notification that jumps to the album) would drop an edit still inside the
    // debounce window, and this screen's scope is cancelled by then: write from
    // a scope of its own. A no-op after a normal back, which has saved already.
    DisposableEffect(Unit) {
        onDispose {
            val pending = dirty.mapNotNull { id -> drafts[id]?.let { id to it } }
            if (pending.isNotEmpty()) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        pending.forEach { (id, draft) ->
                            container.prompts.edit(id) { it.copy(title = draft.title, body = draft.body) }
                        }
                    } catch (_: IOException) {
                        // Nowhere left to say so.
                    }
                }
            }
        }
    }

    // A new prompt lands on top, but a lazy list keeps the row it was showing
    // in view; scroll once the list has actually grown.
    LaunchedEffect(scrollTo, prompts) {
        val id = scrollTo ?: return@LaunchedEffect
        val index = prompts.indexOfFirst { it.id == id }
        if (index >= 0) {
            listState.animateScrollToItem(index)
            scrollTo = null
        }
    }

    BackHandler { leave() }

    ScreenScaffold(
        title = "Prompts",
        onBack = { leave() },
        actions = {
            TextButton(onClick = { addPrompt() }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Add")
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // The scaffold already padded for the nav bar; without this the
                // keyboard would add that height a second time.
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            // Always takes its height, so the list does not jump as messages come and go.
            Text(
                status,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .heightIn(min = 20.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(prompts, key = { _, prompt -> prompt.id }) { index, prompt ->
                    val draft = drafts[prompt.id]
                    PromptCard(
                        number = index + 1,
                        title = draft?.title ?: prompt.title,
                        body = draft?.body ?: prompt.body,
                        selected = prompt.id == selectedId,
                        canRemove = prompts.size > 1,
                        busy = busy,
                        referenceFile = prompt.referenceImage.takeIf { it.isNotEmpty() }
                            ?.let { container.gallery.referenceImagePath(it) },
                        imageVersion = imageVersions[prompt.id] ?: 0,
                        onTitle = { typed -> edit(prompt) { it.copy(title = clampTitle(typed)) } },
                        onBody = { typed -> edit(prompt) { it.copy(body = typed) } },
                        onUse = { usePrompt(prompt) },
                        onRemove = { deletePrompt(prompt) },
                        onPickImage = {
                            pickTarget = prompt.id
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onRemoveImage = { removeImage(prompt) },
                    )
                }
            }
        }
    }
}

/** Titles are cut by code points, as Normalize does, so an emoji is never split. */
private fun clampTitle(value: String): String {
    val max = Normalize.PROMPT_TITLE_MAX_LENGTH
    return if (value.codePointCount(0, value.length) <= max) value
    else value.substring(0, value.offsetByCodePoints(0, max))
}

@Composable
private fun PromptCard(
    number: Int,
    title: String,
    body: String,
    selected: Boolean,
    canRemove: Boolean,
    busy: Boolean,
    referenceFile: File?,
    imageVersion: Int,
    onTitle: (String) -> Unit,
    onBody: (String) -> Unit,
    onUse: () -> Unit,
    onRemove: () -> Unit,
    onPickImage: () -> Unit,
    onRemoveImage: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    GlassCard(modifier = if (selected) Modifier.border(2.dp, accent, RoundedCornerShape(18.dp)) else Modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Prompt $number",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (selected) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("In use", color = accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                } else {
                    OutlinedButton(onClick = onUse, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Use") }
                }
                TextButton(
                    onClick = onRemove,
                    enabled = canRemove,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Remove") }
            }
            OutlinedTextField(
                value = title,
                onValueChange = onTitle,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Title") },
                supportingText = {
                    Text(
                        "${title.codePointCount(0, title.length)}/${Normalize.PROMPT_TITLE_MAX_LENGTH}",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End,
                    )
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            OutlinedTextField(
                value = body,
                onValueChange = onBody,
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                placeholder = { Text("Prompt body") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            ReferenceBlock(
                file = referenceFile,
                version = imageVersion,
                busy = busy,
                onPick = onPickImage,
                onRemove = onRemoveImage,
            )
        }
    }
}

/** The prompt-images branch's reference block: a thumbnail, its actions, and a hint. */
@Composable
private fun ReferenceBlock(
    file: File?,
    version: Int,
    busy: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (file != null) ReferenceThumb(file, version)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = onPick, enabled = !busy) {
                    Text(if (file != null) "Replace image" else "Add image")
                }
                if (file != null) {
                    TextButton(onClick = onRemove, enabled = !busy) { Text("Remove image") }
                }
            }
        }
        Text(
            "Sent with every photo as a style reference. The generations API takes only one image, so it skips this.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun ReferenceThumb(file: File, version: Int) {
    val context = LocalContext.current
    // null while checking. 0 means the file is gone though the prompt still
    // names it, which a hand-edited prompts.json or a cleared cache can cause;
    // the camera's picker marker had the same blind spot.
    val modified by produceState<Long?>(null, file, version) {
        value = withContext(Dispatchers.IO) { file.lastModified() }
    }
    Box(
        modifier = Modifier
            .size(width = 128.dp, height = 96.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.06f)),
        contentAlignment = Alignment.Center,
    ) {
        val stamp = modified
        when {
            stamp == null -> Unit
            stamp == 0L -> Icon(
                Icons.Filled.BrokenImage,
                contentDescription = "The reference image file is missing",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            else -> AsyncImage(
                // A replaced image keeps its file name, so the name alone would
                // hit Coil's memory cache and keep showing the old picture.
                model = remember(file, stamp, version) {
                    ImageRequest.Builder(context)
                        .data(file)
                        .memoryCacheKey("${file.name}:$stamp:$version")
                        .build()
                },
                contentDescription = "Reference image",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
