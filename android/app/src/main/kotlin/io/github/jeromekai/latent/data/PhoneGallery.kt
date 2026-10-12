package io.github.jeromekai.latent.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Getting images out of the app: the phone's own gallery, and the share
 * sheet. These replace the camera's LAN downloads and QR codes -- on a phone
 * the picture is already in your hand.
 */
object PhoneGallery {
    const val ALBUM = "Latent"

    fun mimeTypeOf(file: File): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    /** Android 9 and older need storage permission to write to Pictures. */
    fun needsLegacyPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    /**
     * Copy into Pictures/Latent so it shows in the phone's gallery app. Null
     * means it was not saved, for any reason: callers show "could not save"
     * rather than dying on a MediaStore that refuses (storage full, volume
     * gone, a gallery provider that throws).
     */
    fun saveToPictures(context: Context, file: File): Uri? {
        if (needsLegacyPermission(context)) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, mimeTypeOf(file))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        // Results are copied in as they finish, so the Save button would
        // otherwise stack a "name (1).jpg" beside every one of them.
        existingCopy(context, collection, file.name)?.let { return it }
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: error("no output stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            uri
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /**
     * The copy already in Pictures/Latent, if this app put one there. From
     * Android 10 an app can always see its own entries without any storage
     * permission. Before that, no RELATIVE_PATH exists to match on.
     */
    private fun existingCopy(context: Context, collection: Uri, name: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                arrayOf(name, "${Environment.DIRECTORY_PICTURES}/$ALBUM%"),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
            }
        }.getOrNull()
    }

    private fun contentUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun shareIntent(context: Context, files: List<File>): Intent? {
        if (files.isEmpty()) return null
        val uris = ArrayList(files.map { contentUri(context, it) })
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeTypeOf(files.single())
                putExtra(Intent.EXTRA_STREAM, uris.single())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null)
    }
}
