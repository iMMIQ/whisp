// SPDX-License-Identifier: LGPL-3.0-or-later
plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "app.whisp.benchmark"
    compileSdk = 37
    targetProjectPath = ":app"
    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

kotlin { compilerOptions { allWarningsAsErrors.set(true) } }

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.uiautomator)
    implementation(libs.benchmark.macro.junit4)
}
