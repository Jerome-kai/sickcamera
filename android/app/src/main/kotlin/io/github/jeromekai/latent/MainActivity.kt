package io.github.jeromekai.latent

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeromekai.latent.camera.ShutterBus
import io.github.jeromekai.latent.camera.ShutterEvent
import io.github.jeromekai.latent.ui.LatentNav
import io.github.jeromekai.latent.ui.LatentTheme
import io.github.jeromekai.latent.work.Notifications

class MainActivity : ComponentActivity() {
    /** Bumped when a notification asks to open the album. */
    private val albumRequests = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent?.getBooleanExtra(Notifications.EXTRA_OPEN_ALBUM, false) == true) albumRequests.value++
        setContent {
            val container = remember { applicationContext.container }
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            LatentTheme(settings.theme) {
                LatentNav(
                    startOnboarding = !settings.tutorialSeen,
                    albumRequests = albumRequests.value,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(Notifications.EXTRA_OPEN_ALBUM, false)) albumRequests.value++
    }

    // The volume keys are the phone's shutter button: tap to shoot, hold to
    // seed magic mode -- the same tap/hold split as the camera's shutter. They
    // are claimed only while the camera screen is up (ShutterBus.active).
    private fun isVolume(code: Int) = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isVolume(keyCode) && ShutterBus.active) {
            event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (isVolume(keyCode) && ShutterBus.active) {
            ShutterBus.emit(ShutterEvent.MAGIC)
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isVolume(keyCode) && ShutterBus.active) {
            if (event.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS == 0) ShutterBus.emit(ShutterEvent.SHOT)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
