import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Local, git-ignored configuration.
 *
 * Signing material and the API endpoint must not be committed, so both are read from files that
 * `.gitignore` excludes. See README ("构建") for the keys each file takes.
 *
 *   local.properties     -- `api.base.url` (the NeteaseCloudMusicApi endpoint to build against)
 *   keystore.properties  -- `storeFile`, `storePassword`, `keyAlias`, `keyPassword`
 */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.yunyin.music"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.yunyin.music"
        minSdk = 33
        targetSdk = 37
        versionCode = 6
        versionName = "3.1.0"
        vectorDrawables { useSupportLibrary = true }

        // The API endpoint is injected at build time rather than baked into the sources, so the
        // published repository carries no server address. Empty by default: a clone builds and
        // runs, and the app reports that no endpoint is configured instead of silently pointing at
        // someone else's server.
        buildConfigField(
            "String",
            "API_BASE_URL",
            "\"${localProps.getProperty("api.base.url") ?: ""}\"",
        )
    }

    signingConfigs {
        // Only created when local signing material is present. Without it a release build is left
        // unsigned rather than falling back to a committed key: shipping a keystore in a public
        // repository would let anyone publish updates that Android accepts as this app's.
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Left unsigned when no local keystore is configured, so a fresh clone can still run
            // `assembleRelease` and produce an (unsigned) APK instead of failing the build.
            if (keystoreProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Kept off so the shipped APK is a faithful build of the sources.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += arrayOf(
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "kotlin/**",
                "META-INF/*.version",
                "META-INF/**/LICENSE.txt",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1"
            )
        }
        dex { useLegacyPackaging = true }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // Bridges MediaController's ListenableFuture to coroutines.
    implementation(libs.kotlinx.coroutines.guava)

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    implementation(libs.okhttp)

    // Palette extraction for the audio-reactive background colours.
    implementation(libs.palette)

    // Word-by-word lyrics: lyrics-core parses TTML/YRC/LRC. The lyrics *view* is vendored
    // under `com.amll.player.lyrics` (see tools/vendor_lyrics_ui.ps1) so its focus and
    // auto-scroll behaviour could be fixed; it was previously the `lyrics-ui` artifact.
    implementation(libs.lyrics.core)
    // iOS-style continuous (squircle) corners.
    implementation(libs.gaze.capsule)

    testImplementation("junit:junit:4.13.2")
}
