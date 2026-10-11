package io.github.jeromekai.latent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.jeromekai.latent.album.AlbumScreen
import io.github.jeromekai.latent.album.ViewerScreen
import io.github.jeromekai.latent.camera.CameraScreen
import io.github.jeromekai.latent.magic.MagicScreen
import io.github.jeromekai.latent.models.ModelsScreen
import io.github.jeromekai.latent.onboarding.OnboardingScreen
import io.github.jeromekai.latent.prompts.PromptsScreen
import io.github.jeromekai.latent.settings.SettingsScreen

@Composable
fun LatentNav(startOnboarding: Boolean, albumRequests: Int) {
    val nav: NavHostController = rememberNavController()
    // Evaluated once: finishing onboarding flips tutorialSeen, which must not
    // restart the graph underneath the user.
    val start = remember { if (startOnboarding) Routes.ONBOARDING else Routes.CAMERA }

    // A notification tap opens the album (the camera's sparkle, for a closed app).
    LaunchedEffect(albumRequests) {
        if (albumRequests > 0) {
            nav.navigate(Routes.ALBUM) { launchSingleTop = true }
        }
    }

    NavHost(navController = nav, startDestination = start) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onFinished = {
                    // First run: no camera on the stack yet, so go to it. Replayed
                    // from Settings: the camera is already underneath, so just
                    // pop back to it rather than stacking a second one.
                    if (!nav.popBackStack(Routes.CAMERA, inclusive = false)) {
                        nav.navigate(Routes.CAMERA) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                    }
                },
            )
        }
        composable(Routes.CAMERA) {
            CameraScreen(
                onOpenAlbum = { nav.navigate(Routes.ALBUM) { launchSingleTop = true } },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                onOpenPrompts = { nav.navigate(Routes.PROMPTS) { launchSingleTop = true } },
                onOpenModels = { nav.navigate(Routes.MODELS) { launchSingleTop = true } },
            )
        }
        composable(Routes.ALBUM) {
            AlbumScreen(
                onBack = { nav.popBackStack() },
                onOpen = { item -> nav.navigate(Routes.viewer(item.relativePath)) },
                onOpenMagic = { nav.navigate(Routes.MAGIC) { launchSingleTop = true } },
            )
        }
        composable(
            Routes.VIEWER,
            arguments = listOf(navArgument("path") { type = NavType.StringType }),
        ) { entry ->
            ViewerScreen(
                startPath = entry.arguments?.getString("path").orEmpty(),
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.PROMPTS) { PromptsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.MODELS) { ModelsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.MAGIC) {
            MagicScreen(
                onBack = { nav.popBackStack() },
                // Using a recipe starts magic mode, which lives on the camera.
                onUsed = { nav.popBackStack(Routes.CAMERA, inclusive = false) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenModels = { nav.navigate(Routes.MODELS) { launchSingleTop = true } },
                onOpenPrompts = { nav.navigate(Routes.PROMPTS) { launchSingleTop = true } },
                onReplayTutorial = { nav.navigate(Routes.ONBOARDING) },
            )
        }
    }
}
