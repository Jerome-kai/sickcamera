package io.github.jeromekai.latent.core.imaging

/** Integer rectangle, [left, top) to [right, bottom), like PIL's crop box. */
data class CropBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class PixelSize(val width: Int, val height: Int) {
    val isPortrait: Boolean get() = height > width

    /** "1536x1024" -> PixelSize(1536, 1024); null if malformed. */
    companion object {
        fun parse(value: String): PixelSize? {
            val parts = value.lowercase().split('x')
            if (parts.size != 2) return null
            val width = parts[0].trim().toIntOrNull() ?: return null
            val height = parts[1].trim().toIntOrNull() ?: return null
            if (width <= 0 || height <= 0) return null
            return PixelSize(width, height)
        }
    }

    override fun toString() = "${width}x$height"

    fun turned(): PixelSize = PixelSize(height, width)

    /** The same size in the orientation of [reference]. */
    fun orientedLike(reference: PixelSize): PixelSize =
        if (reference.isPortrait != isPortrait && width != height) turned() else this
}

/**
 * The camera's image fitting, ported from controller.py.
 *
 * One deliberate change: the camera cropped every frame to LANDSCAPE 4:3,
 * because its screen was landscape. A phone is usually held upright, so the
 * crop follows the photo's own orientation -- a portrait shot becomes 3:4
 * portrait rather than having its top and bottom thrown away.
 */
object Geometry {
    /** The camera's display ratio, 320:240. */
    const val ASPECT_LONG = 4
    const val ASPECT_SHORT = 3

    /** _build_crop_box: the largest centred box with the target ratio. */
    fun cropBox(width: Int, height: Int, ratioWidth: Int, ratioHeight: Int): CropBox {
        require(width > 0 && height > 0) { "empty image" }
        val currentRatio = width.toDouble() / height
        val targetRatio = ratioWidth.toDouble() / ratioHeight
        return if (currentRatio > targetRatio) {
            val newWidth = (height * targetRatio).toInt()
            val left = (width - newWidth) / 2
            CropBox(left, 0, left + newWidth, height)
        } else {
            val newHeight = (width / targetRatio).toInt()
            val top = (height - newHeight) / 2
            CropBox(0, top, width, top + newHeight)
        }
    }

    /** The 4:3 crop in the photo's own orientation. */
    fun generationCrop(width: Int, height: Int): CropBox =
        if (height > width) cropBox(width, height, ASPECT_SHORT, ASPECT_LONG)
        else cropBox(width, height, ASPECT_LONG, ASPECT_SHORT)

    /**
     * _fit_for_generation's target: the configured input size (longer edge
     * first, e.g. 1280x960), turned to match the photo.
     */
    fun generationInputSize(photo: PixelSize, longEdge: Int, shortEdge: Int): PixelSize {
        val landscape = PixelSize(maxOf(longEdge, shortEdge), minOf(longEdge, shortEdge))
        return landscape.orientedLike(photo)
    }

    /** IMAGE_GEN_SIZE turned to match the photo, so portrait shots come back portrait. */
    fun outputSize(configured: String, photo: PixelSize): String {
        val size = PixelSize.parse(configured) ?: return configured
        return size.orientedLike(photo).toString()
    }

    /**
     * Downscale factor so a decoded bitmap is at least [target] on each side
     * but no bigger than it needs to be (a power of two, as BitmapFactory's
     * inSampleSize expects). Saves decoding a 50MP photo at full size.
     */
    fun sampleSize(source: PixelSize, target: PixelSize): Int {
        var sample = 1
        while (source.width / (sample * 2) >= target.width && source.height / (sample * 2) >= target.height) {
            sample *= 2
        }
        return sample
    }
}
