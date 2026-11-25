// SPDX-License-Identifier: LGPL-3.0-or-later
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.whisp.core.model"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { allWarningsAsErrors.set(true) } }

dependencies {
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
}
