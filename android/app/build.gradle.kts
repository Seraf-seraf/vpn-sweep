plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
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
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/licenses"))
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
    implementation(files("libs/sweep.aar"))
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
}
