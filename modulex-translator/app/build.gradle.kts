import java.util.Properties

plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing comes from a keystore.properties file that is never committed.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// GPL-3.0 voice engine (Piper + espeak-ng). Off by default: enabling it makes the distributed
// APK subject to GPL-3.0 terms. See docs/LICENSING.md.
val withPiper = providers.gradleProperty("modulex.withPiper").map { it.toBoolean() }.getOrElse(false)

android {
    namespace = "com.modulex.translator"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.modulex.translator"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Translation/speech inference needs 64-bit ARM with dot-product instructions.
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DMX_THIRD_PARTY=${rootProject.file("third_party").absolutePath}",
                    "-DMX_WITH_TTS=${if (withPiper) "ON" else "OFF"}",
                )
                if (withPiper) arguments += "-DMX_ONNXRUNTIME_DIR=${layout.buildDirectory.dir("onnxruntime").get().asFile.absolutePath}"
                targets += listOfNotNull("mx_ai", "mx_ocr", if (withPiper) "mx_tts" else null)
            }
        }
        buildConfigField("boolean", "WITH_PIPER", withPiper.toString())
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "DIAGNOSTICS", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "DIAGNOSTICS", "false")
            signingConfig = signingConfigs.findByName("release")
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
        jniLibs {
            // Keep native libraries uncompressed and page-aligned so they load straight from the APK.
            useLegacyPackaging = false
        }
    }

    androidResources {
        // Model/OCR data must not be compressed: it is copied out once and memory-mapped.
        noCompress += listOf("traineddata", "json")
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// The onnxruntime AAR provides the native runtime and headers for the optional Piper engine.
val onnxruntime by configurations.creating
dependencies { onnxruntime(libs.onnxruntime.android) }
val extractOnnxruntime by tasks.registering(Copy::class) {
    from({ onnxruntime.resolve().map { zipTree(it) } })
    into(layout.buildDirectory.dir("onnxruntime"))
}
if (withPiper) {
    tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }
        .configureEach { dependsOn(extractOnnxruntime) }
}

dependencies {
    implementation(project(":engines"))
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // QR encode/decode without Google Play services.
    implementation(libs.zxing.core)
    // WebRTC data channels for Internet mode (prebuilt from this repository's parent project).
    implementation(libs.webrtc.android)
    implementation(libs.okhttp)
    if (withPiper) implementation(libs.onnxruntime.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
