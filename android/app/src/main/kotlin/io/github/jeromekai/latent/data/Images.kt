package io.github.jeromekai.latent.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import io.github.jeromekai.latent.core.imaging.Geometry
import io.github.jeromekai.latent.core.imaging.PixelSize
import io.github.jeromekai.latent.core.model.OutputFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** The camera's Pillow work, on Android's Bitmap. */
object Images {

    /** Pixel size as the photo is meant to be seen, EXIF rotation applied. */
    fun orientedSize(file: File): PixelSize? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val size = PixelSize(bounds.outWidth, bounds.outHeight)
        return if (rotationOf(file) % 180 != 0) size.turned() else size
    }

    private fun exif(file: File): ExifInterface? = runCatching { ExifInterface(file) }.getOrNull()

    private fun rotationOf(file: File): Int = exif(file)?.rotationDegrees ?: 0

    /** Decode no larger than needed for [target], upright. */
    fun decodeOriented(file: File, target: PixelSize? = null): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            if (target != null) {
                inSampleSize = Geometry.sampleSize(PixelSize(bounds.outWidth, bounds.outHeight), target.orientedLike(PixelSize(bounds.outWidth, bounds.outHeight)))
            }
        }
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: return null
        return applyExif(decoded, exif(file))
    }

    fun decodeOriented(stream: () -> InputStream?, target: PixelSize? = null): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        stream()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            if (target != null) {
                inSampleSize = Geometry.sampleSize(PixelSize(bounds.outWidth, bounds.outHeight), target.orientedLike(PixelSize(bounds.outWidth, bounds.outHeight)))
            }
        }
        val decoded = stream()?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val exif = stream()?.use { runCatching { ExifInterface(it) }.getOrNull() }
        return applyExif(decoded, exif)
    }

    private fun applyExif(bitmap: Bitmap, exif: ExifInterface?): Bitmap {
        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /**
     * _fit_for_generation: crop 4:3 centred -- in the photo's own
     * orientation -- then scale to the configured input size.
     */
    fun fitForGeneration(source: Bitmap, inputLong: Int, inputShort: Int): Bitmap {
        val box = Geometry.generationCrop(source.width, source.height)
        val cropped = Bitmap.createBitmap(source, box.left, box.top, box.width, box.height)
        val target = Geometry.generationInputSize(PixelSize(source.width, source.height), inputLong, inputShort)
        val scaled = Bitmap.createScaledBitmap(cropped, target.width, target.height, true)
        if (cropped !== source && cropped !== scaled) cropped.recycle()
        return scaled
    }

    /** Shrink to fit inside [maxEdge] x [maxEdge], keeping the aspect. */
    fun thumbnail(source: Bitmap, maxLong: Int, maxShort: Int): Bitmap {
        val limit = if (source.width >= source.height) PixelSize(maxLong, maxShort) else PixelSize(maxShort, maxLong)
        val scale = minOf(1.0, limit.width.toDouble() / source.width, limit.height.toDouble() / source.height)
        if (scale >= 1.0) return source
        return Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
    }

    fun encode(bitmap: Bitmap, format: OutputFormat, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val compressFormat = when (format) {
            OutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
            OutputFormat.PNG -> Bitmap.CompressFormat.PNG
            OutputFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
        }
        bitmap.compress(compressFormat, quality.coerceIn(0, 100), out)
        return out.toByteArray()
    }

    fun jpeg(bitmap: Bitmap, quality: Int): ByteArray = encode(bitmap, OutputFormat.JPEG, quality)

    /**
     * _reencode: convert whatever the model returned (usually PNG) to the
     * configured format. If it cannot be decoded, keep the original bytes.
     */
    fun reencode(bytes: ByteArray, format: OutputFormat, quality: Int): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        return try {
            encode(bitmap, format, quality)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Whether a queued job's output is already a finished image. Writes go
     * through a temp file and a rename, so a file at the final path is always
     * whole; this only has to rule out an empty or undecodable one.
     */
    fun isUsableImage(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }
}
