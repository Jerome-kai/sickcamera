package io.github.jeromekai.latent.ui

import android.net.Uri

/** Navigation destinations. Screens receive callbacks, never a NavController. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val CAMERA = "camera"
    const val ALBUM = "album"
    const val VIEWER = "viewer/{path}"
    const val PROMPTS = "prompts"
    const val MODELS = "models"
    const val MAGIC = "magic"
    const val SETTINGS = "settings"

    /** [path] is a gallery-relative path such as "2026-10-11/<id>-generated.jpg". */
    fun viewer(path: String) = "viewer/${Uri.encode(path)}"
}
