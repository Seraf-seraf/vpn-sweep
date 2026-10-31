import com.android.compose.screenshot.tasks.PreviewScreenshotUpdateTask
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.UUID

plugins {
    id("com.android.application")
    id("com.android.compose.screenshot")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
    namespace = "com.seraf.vpnsweep"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "com.seraf.vpnsweep"
        minSdk = 34
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.2-integration"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    buildTypes {
        create("qa") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            applicationIdSuffix = ".qa"
        }
    }
    testBuildType = "qa"
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/licenses"))
    sourceSets.configureEach {
        if (name == "screenshotTestQa") java.srcDir("src/qa/java")
    }
    packaging { jniLibs.useLegacyPackaging = false }
    lint { abortOnError = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencyLocking {
    lockAllConfigurations()
    // Common metadata redirects to the locked JVM stdlib. Locking the metadata
    // node makes this Gradle/Kotlin combination reject the resolved JVM graph.
    ignoredDependencies.add("org.jetbrains.kotlin:kotlin-stdlib-common")
}

dependencies {
    add("screenshotTestImplementation", "com.android.tools.screenshot:screenshot-validation-api:0.0.1-alpha16")
    add("screenshotTestImplementation", platform("androidx.compose:compose-bom:2025.10.01"))
    add("screenshotTestImplementation", "androidx.compose.ui:ui-tooling")
    implementation(files("libs/sweep.aar"))
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    add("qaImplementation", "androidx.compose.ui:ui-tooling")
    add("qaImplementation", "androidx.compose.ui:ui-tooling-preview")
    add("qaImplementation", "androidx.compose.ui:ui-test-manifest")
    add("qaImplementation", "com.android.tools.screenshot:screenshot-validation-api:0.0.1-alpha16")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

androidComponents {
    onVariants(selector().withBuildType("qa")) {
        val previewRun = layout.buildDirectory.dir("outputs/compose-preview/${UUID.randomUUID()}")
        tasks.named<PreviewScreenshotUpdateTask>("updateQaScreenshotTest") {
            testEngineInput.referenceImageDir.set(previewRun.map { it.dir("reference") })
            testEngineInput.previewImageOutputDir.set(previewRun.map { it.dir("rendered") })
            testEngineInput.diffImageOutputDir.set(previewRun.map { it.dir("diffs") })
            testEngineInput.junitXmlOutputDirectory.set(previewRun.map { it.dir("junit") })
            binaryResultsDirectory.set(previewRun.map { it.dir("binary") })
            reports.junitXml.outputLocation.set(previewRun.map { it.dir("junit") })
            reports.html.outputLocation.set(previewRun.map { it.dir("report") })
            doFirst {
                val runDirectory = previewRun.get().asFile.toPath()
                Files.createDirectories(runDirectory)
                try {
                    Files.createFile(runDirectory.resolve(".preview-started"))
                } catch (exception: FileAlreadyExistsException) {
                    throw GradleException("Каталог Preview уже использован; запустите make android-preview", exception)
                }
                for (name in listOf("reference", "rendered", "diffs", "binary", "junit", "report")) {
                    val directory = runDirectory.resolve(name)
                    if (Files.exists(directory)) {
                        check(Files.isDirectory(directory) && Files.newDirectoryStream(directory).use { !it.iterator().hasNext() }) {
                            "Каталог результатов Preview не пуст: $directory"
                        }
                    }
                }
            }
            doLast { logger.lifecycle("Compose Preview PNG: ${previewRun.get().dir("rendered").asFile}") }
        }
    }
}
