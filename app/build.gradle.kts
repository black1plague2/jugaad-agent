import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// --- Read feature flags from gradle.properties --------------------------------
val executorchEnabled = providers.gradleProperty("jugaad.executorch.enabled").getOrElse("false").toBoolean()
val gemmaEnabled = providers.gradleProperty("jugaad.gemma.enabled").getOrElse("false").toBoolean()

android {
    namespace = "com.jugaad.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jugaad.agent"
        minSdk = 29          // Redmi Note 10 Pro (Android 11) is the secondary target.
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Wired into BuildConfig so runtime code can branch without reflection guesswork.
        buildConfigField("boolean", "EXECUTORCH_ENABLED", executorchEnabled.toString())
        buildConfigField("boolean", "GEMMA_ENABLED", gemmaEnabled.toString())

        // Capture / DSP constants — single source of truth, mirrored in Kotlin Constants.kt.
        buildConfigField("int", "SAMPLE_RATE_HZ", "44100")
        buildConfigField("int", "CAPTURE_SECONDS", "3")
        buildConfigField("int", "N_FFT", "2048")
        buildConfigField("int", "HOP", "1024")
        buildConfigField("int", "N_MELS", "128")
        buildConfigField("int", "SPEC_FRAMES", "128")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
            )
        }
        // .pte / .task models must not be compressed or ExecuTorch/MediaPipe mmap fails.
        jniLibs.useLegacyPackaging = false
    }
    androidResources {
        noCompress += setOf("pte", "task", "bin", "tflite")
    }

    lint {
        // Runtime permission gates are enforced in MainActivity before navigation;
        // don't let @RequiresPermission lint fail the hackathon release build.
        abortOnError = false
        checkReleaseBuilds = false
        disable += setOf("MissingPermission")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // CameraX — nameplate photo capture.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // FFT for the log-mel front-end.
    implementation(libs.jtransforms)

    testImplementation(libs.junit)

    // --- Optional runtimes: only linked when the flag is on ------------------
    if (executorchEnabled) {
        implementation(libs.executorch.android)
    }
    if (gemmaEnabled) {
        implementation(libs.mediapipe.genai)
    }
}
