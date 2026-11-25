// SPDX-License-Identifier: LGPL-3.0-or-later
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
        // Prefer Google's Maven Central mirror for Robolectric's ~200 MB SDK jar to avoid
        // Maven Central HTTP 429 rate-limiting on shared CI runners; falls through to
        // mavenCentral() if a newly published SDK artifact is not yet mirrored.
        maven {
            name = "GoogleMavenCentralMirror"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
            content { includeModule("org.robolectric", "android-all-instrumented") }
        }
        mavenCentral()
    }
}

rootProject.name = "Whisp"

include(":app", ":core:model", ":core:native", ":benchmark")
