// SPDX-License-Identifier: LGPL-3.0-or-later
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val releaseSecretsFile = rootProject.file(".signing/signing.properties")
val supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
val buildAbis =
    providers
        .gradleProperty("abis")
        .map { it.split(',').map(String::trim).distinct() }
        .getOrElse(supportedAbis)

require(buildAbis.isNotEmpty() && buildAbis.all { it in supportedAbis }) {
    "abis must contain supported architectures: ${supportedAbis.joinToString()}"
}

val splitApks = providers.gradleProperty("splitApks").isPresent
val useCcache = providers.gradleProperty("ccache").isPresent
val releaseSecrets =
    Properties().apply {
        if (releaseSecretsFile.isFile) releaseSecretsFile.inputStream().use { load(it) }
    }

android {
    namespace = "app.whisp"
    compileSdk = 37
    ndkVersion = "30.0.16248370"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    defaultConfig {
        applicationId = "app.whisp"
        minSdk = 26
        targetSdk = 36
        versionCode = 20
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
                if (useCcache) {
                    arguments += "-DCMAKE_C_COMPILER_LAUNCHER=ccache"
                    arguments += "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache"
                }
            }
        }
        if (!splitApks) ndk { abiFilters += buildAbis }
    }
    splits {
        abi {
            isEnable = splitApks
            reset()
            include(*buildAbis.toTypedArray())
            isUniversalApk = true
        }
    }
    signingConfigs {
        if (releaseSecretsFile.isFile)
            create("localRelease") {
                storeFile = rootProject.file(".signing/${releaseSecrets.getProperty("storeFile")}")
                storePassword = releaseSecrets.getProperty("storePassword")
                keyAlias = releaseSecrets.getProperty("keyAlias")
                keyPassword = releaseSecrets.getProperty("keyPassword")
            }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSecretsFile.isFile) signingConfig = signingConfigs.getByName("localRelease")
        }
    }
    // Isolated on-device tests preserve the installed user's release and its library.
    if (providers.gradleProperty("smokeTest").isPresent) {
        buildTypes.create("smoke") {
            initWith(buildTypes.getByName("release"))
            applicationIdSuffix = ".smoketest"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += "release"
            proguardFiles("smoke-proguard-rules.pro")
            testProguardFiles("test-proguard-rules.pro")
        }
        testBuildType = "smoke"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets.named("androidTest") { assets.srcDir("$projectDir/schemas") }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

// Unit test classes are independent (no fixed ports or shared dirs),
// so spread them across forks instead of Gradle's single-fork default.
tasks.withType<org.gradle.api.tasks.testing.Test> {
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8)
    maxHeapSize = "2g"
}

kotlin { compilerOptions { allWarningsAsErrors.set(true) } }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

val liveSmbTests = providers.environmentVariable("WHISP_SMB_TEST_CONFIG").isPresent

tasks.withType<Test>().configureEach {
    if (liveSmbTests) {
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("Live SMB tests depend on the current server state") { true }
    }
}

dependencies {
    constraints {
        implementation(libs.serialization.core) {
            because("Align the Navigation 3 serialization runtime with test serialization")
        }
    }
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.compose.ui)
    debugImplementation(libs.compose.ui.tooling)
    // Material 3 beta provides the public MotionScheme used by the app theme.
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.okio)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.jsoup)
    implementation(libs.smbj)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.extractor)
    implementation(libs.media3.ui.compose)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.uiautomator)
    debugImplementation(libs.compose.ui.test.manifest)
}
