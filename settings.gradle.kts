pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central: avoids the HTTP 429 rate limits repo1.maven.org
        // applies to shared cloud IPs (cloud development environments, CI).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "HindsTV"
include(":app")
include(":ffmpeg-audio")
