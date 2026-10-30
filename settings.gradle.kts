pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Pleos Playground public Maven (Vehicle SDK, NaviHelper SDK). No token required.
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/")
    }
}

rootProject.name = "drive-symphony"

// :engine is a pure Kotlin/JVM module (DSP, music theory, driving scorer). It builds anywhere with JDK 17.
include(":engine")

// :app is the Android Automotive (Pleos Connect) module. It needs the Android SDK.
// When no SDK is configured we skip it so that `gradlew :engine:test` and preview rendering still work.
val localProps = File(rootDir, "local.properties")
val sdkConfigured = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    (localProps.exists() && localProps.readText().lines().any { it.trim().startsWith("sdk.dir") })
if (sdkConfigured) {
    include(":app")
} else {
    logger.lifecycle("[drive-symphony] Android SDK not found -> ':app' skipped (engine-only build). Install Android Studio or set ANDROID_HOME / local.properties sdk.dir.")
}
