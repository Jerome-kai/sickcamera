package io.github.jeromekai.latent.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeromekai.latent.container
import io.github.jeromekai.latent.ui.GlassCard
import io.github.jeromekai.latent.ui.ThemedBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class TutorialPage(val icon: ImageVector, val title: String, val body: List<String>)

/**
 * The camera's five tutorial pages. The first four keep their titles, and the
 * Welcome body is verbatim (including the camera's "--"); the others describe
 * the phone's controls where the camera's described its physical buttons. The
 * last page was the web app and its QR code, which a phone has no use for, so
 * it asks for the API key instead.
 */
private val PAGES = listOf(
    TutorialPage(
        Icons.Filled.AutoAwesome,
        "Welcome",
        listOf("This camera takes a photo and has an AI redraw it. A few buttons is all there is -- here is what they do."),
    ),
    TutorialPage(
        Icons.Filled.PhotoCamera,
        "Shutter",
        listOf(
            "The shutter takes a photo, sends it to the AI with the current prompt, and drops the result in the album. " +
                "Tap the big button at the bottom, or press a volume key.",
            "Hold it to seed Magic Mode from whatever the camera sees. Tap the pill at the top to leave Magic Mode.",
        ),
    ),
    TutorialPage(
        Icons.Filled.Edit,
        "Prompts",
        listOf(
            "The pill at the top of the viewfinder opens the prompt picker. Prompts are the instructions the AI follows -- " +
                "pick one before you shoot, and edit them in Settings.",
            "Along the bottom, the model dial picks which AI model does the drawing.",
        ),
    ),
    TutorialPage(
        Icons.Filled.PhotoLibrary,
        "Album",
        listOf(
            "The album button, bottom left, shows every generated photo. " +
                "Open one and tap Original to compare it with the photo you took.",
            "Press and hold a photo to select it, then share it or save it to your phone. " +
                "Everything else lives behind the gear at the top left: Settings.",
        ),
    ),
    TutorialPage(
        Icons.Filled.VpnKey,
        "Your API key",
        listOf(
            "Latent redraws your photos with your own API key. It is stored encrypted on this phone " +
                "and is only ever sent to the AI service when a photo is generated.",
        ),
    ),
)

private data class KeyMessage(val text: String, val isError: Boolean)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val hasKey by container.secureKey.hasKey.collectAsStateWithLifecycle()

    val pager = rememberPagerState(pageCount = { PAGES.size })
    val page = pager.currentPage
    val last = page == PAGES.lastIndex

    // Plain remember, not rememberSaveable: the key must not ride along in
    // saved instance state. Turning the phone mid-typing costs a re-paste.
    var draft by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<KeyMessage?>(null) }
    var saving by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }

    // Leaving the key page by swipe must not leave the keyboard up.
    LaunchedEffect(page) { focusManager.clearFocus() }

    fun goTo(target: Int) {
        scope.launch { pager.animateScrollToPage(target.coerceIn(0, PAGES.lastIndex)) }
    }

    fun finish() {
        // Guarded so a double tap cannot navigate twice (the second pop would
        // stack a second camera).
        if (finishing) return
        finishing = true
        scope.launch {
            // Worst case the tutorial shows again next launch; never trap the
            // user on it over a failed write. A replay from Settings finds the
            // flag already set and, as on the camera, writes nothing.
            if (!container.settings.value.tutorialSeen) {
                withContext(Dispatchers.IO) { runCatching { container.settings.update { it.copy(tutorialSeen = true) } } }
            }
            // The camera's status on returning to the viewfinder.
            container.session.say("Ready with ${container.selectedPrompt().title}")
            onFinished()
        }
    }

    fun saveDraft(afterSaved: () -> Unit = {}) {
        val key = draft.trim()
        if (key.isEmpty() || saving) return
        focusManager.clearFocus()
        saving = true
        scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { container.secureKey.set(key) }.isSuccess }
            saving = false
            if (saved) {
                // Stored encrypted now; do not keep the plain text in UI state.
                draft = ""
                message = KeyMessage("Saved. Your key is encrypted on this phone.", isError = false)
                afterSaved()
            } else {
                message = KeyMessage(
                    "Could not save the key on this phone. Try again, or add it later in Settings.",
                    isError = true,
                )
            }
        }
    }

    fun next() {
        // Read the pager now: this runs from click handlers, which must not
        // act on the page number captured when the screen last composed.
        val current = pager.currentPage
        when {
            current < PAGES.lastIndex -> goTo(current + 1)
            // A key typed but not yet saved is saved rather than silently dropped.
            draft.isNotBlank() -> saveDraft(afterSaved = { finish() })
            else -> finish()
        }
    }

    // The back gesture steps back through the pages; on the first page it
    // falls through to the system (leave the app, or return to Settings).
    BackHandler(enabled = page > 0) { goTo(page - 1) }

    ThemedBackground(settings.theme) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${page + 1}/${PAGES.size}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                // The key page has its own "Skip for now".
                if (!last) {
                    TextButton(enabled = !finishing, onClick = { finish() }) { Text("Skip") }
                }
            }

            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { index ->
                val entry = PAGES[index]
                if (index == PAGES.lastIndex) {
                    TutorialCard(entry) {
                        ApiKeyForm(
                            draft = draft,
                            onDraftChange = {
                                draft = it
                                message = null
                            },
                            hasKey = hasKey,
                            saving = saving,
                            message = message,
                            onSave = { saveDraft() },
                            onSkip = { finish() },
                            skipEnabled = !finishing && !saving,
                        )
                    }
                } else {
                    TutorialCard(entry)
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (page > 0) {
                        TextButton(enabled = !finishing, onClick = { goTo(page - 1) }) { Text("Back") }
                    }
                }
                PageDots(current = page, count = PAGES.size)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    // "Go shoot" is the camera's own sign-off on its last page.
                    Button(enabled = !finishing && !saving, onClick = { next() }) {
                        Text(if (last) "Go shoot" else "Next")
                    }
                }
            }
        }
    }
}

/** One page: an icon, the title and the body, centred and scrollable on short screens. */
@Composable
private fun TutorialCard(page: TutorialPage, extra: @Composable ColumnScope.() -> Unit = {}) {
    Box(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            GlassCard {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            page.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        page.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    page.body.forEach { paragraph ->
                        Spacer(Modifier.height(12.dp))
                        Text(
                            paragraph,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                    }
                    extra()
                }
            }
        }
    }
}

/** Password field and the two ways out of the key page. The key goes to SecureKeyStore and nowhere else. */
@Composable
private fun ApiKeyForm(
    draft: String,
    onDraftChange: (String) -> Unit,
    hasKey: Boolean,
    saving: Boolean,
    message: KeyMessage?,
    onSave: () -> Unit,
    onSkip: () -> Unit,
    skipEnabled: Boolean,
) {
    Spacer(Modifier.height(20.dp))
    OutlinedTextField(
        value = draft,
        onValueChange = onDraftChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("API key") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSave() }),
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(enabled = draft.isNotBlank() && !saving, onClick = onSave) { Text("Save key") }
        TextButton(enabled = skipEnabled, onClick = onSkip) { Text("Skip for now") }
    }
    Spacer(Modifier.height(8.dp))
    val text = when {
        message != null -> message.text
        hasKey -> "A key is already saved on this phone. Paste a new one only to replace it."
        else -> "No key yet. Skipping is fine — the camera screen shows a reminder until you add one in Settings."
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = when {
            message?.isError == true -> MaterialTheme.colorScheme.error
            message != null -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        },
    )
}

/** The current page is a wide pill, the rest small dots. */
@Composable
private fun PageDots(current: Int, count: Int) {
    Row(
        Modifier.semantics { contentDescription = "Page ${current + 1} of $count" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val selected = index == current
            val width by animateDpAsState(if (selected) 22.dp else 8.dp, label = "dotWidth")
            Box(
                Modifier
                    .height(8.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                    ),
            )
        }
    }
}
