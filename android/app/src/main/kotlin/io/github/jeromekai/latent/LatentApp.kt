package io.github.jeromekai.latent

import android.app.Application
import android.content.Context
import io.github.jeromekai.latent.work.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LatentApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        // Both read every job file, so they stay off the main thread and the
        // first frame. Work survives reboots on its own; resumePending catches
        // job files left without scheduled work (an update, or restored app
        // data). The queue badge fills in as soon as the count is known.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            container.refreshQueue()
            container.scheduler.resumePending()
        }
    }
}

val Context.container: AppContainer
    get() = (applicationContext as LatentApp).container
