plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.reader.app"
    // compileSdk 36 (Android 16) — required by youtube-player 13.0.0's
    // transitive Compose 1.9.0 dependency graph. Bumped together with
    // AGP 8.9.1 / Kotlin 2.1.20 / Compose BOM 2025.08.00 — see
    // gradle/libs.versions.toml for the full toolchain rationale.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aistudio.reader.vksjtw"
        minSdk = 26
        // targetSdk = 36 stays in sync with compileSdk. No runtime
        // behaviour-flag opt-ins beyond what we already have (no
        // foreground services, no edge-to-edge changes), so the bump
        // is invisible to users on Android 14/15 and avoids "Old
        // targetSdk" warnings on Android 16 devices.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        vectorDrawables { useSupportLibrary = true }
        // PdfBox-Android pulls in Apache PDFBox + BouncyCastle, which by
        // itself blows past the 64K dex method limit. multidex on minSdk
        // 21+ is native, no library needed.
        multiDexEnabled = true
    }

    signingConfigs {
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debugConfig")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    // PdfBox-Android (Apache POI / PDFBox) ships the same META-INF/* files
    // as several of our other deps. AGP 8 fails the build on duplicates
    // unless we explicitly drop them. The patterns below are the standard
    // set the PdfBox docs recommend plus the ones that come up in
    // moshi/okhttp/kotlinx interop.
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)

    // Plain `platform(...)` — the BOM (2025.08.00 = Compose 1.9.0) now
    // matches what every transitive dep wants, so we no longer need
    // `enforcedPlatform` or any `resolutionStrategy.eachDependency`
    // version-pinning hack. Earlier attempts to constrain Compose
    // downward to 1.6.7 with the older toolchain failed because the
    // transitive graph from youtube-player 13.0.0 + activity 1.10 +
    // lifecycle 2.9 genuinely depends on Compose 1.9 surfaces.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.retrofit)
    implementation(libs.converter.moshi)
    implementation(libs.okhttp)
    implementation(libs.logging.interceptor)
    implementation(libs.moshi.kotlin)
    // Generates concrete `*JsonAdapter` classes at compile time for every
    // DTO annotated `@JsonClass(generateAdapter = true)`. Without this
    // processor those annotations are dead code and Moshi falls back to
    // reflection, which broke under Kotlin 2.1.20 (bundled
    // kotlinx-metadata-jvm couldn't read 2.1.x metadata). Adding codegen
    // is what restored the LLM streaming + non-streaming response paths
    // — every request was succeeding HTTP-wise but failing to parse the
    // response JSON, so the UI saw "AI ne abhi response nahi diya".
    ksp(libs.moshi.kotlin.codegen)

    implementation(libs.kotlinx.coroutines.android)

    // PDF text extraction. Pure-Java port of Apache PDFBox; ships its own
    // assets through PDFBoxResourceLoader (initialised once in
    // ReaderApp.onCreate). Extracts plain text from selectable PDFs —
    // image-only / scanned PDFs are detected up-front and rejected by
    // DocumentExtractor with a friendly message.
    implementation(libs.pdfbox.android)

    // YouTube IFrame Player wrapper (Apache 2.0, ~4 MB). Internally uses
    // YouTube's official IFrame API in a managed WebView — never breaks
    // (legal + stable), unlike stream-rip approaches. We disable the
    // YouTube-supplied UI via `IFramePlayerOptions.controls(0)` and
    // render our own fully-custom Compose chrome on top so the player
    // does not look or feel like a generic embed: no YouTube branding,
    // no share / "more videos" overlay, no recommendations on pause.
    // Only used by Discussion mode for YouTube-imported documents; text
    // docs do not load this code path.
    implementation(libs.youtube.player)

    // WorkManager — backs the Generate section. MCQ + PDF generation
    // run as CoroutineWorkers via `setForeground`, so the LLM call
    // continues even if the user backgrounds the app or swipes it
    // from recents. We deliberately never call `Result.retry()`, only
    // `Result.success()` / `Result.failure()`, because re-running a
    // successful work item would re-bill the BYOK LLM and re-running
    // a failed one would re-bill on whatever caused the failure.
    implementation(libs.androidx.work.runtime)
}

// App build script modified.
