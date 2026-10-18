plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// CI passes VERSION_CODE / VERSION_NAME so every published build can update the previous one.
val ciVersionCode = providers.environmentVariable("VERSION_CODE").orNull?.toIntOrNull() ?: 1
val ciVersionName = providers.environmentVariable("VERSION_NAME").orNull ?: "0.3.0-dev"

android {
    namespace = "com.hindstv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hindstv.app"
        minSdk = 28 // Fire OS 7+; the target stick runs Fire OS 8 (API 30)
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = ciVersionName
        // Fire TV Stick 4K / 4K Max run a 32-bit userspace. Override for an emulator with
        // -Phinds.abis=x86 (the FFmpeg audio decoder is ARM-only, so it is left out there).
        ndk { abiFilters += providers.gradleProperty("hinds.abis").orElse("armeabi-v7a").get().split(",") }
    }

    signingConfigs {
        // One permanent key for every build (local, cloud and CI) so updates install over each
        // other. Override with HINDS_KEYSTORE_* environment variables (e.g. GitHub secrets).
        create("hinds") {
            storeFile = file(System.getenv("HINDS_KEYSTORE_FILE") ?: "$rootDir/keystore/hinds-dev.jks")
            storePassword = System.getenv("HINDS_KEYSTORE_PASSWORD") ?: "hindstv-dev"
            keyAlias = System.getenv("HINDS_KEY_ALIAS") ?: "hinds"
            keyPassword = System.getenv("HINDS_KEY_PASSWORD") ?: "hindstv-dev"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("hinds")
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("hinds")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/previous-compilation-data.bin")
        // Prebuilt and already stripped (ffmpeg-audio module); no NDK needed to build the app.
        jniLibs.keepDebugSymbols += "**/libffmpegJNI.so"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.profileinstaller)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.tv.material)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui.compose)
    implementation(project(":ffmpeg-audio")) // MP2/AC3/E-AC3/DTS software decoding when the device lacks it

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kxml2) // XmlPullParser implementation for JVM tests (Android provides its own)
}
