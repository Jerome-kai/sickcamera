package io.github.jeromekai.latent.models

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeromekai.latent.container
import io.github.jeromekai.latent.core.model.Defaults
import io.github.jeromekai.latent.core.model.ImageApi
import io.github.jeromekai.latent.core.model.ModelPreset
import io.github.jeromekai.latent.core.model.Normalize
import io.github.jeromekai.latent.core.model.ProviderRecipe
import io.github.jeromekai.latent.ui.GlassCard
import io.github.jeromekai.latent.ui.ScreenScaffold
import io.github.jeromekai.latent.ui.SectionTitle
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import java.io.IOException

/**
 * A row as it is being edited. The store drops a preset with no model name, so
 * a row still being typed cannot live there; it lives here, and only complete
 * rows are written through.
 */
private data class Draft(
    val id: String,
    val label: String,
    val model: String,
    val api: ImageApi,
    /** False for a row just added, so it is not flagged before anything is typed. */
    val touched: Boolean = true,
) {
    val hasModel: Boolean get() = model.isNotBlank()
}

private fun ModelPreset.toDraft() = Draft(id = id, label = label, model = model, api = api)

private fun Draft.toPreset() = ModelPreset(id = id, label = label.trim(), model = model.trim(), api = api)

/** What the row is called on screen: the store's own fallback is the model after the last '/'. */
private fun Draft.displayName(): String =
    label.trim().ifEmpty { model.trim().substringAfterLast('/') }.ifEmpty { "New model" }

/** Cut by code points, as the store does, so a pasted emoji is never split. */
private fun limitLabel(text: String): String {
    val max = Normalize.MODEL_LABEL_MAX_LENGTH
    return if (text.codePointCount(0, text.length) <= max) text else text.substring(0, text.offsetByCodePoints(0, max))
}

private fun ImageApi.title(): String = when (this) {
    ImageApi.EDITS -> "Edits"
    ImageApi.CHAT -> "Chat"
    ImageApi.GENERATIONS -> "Generations"
}

private fun ImageApi.explanation(): String = when (this) {
    ImageApi.EDITS -> "OpenAI /images/edits, for api.openai.com and gpt-image models."
    ImageApi.CHAT -> "A chat model that returns images, for gateways such as Vercel."
    ImageApi.GENERATIONS -> "One image only: reference images are skipped. For SiliconFlow and similar."
}

private fun ProviderRecipe.needs(): String = when (id) {
    "openai" -> "Needs an OpenAI API key."
    "vercel" -> "Needs a Vercel AI Gateway API key."
    "siliconflow" -> "Needs a SiliconFlow API key."
    else -> "Needs an API key for $name."
}

/** An empty base URL means OpenAI, and so does its spelled-out address. */
private fun ProviderRecipe.servesBaseUrl(current: String): Boolean =
    baseUrl.ifEmpty { Defaults.OPENAI_BASE_URL } == current.ifEmpty { Defaults.OPENAI_BASE_URL }

/**
 * The list the camera's model dial turns through. On the camera a hand-edited
 * data/models.json was the only way to change it; here it is edited in place.
 */
@OptIn(FlowPreview::class)
@Composable
fun ModelsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val presets by container.models.presets.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val queue by container.queue.collectAsStateWithLifecycle()

    val drafts = remember { container.models.value.map { it.toDraft() }.toMutableStateList() }
    var focusId by remember { mutableStateOf<String?>(null) }
    var pendingRecipe by remember { mutableStateOf<ProviderRecipe?>(null) }
    var removingId by remember { mutableStateOf<String?>(null) }

    // What the camera really uses, read from the store rather than the drafts.
    val activeId = (presets.firstOrNull { it.id == settings.selectedModelId } ?: presets.firstOrNull())?.id.orEmpty()
    // With no complete row the store keeps its last good list, so say that.
    val nothingComplete = drafts.none { it.hasModel }

    fun persist() {
        val complete = drafts.filter { it.hasModel }.map { it.toPreset() }
        // An empty list would make the store fall back to its stock preset.
        if (complete.isEmpty() || complete == container.models.value) return
        try {
            container.models.replaceAll(complete)
        } catch (_: IOException) {
            Toast.makeText(context, "Could not save the model list", Toast.LENGTH_SHORT).show()
        }
    }

    // Typing writes after a pause rather than per keystroke: every write is an fsync.
    LaunchedEffect(Unit) {
        snapshotFlow { drafts.toList() }.debounce(400).collect { persist() }
    }
    // Leaving inside that pause must not lose the last edit.
    DisposableEffect(Unit) { onDispose { persist() } }

    fun edit(id: String, change: (Draft) -> Draft) {
        val index = drafts.indexOfFirst { it.id == id }
        if (index >= 0) drafts[index] = change(drafts[index])
    }

    fun select(id: String) {
        // Flush first, so the store knows the row before settings points at it.
        persist()
        container.settings.update { it.copy(selectedModelId = id) }
    }

    fun add() {
        val taken = drafts.map { it.id }.toSet()
        var number = drafts.size + 1
        while ("model-$number" in taken) number += 1
        val id = "model-$number"
        drafts.add(Draft(id = id, label = "", model = "", api = ImageApi.EDITS, touched = false))
        focusId = id
    }

    // At least one complete row must survive, or the store would quietly bring back its default.
    fun canRemove(draft: Draft): Boolean = drafts.any { it.id != draft.id && it.hasModel }

    fun remove(id: String) {
        val draft = drafts.firstOrNull { it.id == id } ?: return
        if (!canRemove(draft)) return
        drafts.remove(draft)
        persist()
        if (id == activeId) {
            drafts.firstOrNull { it.hasModel }?.let { next ->
                container.settings.update { it.copy(selectedModelId = next.id) }
            }
        }
    }

    fun applyRecipe(recipe: ProviderRecipe) {
        val preset = recipe.model
        val draft = preset.toDraft()
        // Same id replaces in place; failing that the same model name does, so
        // tapping OpenAI on a fresh install does not list gpt-image-2 twice.
        val index = drafts.indexOfFirst { it.id == preset.id }.takeIf { it >= 0 }
            ?: drafts.indexOfFirst { it.model.trim() == preset.model }
        if (index >= 0) drafts[index] = draft else drafts.add(draft)
        persist()
        container.settings.update {
            it.copy(
                baseUrl = recipe.baseUrl,
                magicApi = recipe.magicApi,
                magicModel = recipe.magicModel,
                selectedModelId = preset.id,
            )
        }
    }

    ScreenScaffold(title = "Models", onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassCard {
                Text("Turning the dial on the camera changes the model for the next photo only.")
                Spacer(Modifier.height(4.dp))
                Text("Photos already queued keep the model they were taken with.")
            }

            SectionTitle("Your models")
            for (draft in drafts) {
                key(draft.id) {
                    ModelCard(
                        draft = draft,
                        active = draft.id == activeId,
                        nothingComplete = nothingComplete,
                        canRemove = canRemove(draft),
                        autoFocus = draft.id == focusId,
                        onAutoFocused = { focusId = null },
                        onSelect = { select(draft.id) },
                        onChange = { change -> edit(draft.id, change) },
                        // A row nobody filled in has nothing to lose, so no question.
                        onRemove = { if (draft.hasModel) removingId = draft.id else remove(draft.id) },
                    )
                }
            }
            OutlinedButton(onClick = { add() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add a model")
            }
            if (drafts.any { !canRemove(it) }) {
                Text(
                    "At least one model with a name is needed, so the last one cannot be removed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            SectionTitle("Provider setups")
            Text(
                "One tap sets the server, the magic-prompt model and an image model that work together. " +
                    "Your API key is left as it is.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            for (recipe in Defaults.providerRecipes) {
                key(recipe.id) {
                    RecipeCard(
                        recipe = recipe,
                        inUse = recipe.servesBaseUrl(settings.baseUrl),
                        onClick = { pendingRecipe = recipe },
                    )
                }
            }
        }
    }

    val removing = removingId?.let { id -> drafts.firstOrNull { it.id == id } }
    if (removing != null) {
        AlertDialog(
            onDismissRequest = { removingId = null },
            title = { Text("Remove ${removing.displayName()}?") },
            text = {
                val note = if (removing.id == activeId) " It is the active model, so the first one left becomes active." else ""
                Text("Photos already queued with it keep using it.$note")
            },
            confirmButton = {
                TextButton(onClick = { removingId = null; remove(removing.id) }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removingId = null }) { Text("Cancel") } },
        )
    }

    pendingRecipe?.let { recipe ->
        AlertDialog(
            onDismissRequest = { pendingRecipe = null },
            title = { Text("Switch to ${recipe.name}?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This changes where your photos are sent.")
                    Text("Server: ${recipe.baseUrl.ifEmpty { "api.openai.com (the default)" }}")
                    Text("Magic prompts use: ${recipe.magicModel}")
                    Text("Adds ${recipe.model.label} (${recipe.model.model}) to your models and makes it the active one.")
                    Text("${recipe.needs()} Add it in Settings if you have not yet.")
                    // The job pins its model, but the server is read when it runs.
                    if (queue.pending > 0) {
                        Text(
                            "${queue.pending} queued photo(s) keep their model but will be sent to the new server, " +
                                "so they may fail.",
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pendingRecipe = null; applyRecipe(recipe) }) { Text("Switch") }
            },
            dismissButton = { TextButton(onClick = { pendingRecipe = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ModelCard(
    draft: Draft,
    active: Boolean,
    nothingComplete: Boolean,
    canRemove: Boolean,
    autoFocus: Boolean,
    onAutoFocused: () -> Unit,
    onSelect: () -> Unit,
    onChange: ((Draft) -> Draft) -> Unit,
    onRemove: () -> Unit,
) {
    val modelFocus = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) {
            // A just-added row wants typing in. The field scrolls itself into view on focus.
            runCatching { modelFocus.requestFocus() }
            onAutoFocused()
        }
    }

    val name = draft.displayName()
    val quiet = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .selectable(selected = active, enabled = draft.hasModel, role = Role.RadioButton, onClick = onSelect),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = active, onClick = null, enabled = draft.hasModel)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            active -> "Active · used for the next photo"
                            draft.hasModel -> "Tap to use this model"
                            else -> "Add a model name to use it"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (active) MaterialTheme.colorScheme.primary else quiet,
                    )
                }
            }
            IconButton(onClick = onRemove, enabled = canRemove) { Icon(Icons.Filled.Delete, "Remove $name") }
        }

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = draft.label,
            onValueChange = { text -> onChange { it.copy(label = limitLabel(text)) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Label") },
            // What the store will use if this is left empty.
            placeholder = { Text(limitLabel(draft.model.trim().substringAfterLast('/'))) },
            singleLine = true,
            supportingText = {
                Row(Modifier.fillMaxWidth()) {
                    Text("Shown on the camera's model dial", Modifier.weight(1f))
                    Text("${draft.label.codePointCount(0, draft.label.length)}/${Normalize.MODEL_LABEL_MAX_LENGTH}")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )

        Spacer(Modifier.height(4.dp))
        val showError = !draft.hasModel && draft.touched
        OutlinedTextField(
            value = draft.model,
            onValueChange = { text -> onChange { it.copy(model = text, touched = true) } },
            modifier = Modifier.fillMaxWidth().focusRequester(modelFocus),
            label = { Text("Model name") },
            placeholder = { Text("google/gemini-2.5-flash-image") },
            singleLine = true,
            isError = showError,
            supportingText = {
                Text(
                    when {
                        draft.hasModel -> "Exactly as your provider names it."
                        !draft.touched -> "Required. The row is saved once it has a model name."
                        nothingComplete -> "A model name is required. The last saved one stays in use until you add one."
                        else -> "A model name is required. Until you add one, the camera leaves this row out."
                    },
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        )

        Spacer(Modifier.height(8.dp))
        Text("API mode", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        for (mode in ImageApi.entries) {
            val selected = draft.api == mode
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onChange { it.copy(api = mode) } })
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(mode.title(), fontWeight = FontWeight.Medium)
                    Text(mode.explanation(), style = MaterialTheme.typography.bodySmall, color = quiet)
                }
            }
        }
    }
}

@Composable
private fun RecipeCard(recipe: ProviderRecipe, inUse: Boolean, onClick: () -> Unit) {
    val quiet = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    GlassCard(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClickLabel = "Use the ${recipe.name} setup", role = Role.Button, onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                recipe.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (inUse) {
                Text(
                    "In use",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(recipe.note)
        Spacer(Modifier.height(6.dp))
        Text(
            "Adds ${recipe.model.label} · ${recipe.model.model} · ${recipe.model.api.wire}",
            style = MaterialTheme.typography.bodySmall,
            color = quiet,
        )
        Text("${recipe.needs()} Add it in Settings.", style = MaterialTheme.typography.bodySmall, color = quiet)
    }
}
