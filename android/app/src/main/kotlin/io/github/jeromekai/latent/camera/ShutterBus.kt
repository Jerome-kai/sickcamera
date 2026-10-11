package io.github.jeromekai.latent.camera

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class ShutterEvent { SHOT, MAGIC }

/**
 * Hardware shutter presses (the volume keys) on their way from the Activity
 * to the camera screen. [active] is true only while the camera screen is up,
 * so the volume keys keep their normal job everywhere else.
 */
object ShutterBus {
    private val _events = MutableSharedFlow<ShutterEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ShutterEvent> = _events.asSharedFlow()

    @Volatile var active: Boolean = false

    fun emit(event: ShutterEvent) = _events.tryEmit(event)
}
