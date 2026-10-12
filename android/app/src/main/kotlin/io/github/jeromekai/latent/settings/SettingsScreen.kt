package io.github.jeromekai.latent.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeromekai.latent.AppContainer
import io.github.jeromekai.latent.container
import io.github.jeromekai.latent.core.model.AppTheme
import io.github.jeromekai.latent.core.model.Defaults
import io.github.jeromekai.latent.core.model.Normalize
import io.github.jeromekai.latent.core.model.Settings
import io.github.jeromekai.latent.data.PhoneGallery
import io.github.jeromekai.latent.ui.GlassCard
import io.github.jeromekai.latent.ui.ScreenScaffold
import io.github.jeromekai.latent.ui.SectionTitle
import io.github.jeromekai.latent.ui.palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val TAG = "SettingsScreen"
private const val KEY_PAGE = "https://platform.openai.com/api-keys"
private const val TYPING_PAUSE_MS = 500L

private val KeyAcceptedColor = Color(0xFF2E7D32)
private val KeyRefusedColor = Color(0xFFB3261E)

/**
 * Settings are written from here, not from the screen's own scope, so a tap
 * made just before leaving the screen still lands. One thread keeps the
 * writes in the order they were made.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

private fun AppContainer.saveSettings(transform: (Settings) -> Settings) {
    persistScope.launch {
        try {
            settings.update(transform)
        } catch (error: Exception) {
            // Disk full or similar: nothing was stored, so the control stays where it was.
            Log.w(TAG, "Could not save settings", error)
        }
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenPrompts: () -> Unit,
    onReplayTutorial: () -> Unit,
) {
    ScreenScaffold(title = "Settings", onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // The scaffold already padded for the nav bar; without this the
                // keyboard would add that height a second time.
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            SectionTitle("API key")
            ApiKeyCard()
            SectionTitle("Server")
            ServerCard(onOpenModels)
            SectionTitle("Appearance")
            AppearanceCard()
            SectionTitle("Capture")
            CaptureCard()
            GenerationSettingsSection()
            StorageSection()
            SectionTitle("Shortcuts")
            ShortcutsCard(onOpenPrompts, onReplayTutorial)
            SectionTitle("About")
            AboutCard()
        }
    }
}

// ---- API key -------------------------------------------------------------------

private sealed interface CheckState {
    data object Idle : CheckState
    data object Running : CheckState
    data class Done(val result: KeyCheckResult) : CheckState
}

@Composable
private fun ApiKeyCard() {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val hasKey by container.secureKey.hasKey.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    // Only OpenAI refuses /models for a bad key; see CheckStatus.
    val officialServer = settings.baseUrl.isEmpty() || settings.baseUrl.equals(Defaults.OPENAI_BASE_URL, ignoreCase = true)

    // The draft deliberately lives in plain remember, not rememberSaveable:
    // saved state is written out of process, and a key must go nowhere.
    var draft by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var savedCount by remember { mutableStateOf(0) }
    // A check answers for one server; a different address starts the question over.
    var checkState by remember(settings.baseUrl) { mutableStateOf<CheckState>(CheckState.Idle) }

    // Reading the hint decrypts the key, so it is fetched off the main thread.
    val hint by produceState<String?>(null, hasKey, savedCount) {
        value = if (hasKey) withContext(Dispatchers.IO) { container.secureKey.hint() } else null
    }

    fun saveKey() {
        val key = draft.trim()
        if (key.isEmpty() || saving) return
        if (!isSendableKey(key)) {
            // A stored key like this could never be sent, and would fail every photo.
            Toast.makeText(
                context,
                "A key has no spaces, line breaks or special characters. Check what was pasted.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        saving = true
        scope.launch {
            val stored = try {
                withContext(Dispatchers.IO) { container.secureKey.set(key) }
                true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Keystore trouble. Say nothing about why: the message must never carry the key.
                false
            }
            saving = false
            if (stored) {
                draft = ""
                visible = false
                checkState = CheckState.Idle
                savedCount++
                focusManager.clearFocus()
            }
            Toast.makeText(
                context,
                if (stored) "Key saved" else "This phone could not store the key",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun runCheck() {
        if (checkState is CheckState.Running) return
        checkState = CheckState.Running
        scope.launch {
            val endpoint = withContext(Dispatchers.IO) { container.endpoint() }
            checkState = CheckState.Done(checkKey(endpoint))
        }
    }

    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                when {
                    !hasKey -> "No key saved yet. Photos cannot be redrawn without one."
                    hint != null -> "Key saved ($hint)"
                    else -> "Key saved"
                },
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    // The last answer was about the saved key, not about what is being typed.
                    checkState = CheckState.Idle
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(if (hasKey) "Replace key" else "API key") },
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            if (visible) "Hide key" else "Show key",
                        )
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { saveKey() },
                    enabled = draft.isNotBlank() && !saving,
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
                OutlinedButton(
                    onClick = { runCheck() },
                    // It asks about the saved key; with a new one half-entered that would be a different answer.
                    enabled = hasKey && draft.isBlank() && checkState !is CheckState.Running,
                    modifier = Modifier.weight(1f),
                ) { Text("Check key") }
            }
            CheckStatus(checkState, officialServer)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { openKeyPage(context) }) { Text("Get a key") }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { confirmRemove = true },
                    enabled = hasKey,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Remove") }
            }
            HelperText("The key stays encrypted on this phone and is only sent to the server you choose below.")
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove the API key?") },
            text = { Text("Photos you take will not be redrawn until you add a key again.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        scope.launch {
                            withContext(Dispatchers.IO) { container.secureKey.clear() }
                            checkState = CheckState.Idle
                            Toast.makeText(context, "Key removed", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CheckStatus(state: CheckState, officialServer: Boolean) {
    val style = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    when (state) {
        CheckState.Idle -> Unit
        CheckState.Running -> Text("Asking the server…", style = style, color = muted)
        is CheckState.Done -> when (val result = state.result) {
            KeyCheckResult.Valid -> if (officialServer) {
                Text("The server accepted this key.", style = style, color = KeyAcceptedColor)
            } else {
                // Some gateways list their models to anyone, so an answer proves little.
                Text(
                    "The server answered and did not refuse this key. Some providers do not check keys when " +
                        "listing models, so your first photo is the real test.",
                    style = style,
                    color = muted,
                )
            }
            KeyCheckResult.Rejected -> Text(
                "The server refused this key. Check that it was copied in full and has not been revoked. " +
                    "A restricted key may also need permission to list models.",
                style = style,
                color = KeyRefusedColor,
            )
            KeyCheckResult.Malformed -> Text(
                "This key has a space, line break or special character, which no key has. Remove it and paste the key again.",
                style = style,
                color = KeyRefusedColor,
            )
            // Not the same as wrong: say so, or an offline phone looks like a bad key.
            is KeyCheckResult.Unverified -> Text(
                "The key could not be verified. ${result.reason} That does not mean the key is wrong.",
                style = style,
                color = muted,
            )
        }
    }
}

private fun openKeyPage(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KEY_PAGE)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No web browser found", Toast.LENGTH_SHORT).show()
    }
}

// ---- Server --------------------------------------------------------------------

/** Null when [raw] can be saved as the base URL: empty (OpenAI) or a well-formed https address. */
private fun baseUrlProblem(raw: String): String? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    if (value.startsWith("http://", ignoreCase = true)) {
        return "Only https:// addresses are allowed, so your key is never sent unencrypted. Not saved."
    }
    if (!value.startsWith("https://", ignoreCase = true)) return "The address must start with https://. Not saved."
    if (value.toHttpUrlOrNull() == null) return "That is not a valid web address. Not saved."
    return null
}

@Composable
private fun ServerCard(onOpenModels: () -> Unit) {
    val container = LocalContext.current.container
    var text by remember { mutableStateOf(container.settings.value.baseUrl) }
    val problem = baseUrlProblem(text)

    // The store trims a trailing slash; compare the same way so an untouched
    // field never rewrites the file.
    SaveWhenTypingPauses(text) { typed ->
        val wanted = typed.trim().trimEnd('/')
        if (baseUrlProblem(typed) == null && wanted != container.settings.value.baseUrl) {
            container.saveSettings { it.copy(baseUrl = wanted) }
        }
    }

    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Base URL") },
                placeholder = { Text("https://api.openai.com/v1") },
                isError = problem != null,
                supportingText = { Text(problem ?: "Leave empty to use api.openai.com.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            )
            HelperText("Other providers and gateways have their own address and model names. Provider setups fill in both.")
            OutlinedButton(onClick = onOpenModels, modifier = Modifier.fillMaxWidth()) {
                Text("Provider setups and models")
            }
        }
    }
}

// ---- Appearance ----------------------------------------------------------------

private val AppTheme.displayName: String
    get() = wire.replaceFirstChar { it.uppercase() }

@Composable
private fun AppearanceCard() {
    val container = LocalContext.current.container
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(settings.cameraUsername) }
    // The store applies exactly this, so the preview is what will be saved.
    val clean = Normalize.cameraUsername(name)

    SaveWhenTypingPauses(name) { typed ->
        val wanted = Normalize.cameraUsername(typed)
        if (wanted != container.settings.value.cameraUsername) {
            container.saveSettings { it.copy(cameraUsername = wanted) }
        }
    }

    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Theme", fontWeight = FontWeight.SemiBold)
            ThemeSwatches(selected = settings.theme) { theme ->
                container.saveSettings { it.copy(theme = theme) }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier
                    .fillMaxWidth()
                    // Tidy the field once typing is over; rewriting it mid-word would jump the cursor.
                    .onFocusChanged { if (!it.isFocused) name = Normalize.cameraUsername(name) },
                singleLine = true,
                label = { Text("Camera name") },
                supportingText = {
                    Text(if (clean.isEmpty()) "Optional. A short handle for your camera." else "Saved as: $clean")
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        }
    }
}

@Composable
private fun ThemeSwatches(selected: AppTheme, onSelect: (AppTheme) -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (theme in AppTheme.entries) {
            val palette = theme.palette
            val isSelected = theme == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(theme) })
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // A transparent ring, not a zero-width one: Compose draws a
                // zero-width border as a hairline.
                Box(
                    Modifier
                        .size(48.dp)
                        .border(3.dp, if (isSelected) palette.accent else Color.Transparent, CircleShape)
                        .padding(5.dp)
                        .clip(CircleShape)
                        .background(Brush.verticalGradient(listOf(palette.top, palette.bottom)))
                        .border(1.dp, Color.Black.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(14.dp).background(palette.accent, CircleShape))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    theme.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

// ---- Capture -------------------------------------------------------------------

@Composable
private fun CaptureCard() {
    val context = LocalContext.current
    val container = context.container
    val settings by container.settings.settings.collectAsStateWithLifecycle()

    // Android 9 and older cannot write to Pictures without this; later versions never ask.
    val legacyStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            container.saveSettings { it.copy(saveToPhoneGallery = false) }
            Toast.makeText(
                context,
                "Saving to your gallery needs storage permission on this phone.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    // The switch starts on, but Android 9 and older can only write to Pictures once
    // allowed. Ask here, or it would read "on" while nothing was ever copied.
    LaunchedEffect(Unit) {
        if (container.settings.value.saveToPhoneGallery && PhoneGallery.needsLegacyPermission(context)) {
            legacyStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    GlassCard {
        Column {
            SwitchRow(
                title = "Volume keys take photos",
                detail = "While the camera is open, a volume key takes the photo. Hold it for magic mode.",
                checked = settings.volumeKeyShutter,
            ) { on -> container.saveSettings { it.copy(volumeKeyShutter = on) } }
            SwitchRow(
                title = "Save finished photos to my phone's gallery",
                detail = "Copies each result into Pictures/${PhoneGallery.ALBUM}.",
                checked = settings.saveToPhoneGallery,
            ) { on ->
                container.saveSettings { it.copy(saveToPhoneGallery = on) }
                if (on && PhoneGallery.needsLegacyPermission(context)) {
                    legacyStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title)
            HelperText(detail)
        }
        // The row handles the tap, so the switch itself takes none.
        Switch(checked = checked, onCheckedChange = null)
    }
}

// ---- Shortcuts and about -------------------------------------------------------

@Composable
private fun ShortcutsCard(onOpenPrompts: () -> Unit, onReplayTutorial: () -> Unit) {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenPrompts, modifier = Modifier.fillMaxWidth()) { Text("Edit prompts") }
            OutlinedButton(onClick = onReplayTutorial, modifier = Modifier.fillMaxWidth()) { Text("Replay the tutorial") }
        }
    }
}

private data class AppInfo(val name: String, val versionName: String, val versionCode: Long)

// getPackageInfo(String, Int) is deprecated on API 33+ but still the one call that works on 26+.
@Suppress("DEPRECATION")
private fun appInfo(context: Context): AppInfo {
    val name = context.applicationInfo.loadLabel(context.packageManager).toString()
    return try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        AppInfo(name, info.versionName ?: "unknown", PackageInfoCompat.getLongVersionCode(info))
    } catch (_: PackageManager.NameNotFoundException) {
        AppInfo(name, "unknown", 0L)
    }
}

@Composable
private fun AboutCard() {
    val context = LocalContext.current
    val info = remember { appInfo(context) }
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(info.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            HelperText("Version ${info.versionName} (build ${info.versionCode})")
            Spacer(Modifier.height(6.dp))
            Text(
                "Latent brings ImageGenCam, the original DIY AI camera, to your phone. The idea, the default " +
                    "prompts and the way the shutter, magic mode and album work all come from it.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// ---- Shared --------------------------------------------------------------------

@Composable
private fun HelperText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    )
}

/**
 * For a text field that writes to a store. Calls [save] once typing pauses,
 * and once more as the screen goes away, so leaving straight after typing
 * does not lose the edit. [save] must itself skip values that are invalid or
 * already stored.
 */
@Composable
private fun SaveWhenTypingPauses(value: String, save: (String) -> Unit) {
    val latestValue by rememberUpdatedState(value)
    val latestSave by rememberUpdatedState(save)
    LaunchedEffect(value) {
        delay(TYPING_PAUSE_MS)
        latestSave(value)
    }
    DisposableEffect(Unit) {
        onDispose { latestSave(latestValue) }
    }
}
