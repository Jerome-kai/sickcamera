package io.github.jeromekai.latent.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeromekai.latent.container
import io.github.jeromekai.latent.core.model.ModelPreset
import io.github.jeromekai.latent.ui.Amber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.pow

/** The camera's tap-or-hold threshold: held longer than 0.6s seeds magic mode. */
private const val LONG_PRESS_MS = 600L

/** _queue_shutter_event's 0.125s debounce. */
private const val SHUTTER_DEBOUNCE_MS = 125L

/** CAPTURE_FEEDBACK_DURATION_SECONDS default. */
private const val FEEDBACK_MS = 250

private suspend fun cameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    continuation.resume(future.get())
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

@Composable
fun CameraScreen(
    onOpenAlbum: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPrompts: () -> Unit,
    onOpenModels: () -> Unit,
) {
    val context = LocalContext.current
    val container = context.container
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) askCamera.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraContent(container, onOpenAlbum, onOpenSettings, onOpenPrompts, onOpenModels)
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Latent needs the camera to take photos.", color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { askCamera.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            }
        }
    }
}

@Composable
private fun CameraContent(
    container: io.github.jeromekai.latent.AppContainer,
    onOpenAlbum: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPrompts: () -> Unit,
    onOpenModels: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val session = container.session

    val sessionState by session.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val prompts by container.prompts.prompts.collectAsStateWithLifecycle()
    val models by container.models.presets.collectAsStateWithLifecycle()
    val queue by container.queue.collectAsStateWithLifecycle()
    val unseen by container.unseen.collectAsStateWithLifecycle()
    val hasKey by container.secureKey.hasKey.collectAsStateWithLifecycle()

    var lensBack by remember { mutableStateOf(true) }
    var flashMode by remember { mutableStateOf(ImageCapture.FLASH_MODE_OFF) }
    var showPicker by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // TextureView-backed, so the frozen capture frame can be grabbed.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    LaunchedEffect(flashMode) { imageCapture.flashMode = flashMode }

    LaunchedEffect(lensBack) {
        val provider = cameraProvider(context)
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val selector = if (lensBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
        } catch (error: Exception) {
            session.say("Camera unavailable: ${error.message}", isError = true)
        }
    }

    // ---- capture feedback: frozen frame + fading white flash -----------------
    var frozen by remember { mutableStateOf<ImageBitmap?>(null) }
    val feedback = remember { Animatable(1f) }
    var lastShutterAt by remember { mutableStateOf(0L) }

    fun capture(magic: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (now - lastShutterAt < SHUTTER_DEBOUNCE_MS) return
        lastShutterAt = now
        if (!magic) session.refusal()?.let { session.say(it, isError = true); return }
        if (magic && !hasKey) { session.say("Add your API key in Settings first.", isError = true); return }
        if (magic && !settings.magicEnabled) return

        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val snapshot: Bitmap? = previewView.bitmap
        frozen = snapshot?.asImageBitmap()
        scope.launch {
            feedback.snapTo(0f)
            feedback.animateTo(1f, tween(FEEDBACK_MS, easing = LinearEasing))
            frozen = null
        }

        val file = File(context.cacheDir, "shot-${System.nanoTime()}.jpg")
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (magic) session.seedMagic(file) else session.shoot(file)
                }

                override fun onError(exception: ImageCaptureException) {
                    file.delete()
                    session.say("Capture failed: ${exception.message}", isError = true)
                }
            },
        )
    }

    // Volume keys, while this screen is up and the setting allows it.
    DisposableEffect(settings.volumeKeyShutter) {
        ShutterBus.active = settings.volumeKeyShutter
        onDispose { ShutterBus.active = false }
    }
    LaunchedEffect(Unit) {
        ShutterBus.events.collect { event -> capture(magic = event == ShutterEvent.MAGIC) }
    }

    // Transient status line, like the camera's status_message.
    var visibleStatus by remember { mutableStateOf<Status?>(null) }
    LaunchedEffect(sessionState.status) {
        val status = sessionState.status ?: return@LaunchedEffect
        visibleStatus = status
        delay(if (status.isError) 5000 else 2500)
        if (visibleStatus == status) visibleStatus = null
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Frozen frame cross-fading back to live, with the camera's flash
        // curve: white at alpha 220 * (1 - progress)^1.8.
        frozen?.let { bitmap ->
            val progress = feedback.value
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(1f - progress),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Color.White.copy(alpha = (220f / 255f) * (1f - progress).toDouble().pow(1.8).toFloat()),
                ),
            )
        }

        // ---- top bar -----------------------------------------------------------
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Settings", tint = Color.White) }
                Spacer(Modifier.width(8.dp))
                RoundIcon(onClick = {
                    flashMode = when (flashMode) {
                        ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                        ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                        else -> ImageCapture.FLASH_MODE_OFF
                    }
                }) {
                    val icon = when (flashMode) {
                        ImageCapture.FLASH_MODE_AUTO -> Icons.Filled.FlashAuto
                        ImageCapture.FLASH_MODE_ON -> Icons.Filled.FlashOn
                        else -> Icons.Filled.FlashOff
                    }
                    Icon(icon, "Flash", tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                QueueBadge(pending = queue.pending, failed = queue.failed, onClick = { showQueue = true })
            }
            Spacer(Modifier.height(10.dp))
            TitlePill(
                text = if (sessionState.magicActive) session.magicPillText(sessionState) else container.selectedPrompt().title,
                magic = sessionState.magicActive,
                onClick = { if (sessionState.magicActive) session.exitMagic() else showPicker = true },
            )
            if (!hasKey) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xCC7A1F1F))
                        .clickable(onClick = onOpenSettings)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text("No API key yet — tap to add one", color = Color.White, fontSize = 13.sp)
                }
            }
        }

        // ---- status line -------------------------------------------------------
        AnimatedVisibility(
            visible = visibleStatus != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            val status = visibleStatus
            if (status != null) {
                Text(
                    status.text,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(horizontal = 32.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (status.isError) Color(0xCC7A1F1F) else Color(0x99000000))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        // ---- bottom controls ---------------------------------------------------
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (models.size >= 2) {
                ModelDial(
                    models = models,
                    selectedId = container.selectedModel().id,
                    onSelect = { preset ->
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        container.settings.update { it.copy(selectedModelId = preset.id) }
                    },
                )
            } else {
                Text(
                    container.selectedModel().label,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    modifier = Modifier.clickable(onClick = onOpenModels).padding(8.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 36.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    RoundIcon(size = 56, onClick = onOpenAlbum) { Icon(Icons.Filled.PhotoLibrary, "Album", tint = Color.White) }
                    if (unseen > 0) {
                        // The camera's pale-yellow sparkle on the album tab.
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = "$unseen new",
                            tint = Color(0xFFFFF3A0),
                            modifier = Modifier.size(20.dp).align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp),
                        )
                    }
                }
                ShutterButton(
                    magic = sessionState.magicActive,
                    onTap = { capture(magic = false) },
                    onHold = { capture(magic = true) },
                )
                RoundIcon(size = 56, onClick = { lensBack = !lensBack }) {
                    Icon(Icons.Filled.FlipCameraAndroid, "Switch camera", tint = Color.White)
                }
            }
        }
    }

    if (showPicker) {
        PromptPickerSheet(
            prompts = prompts,
            selectedId = container.selectedPrompt().id,
            onSelect = { prompt ->
                container.settings.update { it.copy(selectedPromptId = prompt.id) }
                session.say("Ready with ${prompt.title}")
                showPicker = false
            },
            onEdit = { showPicker = false; onOpenPrompts() },
            onDismiss = { showPicker = false },
            hasReference = { container.referenceFile(it) != null },
        )
    }
    if (showQueue) {
        QueueSheet(container = container, onDismiss = { showQueue = false })
    }
}

@Composable
private fun RoundIcon(size: Int = 44, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Color(0x66000000)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The frosted pill across the top of the viewfinder. */
@Composable
private fun TitlePill(text: String, magic: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(if (magic) Amber.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.88f))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (magic) Icon(Icons.Filled.AutoAwesome, null, tint = Color(0xFF3A2A00), modifier = Modifier.size(18.dp))
        Text(
            text,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF121212),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (magic) Icon(Icons.Filled.Close, "Leave magic mode", tint = Color(0xFF3A2A00), modifier = Modifier.size(18.dp))
    }
}

/** The camera's dark pill with an amber count of photos still in the queue. */
@Composable
private fun QueueBadge(pending: Int, failed: Int, onClick: () -> Unit) {
    if (pending == 0 && failed == 0) return
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xCC111111)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pending > 0) Text("$pending", color = Amber, fontWeight = FontWeight.Bold)
        if (pending > 0 && failed > 0) Spacer(Modifier.width(8.dp))
        if (failed > 0) Text("$failed failed", color = Color(0xFFFF8A80), fontWeight = FontWeight.Bold)
    }
}

/**
 * Tap to shoot; hold past 0.6s to seed magic mode. Like the camera, the hold
 * fires while the finger is still down, and the release is then ignored.
 */
@Composable
private fun ShutterButton(magic: Boolean, onTap: () -> Unit, onHold: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    // pointerInput(Unit) outlives recompositions; read the latest callbacks.
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnHold by rememberUpdatedState(onHold)
    Box(
        Modifier
            .size(84.dp)
            .clip(CircleShape)
            .border(5.dp, if (magic) Amber else Color.White, CircleShape)
            .padding(9.dp)
            .clip(CircleShape)
            .background(if (pressed) Color.White.copy(alpha = 0.6f) else Color.White)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    var held = false
                    val timer = scope.launch {
                        delay(LONG_PRESS_MS)
                        held = true
                        currentOnHold()
                    }
                    val up = waitForUpOrCancellation()
                    timer.cancel()
                    pressed = false
                    if (!held && up != null) currentOnTap()
                }
            },
    )
}

/**
 * The model wheel: a camera-style mode dial along the bottom. Choosing a
 * model applies to the next photo; ones already queued keep theirs.
 */
@Composable
private fun ModelDial(models: List<ModelPreset>, selectedId: String, onSelect: (ModelPreset) -> Unit) {
    val listState = rememberLazyListState()
    val selectedIndex = models.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    LaunchedEffect(selectedIndex) { listState.animateScrollToItem(selectedIndex) }
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 120.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(models, key = { _, preset -> preset.id }) { index, preset ->
            val selected = index == selectedIndex
            Text(
                preset.label,
                color = if (selected) Amber else Color.White.copy(alpha = 0.75f),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                fontSize = if (selected) 15.sp else 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) Color(0x99000000) else Color.Transparent)
                    .clickable { onSelect(preset) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
