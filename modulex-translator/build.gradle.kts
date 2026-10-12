// The Android Gradle Plugin is only put on the build classpath when an Android SDK is configured
// (the same condition settings.gradle.kts uses to include :app). This keeps the JVM modules
// (:core, :engines, :signaling) buildable and testable on machines without Android tooling.
// Keep the version in sync with `agp` in gradle/libs.versions.toml.
buildscript {
    val localProps = java.util.Properties().apply {
        val f = file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val sdkConfigured = localProps.getProperty("sdk.dir") != null ||
        System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null
    if (sdkConfigured) {
        repositories { google(); mavenCentral() }
        dependencies { classpath("com.android.tools.build:gradle:8.13.0") }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
