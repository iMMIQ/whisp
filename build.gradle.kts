// SPDX-License-Identifier: LGPL-3.0-or-later
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target("app/src/**/*.kt", "core/*/src/**/*.kt", "benchmark/src/**/*.kt")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts", "core/*/*.gradle.kts", "benchmark/*.gradle.kts")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
    format("text") {
        target(
            "*.md",
            "docs/*.md",
            ".gitignore",
            ".gitattributes",
            ".editorconfig",
            "*.properties",
            "app/*.pro",
            "core/*/*.pro",
            "gradle/*.toml",
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
}

val blackExecutable = providers.environmentVariable("WHISP_BLACK").orElse("black")

tasks.register<Exec>("pythonFormat") {
    commandLine(blackExecutable.get(), "--required-version", "26.10.1", "tools")
}

tasks.register<Exec>("pythonFormatCheck") {
    commandLine(blackExecutable.get(), "--required-version", "26.10.1", "--check", "tools")
}

tasks.register<Exec>("publicContentCheck") {
    commandLine("python3", "tools/check-public-content.py")
}

val clangFormatExecutable =
    providers.environmentVariable("WHISP_CLANG_FORMAT").orElse("clang-format-19")
val cppFiles =
    listOf(
        "core/native/src/main/cpp/nfs_bridge.cpp",
        "core/native/src/main/cpp/nfs_bridge_test.cpp",
        "core/native/src/main/cpp/smb_bridge.cpp",
        "core/native/src/main/cpp/smb_bridge_test.cpp",
        "core/native/src/main/cpp/dsp_bridge.cpp",
        "core/native/src/main/cpp/dsp_bridge_test.cpp",
        "core/native/src/main/cpp/test_stubs/nfsc/libnfs.h",
        "core/native/src/main/cpp/test_stubs/smb2/smb2.h",
        "core/native/src/main/cpp/test_stubs/smb2/libsmb2.h",
    )

tasks.register<Exec>("cppFormat") {
    commandLine(listOf(clangFormatExecutable.get(), "-i") + cppFiles)
}

tasks.register<Exec>("cppFormatCheck") {
    commandLine(listOf(clangFormatExecutable.get(), "--dry-run", "--Werror") + cppFiles)
}

val hostNativeBuildDir = layout.buildDirectory.dir("host-jni-test")

tasks.register<Exec>("nativeHostTestConfigure") {
    commandLine(
        "cmake",
        "-S",
        "core/native/src/main/cpp",
        "-B",
        hostNativeBuildDir.get().asFile.absolutePath,
        "-DWHISP_BUILD_HOST_TESTS=ON",
    )
}

tasks.register<Exec>("nativeHostTestBuild") {
    dependsOn("nativeHostTestConfigure")
    commandLine("cmake", "--build", hostNativeBuildDir.get().asFile.absolutePath)
}

tasks.register<Exec>("nativeHostTest") {
    group = "verification"
    description = "Run host C++ JNI bridge tests (NFS, SMB2, NEON DSP) with ASan and UBSan."
    dependsOn("nativeHostTestBuild")
    commandLine(
        "ctest",
        "--test-dir",
        hostNativeBuildDir.get().asFile.absolutePath,
        "--output-on-failure",
    )
}

tasks.register("fmt") {
    group = "formatting"
    description = "Format Kotlin, Gradle scripts, Python tools and text files."
    dependsOn("spotlessApply", "pythonFormat", "cppFormat")
}

tasks.register("qualityCheck") {
    group = "verification"
    description = "Check formatting, public content, unit tests, host JNI tests and Android Lint."
    dependsOn(
        "spotlessCheck",
        "pythonFormatCheck",
        "publicContentCheck",
        "cppFormatCheck",
        "nativeHostTest",
        ":app:testDebugUnitTest",
        ":app:lintDebug",
        ":app:lintRelease",
        ":core:model:lintRelease",
        ":core:native:lintRelease",
        ":benchmark:compileBenchmarkKotlin",
    )
}
