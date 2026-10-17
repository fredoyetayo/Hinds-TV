// Media3 FFmpeg audio decoder extension (androidx.media3.decoder.ffmpeg), audio only,
// with a prebuilt LGPL FFmpeg libffmpegJNI.so for armeabi-v7a.
// See README.md; rebuild the .so with scripts/build-ffmpeg.sh.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.hindstv.ffmpeg"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The prebuilt .so is already stripped (scripts/build-ffmpeg.sh); don't try again.
    packaging { jniLibs { keepDebugSymbols += "**/libffmpegJNI.so" } }
}

dependencies {
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.decoder)
    // The vendored Media3 sources use these annotations, which Media3 does not expose on
    // consumers' compile classpath. Compile time only.
    compileOnly(libs.androidx.annotation)
    compileOnly(libs.checkerframework.qual)
}
