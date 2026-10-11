package io.github.jeromekai.latent.settings

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeromekai.latent.AppContainer
import io.github.jeromekai.latent.container
import io.github.jeromekai.latent.core.model.Defaults
import io.github.jeromekai.latent.core.model.MagicApi
import io.github.jeromekai.latent.core.model.OutputFormat
import io.github.jeromekai.latent.core.model.Settings
import io.github.jeromekai.latent.core.queue.RetryPolicy
import io.github.jeromekai.latent.ui.GlassCard
import io.github.jeromekai.latent.ui.SectionTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val TAG = "GenerationSettings"

// The ranges Normalize.settings clamps to. The store would take any value and
// quietly change it, so the fields check first and only ever write a value the
// store will keep exactly as typed. Keep these in step with Normalize.
private val COMPRESSION = 0..100
private val IMAGE_TIMEOUT = 10..600
private val INPUT_EDGE = 256..4096
private val MAGIC_TIMEOUT = 5..300
private val ATTEMPTS = 0..100
private val PENDING = 0..1000

private val QUALITIES = listOf("low" to "Low", "medium" to "Medium", "high" to "High", "auto" to "Auto")
private val FORMATS = listOf(OutputFormat.JPEG to "JPEG", OutputFormat.PNG to "PNG", OutputFormat.WEBP to "WebP")
private val MAGIC_APIS = listOf(MagicApi.RESPONSES to "Responses", MagicApi.CHAT to "Chat")

// Same rule as Normalize.settings: anything else falls back to the default size.
private val SIZE_RULE = Regex("^\\s*(\\d{2,5})\\s*x\\s*(\\d{2,5})\\s*$")

/**
 * Every field saves on a single thread, in the order it was edited, so quick
 * typing cannot land "15" after "153". The scope belongs to the process rather
 * than the screen, so the last edit still saves if the owner leaves at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

private fun AppContainer.saveSettings(transform: (Settings) -> Settings) {
    writer.launch {
        try {
            settings.update(transform)
        } catch (error: Exception) {
            Log.w(TAG, "Could not save settings", error)
        }
    }
}

/**
 * How the AI picture is made: quality, size and format, the upload size and
 * timeouts, magic mode, and how hard the queue retries. Emits section titles
 * and cards for whatever column it is placed in.
 */
@Composable
fun GenerationSettingsSection() {
    val container = LocalContext.current.container
    val settings by container.settings.settings.collectAsStateWithLifecycle()

    fun save(transform: (Settings) -> Settings) = container.saveSettings(transform)

    // ---- photo quality ---------------------------------------------------------
    SectionTitle("Photo quality")
    GlassCard {
        FieldLabel("Quality")
        Spacer(Modifier.height(8.dp))
        SegmentedChoice(QUALITIES, settings.quality) { value -> save { it.copy(quality = value) } }
        FieldNote("Higher quality costs more and takes longer. Auto lets the model decide.")

        Spacer(Modifier.height(16.dp))
        SizeField(settings.size) { value -> save { it.copy(size = value) } }
        FieldNote("Portrait photos automatically get this size turned on its side, so a tall shot comes back tall.")

        Spacer(Modifier.height(16.dp))
        FieldLabel("Output format")
        Spacer(Modifier.height(8.dp))
        val lossless = settings.outputFormat == OutputFormat.PNG
        SegmentedChoice(FORMATS, settings.outputFormat) { value -> save { it.copy(outputFormat = value) } }

        Spacer(Modifier.height(12.dp))
        IntSettingSlider(
            label = "Compression",
            stored = settings.outputCompression,
            range = COMPRESSION,
            step = 1,
            enabled = !lossless,
            display = { if (lossless) "Lossless" else "$it" },
        ) { value -> save { it.copy(outputCompression = value) } }
        FieldNote("Higher keeps more detail and makes bigger files. PNG is lossless, so it has no compression setting.")
    }

    // ---- upload ------------------------------------------------------------------
    SectionTitle("Upload")
    GlassCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberSettingField(
                label = "Long edge",
                stored = settings.inputWidth,
                range = INPUT_EDGE,
                hint = "256 to 4096 px",
                modifier = Modifier.weight(1f),
            ) { value -> save { it.copy(inputWidth = value) } }
            NumberSettingField(
                label = "Short edge",
                stored = settings.inputHeight,
                range = INPUT_EDGE,
                hint = "256 to 4096 px",
                modifier = Modifier.weight(1f),
            ) { value -> save { it.copy(inputHeight = value) } }
        }
        FieldNote("Each photo is scaled to this size before it is uploaded. A smaller upload is faster and cheaper; a larger one gives the model more detail to work with.")
    }

    // ---- timeout -----------------------------------------------------------------
    SectionTitle("Timeout")
    GlassCard {
        IntSettingSlider(
            label = "Image timeout",
            stored = settings.imageTimeoutSeconds,
            range = IMAGE_TIMEOUT,
            step = 5,
            display = { seconds(it) },
        ) { value -> save { it.copy(imageTimeoutSeconds = value) } }
        FieldNote("How long to wait for the image model before an attempt counts as failed. The queue then tries again.")
    }

    // ---- magic mode --------------------------------------------------------------
    SectionTitle("Magic mode")
    GlassCard {
        // The whole row toggles, so the switch is labelled for screen readers.
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = settings.magicEnabled,
                    role = Role.Switch,
                    onValueChange = { on -> save { it.copy(magicEnabled = on) } },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                FieldLabel("Allow magic shots")
                FieldNote("Hold the shutter to have the app look at the scene and write a prompt for it. When off, holding the shutter does nothing.")
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = settings.magicEnabled, onCheckedChange = null)
        }

        Spacer(Modifier.height(16.dp))
        FieldLabel("Planner API")
        Spacer(Modifier.height(8.dp))
        SegmentedChoice(MAGIC_APIS, settings.magicApi) { value -> save { it.copy(magicApi = value) } }
        FieldNote(
            "Responses suits ${providersUsing(MagicApi.RESPONSES)}. " +
                "Chat is for providers with no Responses endpoint, such as ${providersUsing(MagicApi.CHAT)}.",
        )

        Spacer(Modifier.height(16.dp))
        CommitTextField(
            label = "Planner model",
            stored = settings.magicModel,
            parse = { raw -> raw.trim().takeIf { it.isNotEmpty() } },
            hint = "A vision model, such as gpt-4.1-mini. Gateways often want a prefix: openai/gpt-4.1-mini.",
            error = "Enter a model name.",
            keyboardType = KeyboardType.Uri,
        ) { value -> save { it.copy(magicModel = value) } }

        Spacer(Modifier.height(16.dp))
        IntSettingSlider(
            label = "Planner timeout",
            stored = settings.magicTimeoutSeconds,
            range = MAGIC_TIMEOUT,
            step = 5,
            display = { seconds(it) },
        ) { value -> save { it.copy(magicTimeoutSeconds = value) } }
        FieldNote("How long to wait for the planner to describe a photo.")
    }

    // ---- queue -------------------------------------------------------------------
    SectionTitle("Queue")
    GlassCard {
        NumberSettingField(
            label = "Max attempts",
            stored = settings.maxAttempts,
            range = ATTEMPTS,
            hint = "0 retries forever",
        ) { value -> save { it.copy(maxAttempts = value) } }
        FieldNote(
            "A wrong key or a refused prompt stops at once, whatever this is set to. " +
                "The cap is for network trouble: waits grow from 15 s to 15 min between tries. " +
                giveUpSummary(settings.maxAttempts),
        )

        Spacer(Modifier.height(16.dp))
        NumberSettingField(
            label = "Max pending photos",
            stored = settings.maxPendingGenerations,
            range = PENDING,
            hint = "0 means unlimited",
        ) { value -> save { it.copy(maxPendingGenerations = value) } }
        FieldNote("While this many photos are waiting for their result, the shutter refuses new ones until the queue catches up.")
    }
}

// ---- wording -----------------------------------------------------------------------

private fun seconds(value: Int): String = when {
    value < 60 -> "$value s"
    value % 60 == 0 -> "${value / 60} min"
    else -> "${value / 60} min ${value % 60} s"
}

/** Provider names from the one-tap recipes, so this note cannot drift from them. */
private fun providersUsing(api: MagicApi): String =
    Defaults.providerRecipes.filter { it.magicApi == api }.joinToString { it.name }

/** Total of the waits the retry policy sits through before the last attempt. */
private fun giveUpSummary(maxAttempts: Int): String {
    if (maxAttempts == 0) return "It keeps retrying until the photo goes through."
    val total = (1 until maxAttempts).sumOf { RetryPolicy.delaySeconds(it) }
    if (total == 0L) return "It gives up after the first failure."
    val minutes = (total + 30) / 60
    val span = when {
        total < 60 -> "$total s"
        minutes < 120 -> "$minutes min"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
    return "With $maxAttempts attempts it gives up after about $span of retrying."
}

private fun parseSize(raw: String): String? {
    val (width, height) = SIZE_RULE.find(raw.lowercase())?.destructured ?: return null
    return if (width.toInt() > 0 && height.toInt() > 0) "${width}x$height" else null
}

// ---- controls ------------------------------------------------------------------------

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun FieldNote(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SegmentedChoice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                // The theme's accent rather than Material's default tint; and no
                // tick icon, which would crowd four options onto a phone.
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = accent.copy(alpha = 0.16f),
                    activeContentColor = accent,
                    activeBorderColor = accent,
                ),
                icon = {},
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

/**
 * A text field that keeps its own text while it is being typed in and saves
 * every acceptable edit. [parse] returns the value to save, or null when the
 * text is not acceptable, which shows [error] and writes nothing. Binding the
 * store straight to the field would make the cursor jump as it normalises.
 */
@Composable
private fun CommitTextField(
    label: String,
    stored: String,
    parse: (String) -> String?,
    hint: String,
    error: String,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onCommit: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var text by remember { mutableStateOf(stored) }
    var focused by remember { mutableStateOf(false) }

    // Follow the store (a recipe applied elsewhere, a clamp) but never rewrite
    // the text under the owner's cursor.
    LaunchedEffect(stored) { if (!focused) text = stored }

    val accepted = parse(text)
    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            text = next
            parse(next)?.let(onCommit)
        },
        modifier = modifier.onFocusChanged { state ->
            // Leaving the field shows what is really stored, not what was typed.
            if (focused && !state.isFocused) text = parse(text) ?: stored
            focused = state.isFocused
        },
        label = { Text(label) },
        singleLine = true,
        isError = accepted == null,
        supportingText = { Text(if (accepted == null) error else hint) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
    )
}

@Composable
private fun NumberSettingField(
    label: String,
    stored: Int,
    range: IntRange,
    hint: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onCommit: (Int) -> Unit,
) {
    CommitTextField(
        label = label,
        stored = stored.toString(),
        parse = { raw -> raw.trim().toIntOrNull()?.takeIf { it in range }?.toString() },
        hint = hint,
        error = "Enter a whole number from ${range.first} to ${range.last}.",
        keyboardType = KeyboardType.Number,
        modifier = modifier,
        onCommit = { onCommit(it.toInt()) },
    )
}

@Composable
private fun SizeField(stored: String, onCommit: (String) -> Unit) {
    CommitTextField(
        label = "Output size",
        stored = stored,
        parse = ::parseSize,
        hint = "Width x height in pixels, such as 1536x1024",
        error = "Use width x height in pixels, such as 1536x1024.",
        keyboardType = KeyboardType.Ascii,
        onCommit = onCommit,
    )
}

/**
 * A whole-number slider that snaps to [step]. The thumb follows the finger on
 * local state and the store is written once, on release: a drag would
 * otherwise write the settings file on every pixel it moves.
 */
@Composable
private fun IntSettingSlider(
    label: String,
    stored: Int,
    range: IntRange,
    step: Int,
    display: (Int) -> String,
    enabled: Boolean = true,
    onCommit: (Int) -> Unit,
) {
    var dragging by remember { mutableStateOf<Int?>(null) }

    // Back to the stored value as soon as the write lands, so what the slider
    // shows after release is what the store kept.
    LaunchedEffect(stored) { dragging = null }
    val shown = dragging ?: stored

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FieldLabel(label)
        Text(
            display(shown),
            style = MaterialTheme.typography.titleSmall,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    }
    Slider(
        value = shown.toFloat(),
        onValueChange = { raw ->
            dragging = ((raw / step).roundToInt() * step).coerceIn(range.first, range.last)
        },
        enabled = enabled,
        valueRange = range.first.toFloat()..range.last.toFloat(),
        onValueChangeFinished = {
            val picked = dragging
            if (picked != null && picked != stored) onCommit(picked)
        },
    )
}
