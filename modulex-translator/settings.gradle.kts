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
    }
}

rootProject.name = "modulex-translator"

// Platform-independent logic, JVM-tested without the Android SDK.
include(":core")
// Kotlin side of the native engines (JNI bindings), shared by the app and host tests.
include(":engines")
// WebRTC signaling relay for Internet mode.
include(":signaling")

// The Android application needs the Android SDK (and NDK for the native engines).
// It is included whenever an SDK location is configured, so the JVM modules can
// still be built and tested on machines without Android tooling.
val localProps = java.util.Properties().apply {
    val f = file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val sdkConfigured = localProps.getProperty("sdk.dir") != null ||
    System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null
if (sdkConfigured || providers.gradleProperty("modulex.forceApp").isPresent) {
    include(":app")
} else {
    logger.lifecycle("ModuleX: Android SDK not configured; building :core and :signaling only.")
}
