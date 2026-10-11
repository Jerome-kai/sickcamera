package io.github.jeromekai.latent

import android.app.Application
import android.content.Context
import io.github.jeromekai.latent.work.Notifications

class LatentApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        container.refreshQueue()
        // Work survives reboots on its own; this catches job files left
        // without scheduled work (an update, or restored app data).
        container.scheduler.resumePending()
    }
}

val Context.container: AppContainer
    get() = (applicationContext as LatentApp).container
