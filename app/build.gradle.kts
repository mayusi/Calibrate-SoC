import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Release keystore loaded from a gitignored keystore.properties at project
// root. Missing file falls back to debug signing with a warning — fine for
// dev, gated against accidental release upload elsewhere.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile")?.isNotBlank() == true

android {
    namespace = "io.github.mayusi.calibratesoc"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.mayusi.calibratesoc"
        minSdk = 29              // Android 10 — earliest still in use on handhelds
        targetSdk = 35
        versionCode = 27
        versionName = "0.1.26-alpha"

        ndk {
            // arm64 only. Every supported handheld is aarch64.
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                // -O3 unlocks CoreMark numbers comparable to reference
                // builds. -DNDEBUG strips asserts in the bundled bench
                // kernels. -fno-builtin keeps libc from short-circuiting
                // CoreMark's memcmp loops (Coremark requires it).
                cppFlags += listOf("-O3", "-DNDEBUG", "-fno-builtin")
                cFlags += listOf("-O3", "-DNDEBUG", "-fno-builtin")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("keystore.properties missing — release build will be debug-signed.")
                signingConfigs.getByName("debug")
            }
        }
        debug {
            // .debug suffix so debug + release coexist on a single device.
            // Debug-only screens gated on BuildConfig.DEBUG so they never
            // appear in release APKs.
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*"
        )
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Serialization / coroutines / filesystem / http
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okio)
    implementation(libs.okhttp)

    // Storage
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Background work
    implementation(libs.androidx.work.runtime.ktx)

    // Shizuku
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // libsu (root)
    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    implementation(libs.libsu.io)

    // Charts
    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okio.fakefilesystem)

    // Instrumented tests
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.mockk.android)
}
