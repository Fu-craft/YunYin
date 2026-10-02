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
        // versionCode must increase for Android to accept a build as an update over a previous one;
        // versionName is what the user sees. Bumped together — a version name change with an unchanged
        // code would install as a downgrade and be rejected.
        versionCode = 35
        versionName = "3.10.8"
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

        // Listen-together relay, same arrangement as the API endpoint: injected from local.properties
        // (`together.base.url`) so no server address is committed, and empty by default so a clone
        // simply does not offer the feature rather than pointing at someone else's relay.
        //
        // This is needed because NetEase's own HTTP API cannot read a peer's playback state at all —
        // its room runs over an Agora RTC channel and the HTTP calls are write-only for state
        // (measured: reporting succeeds, reading returns empty). The relay supplies the read path.
        buildConfigField(
            "String",
            "TOGETHER_BASE_URL",
            "\"${localProps.getProperty("together.base.url") ?: ""}\"",
        )

        // Optional shared secret for the relay (`together.token`). Empty means the relay is open,
        // which is correct on a LAN and wrong on a public address — see the relay's RELAY_TOKEN.
        buildConfigField(
            "String",
            "TOGETHER_TOKEN",
            "\"${localProps.getProperty("together.token") ?: ""}\"",
        )

        // Override for the public pub/sub host used when no relay is configured. Blank means the
        // default public instance, so the feature works with nothing deployed; set this to point at a
        // self-hosted instance or a mirror.
        buildConfigField(
            "String",
            "TOGETHER_NTFY_URL",
            "\"${localProps.getProperty("together.ntfy.url") ?: ""}\"",
        )

        // MQTT broker for the default transport. Blank means the public default, so the feature works
        // with nothing deployed and nothing configured; set these to point at your own broker.
        buildConfigField(
            "String",
            "TOGETHER_MQTT_HOST",
            "\"${localProps.getProperty("together.mqtt.host") ?: ""}\"",
        )
        buildConfigField(
            "String",
            "TOGETHER_MQTT_PORT",
            "\"${localProps.getProperty("together.mqtt.port") ?: ""}\"",
        )
        // Any non-blank value turns TLS on (or just use port 8883, which implies it).
        buildConfigField(
            "String",
            "TOGETHER_MQTT_TLS",
            "\"${localProps.getProperty("together.mqtt.tls") ?: ""}\"",
        )

        // Where the update check looks. `owner/repo`, injected rather than hardcoded so each build points
        // at the repository that actually publishes *its* updates: a fork that hardcoded the upstream
        // coordinates would offer users builds signed by someone else. Blank disables the feature, and the
        // Settings row then explains that rather than failing a check.
        buildConfigField(
            "String",
            "UPDATE_REPO_OWNER",
            "\"${localProps.getProperty("update.repo.owner") ?: ""}\"",
        )
        buildConfigField(
            "String",
            "UPDATE_REPO_NAME",
            "\"${localProps.getProperty("update.repo.name") ?: ""}\"",
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
    // under `com.yunyin.music.lyrics` so its focus and auto-scroll behaviour could be adapted;
    // it was previously the `lyrics-ui` artifact.
    implementation(libs.lyrics.core)
    // iOS-style continuous (squircle) corners.
    implementation(libs.gaze.capsule)

    // 词幕 (Lyricon) provider bridge: pushes song, lyrics and playback state to Lyricon so it can
    // render a status-bar lyric. Optional at runtime — the app works unchanged when Lyricon is not
    // installed, and the bridge reports "unavailable" rather than failing.
    implementation(libs.lyricon.provider)

    // Liquid Glass (Backdrop): samples and refracts what is actually behind a surface, so the
    // floating tab bar and mini player are real glass rather than a translucent fill.
    implementation(libs.backdrop)

    testImplementation("junit:junit:4.13.2")
    // A real `org.json` for unit tests. The main source set gets the Android SDK's copy, but the unit-test
    // classpath's is a stub whose methods throw "not mocked", so any test touching JSONObject would fail
    // for a reason that has nothing to do with the code under test. Test-only: it does not enter the APK.
    testImplementation("org.json:json:20240303")
}
