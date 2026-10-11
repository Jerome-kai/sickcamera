import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// CI passes a monotonically increasing build number so every published APK
// installs over the previous one. Local builds are version 1.
val buildNumber = (System.getenv("LATENT_BUILD_NUMBER") ?: "1").toInt()

// Release signing. Android only installs an update over an app signed with the
// SAME key, so a key that changes between builds means uninstalling -- and losing
// the app's data -- on every update. Hence two options, never a random one:
//   1. a private key from the environment (GitHub secrets in CI), or
//   2. signing/public-fallback.keystore, committed on purpose and NOT a secret.
// Option 2 keeps updates working out of the box but is not a trust anchor: anyone
// can sign with it. android/README.md explains moving to a private key.
val keystorePath: String? = System.getenv("LATENT_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val fallbackKeystore = rootProject.file("signing/public-fallback.keystore")

android {
    namespace = "io.github.jeromekai.latent"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.jeromekai.latent"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("LATENT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LATENT_KEY_ALIAS")
                keyPassword = System.getenv("LATENT_KEY_PASSWORD")
            } else {
                storeFile = fallbackKeystore
                storePassword = "latent-public"
                keyAlias = "latent-public"
                keyPassword = "latent-public"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("io.github.jeromekai.latent:core:1.0")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.exifinterface)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit4)
}
