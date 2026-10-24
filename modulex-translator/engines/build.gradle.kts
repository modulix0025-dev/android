import java.time.Duration

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Host builds of the JNI libraries (see native/CMakeLists.txt) are exercised by the JVM tests.
val nativeHostDir = rootProject.layout.buildDirectory.dir("native-host")
tasks.test {
    // The opt-in translation-quality experiment runs hundreds of generations.
    timeout.set(Duration.ofMinutes(if (providers.gradleProperty("modulex.mtEvalModels").isPresent) 150 else 20))
    systemProperty("modulex.native.dir", nativeHostDir.get().asFile.absolutePath)
    // -Pmodulex.tessdata=/dir evaluates other OCR data with the same tests (e.g. tessdata_best).
    systemProperty("modulex.tessdata.dir", providers.gradleProperty("modulex.tessdata").getOrElse(rootProject.file("app/src/main/assets/tessdata").absolutePath))
    systemProperty("modulex.pipelineFixture", rootProject.layout.buildDirectory.file("fixtures/pipeline-fixture.gguf").get().asFile.absolutePath)
    providers.gradleProperty("modulex.testModel").orNull?.let { systemProperty("modulex.testModel", it) }
    providers.gradleProperty("modulex.testSpeechModel").orNull?.let { systemProperty("modulex.testSpeechModel", it) }
    providers.gradleProperty("modulex.mtEvalModels").orNull?.let { systemProperty("modulex.mtEvalModels", it) }
    providers.gradleProperty("modulex.tmEvalPerDialect").orNull?.let { systemProperty("modulex.tmEvalPerDialect", it) }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Builds the JNI libraries for the host and the random-weight pipeline fixture.
// Requires cmake, ninja, a C++17 compiler, python3+numpy and tools/fetch-native-deps.sh.
val thirdParty = rootProject.file("third_party")
val buildNativeHost by tasks.registering(Exec::class) {
    onlyIf { thirdParty.resolve("llama.cpp").isDirectory }
    workingDir = rootProject.projectDir
    commandLine("sh", "-c",
        "cmake -S native -B build/native-host -G Ninja -DCMAKE_BUILD_TYPE=Release && " +
        "cmake --build build/native-host --target mx_ai mx_ocr")
}
val pipelineFixture by tasks.registering(Exec::class) {
    onlyIf { thirdParty.resolve("llama.cpp").isDirectory }
    workingDir = rootProject.projectDir
    doFirst { rootProject.layout.buildDirectory.dir("fixtures").get().asFile.mkdirs() }
    commandLine("python3", "tools/make_pipeline_fixture.py", "third_party/llama.cpp/gguf-py", "build/fixtures/pipeline-fixture.gguf")
}
tasks.test {
    dependsOn(buildNativeHost, pipelineFixture)
    // Re-run when the native libraries change.
    inputs.files(fileTree(nativeHostDir) { include("*.so") }).withPropertyName("nativeLibraries")
}
