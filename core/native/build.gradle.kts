// SPDX-License-Identifier: LGPL-3.0-or-later
plugins {
    alias(libs.plugins.android.library)
}

val supportedAbis = listOf("arm64-v8a", "x86_64")
val defaultAbis = listOf("arm64-v8a")
val buildAbis =
    providers
        .gradleProperty("abis")
        .map { it.split(',').map(String::trim).distinct() }
        .getOrElse(defaultAbis)

require(buildAbis.isNotEmpty() && buildAbis.all { it in supportedAbis }) {
    "abis must contain supported architectures: ${supportedAbis.joinToString()}"
}

val useCcache = providers.gradleProperty("ccache").isPresent

android {
    namespace = "app.whisp.core.nativebridge"
    compileSdk = 37
    ndkVersion = "30.0.16248370"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
                if (useCcache) {
                    arguments += "-DCMAKE_C_COMPILER_LAUNCHER=ccache"
                    arguments += "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache"
                }
            }
        }
        ndk { abiFilters += buildAbis }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { allWarningsAsErrors.set(true) } }
