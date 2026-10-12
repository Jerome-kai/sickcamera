package io.github.jeromekai.latent.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.jeromekai.latent.MainActivity
import io.github.jeromekai.latent.R

/**
 * The camera showed a sparkle on its album tab when a result landed. A phone
 * app is often closed by then, so it also says so in a notification.
 */
object Notifications {
    private const val CHANNEL_RESULTS = "results"
    const val EXTRA_OPEN_ALBUM = "open_album"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULTS, "Finished photos", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When an AI photo is ready, or could not be made"
            },
        )
    }

    private fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * Android 13+ shows no notification until the user has been asked, and an
     * app targeting 33+ is never asked automatically. A screen that wants the
     * "photo ready" alerts has to launch the POST_NOTIFICATIONS request itself.
     */
    fun needsPermission(context: Context): Boolean = !allowed(context)

    private fun openAlbum(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_ALBUM, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** [detail] is a caveat worth a second line, e.g. references that could not be sent. */
    fun ready(context: Context, jobId: String, title: String, detail: String? = null) =
        post(context, jobId, "$title is ready", listOfNotNull("Tap to open the album.", detail).joinToString(" "))

    fun failed(context: Context, jobId: String, title: String, reason: String) =
        post(context, jobId, "Couldn't make $title", reason.take(200))

    private fun post(context: Context, jobId: String, title: String, text: String) {
        if (!allowed(context)) return
        try {
            val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openAlbum(context))
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(jobId.hashCode(), notification)
        } catch (_: RuntimeException) {
            // Permission revoked between the check and the post, or the system
            // refused it. A notification is a courtesy: it must never fail the
            // job that triggered it, nor take the worker down with it.
        }
    }
}
