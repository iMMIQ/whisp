# 工程质量与构建

本文档说明 Whisp 的代码格式约定、质量门禁任务、本机构建加速方法以及 GitHub Actions CI 配置。

---

## 环境与工具准备

项目固定工具版本如下（核心 Android / Kotlin 依赖版本统一维护在 [gradle/libs.versions.toml](../gradle/libs.versions.toml)）：

- **JDK**：21
- **Gradle Wrapper**：9.8.1（`./gradlew`）
- **Android SDK**：`platforms;android-37.0`、`build-tools;36.0.0`、`ndk;30.0.16248370`、`cmake;3.31.6`
- **Kotlin / Gradle 格式化**：Spotless 8.10.4 + ktfmt 0.65（Kotlin 官方风格）
- **Python 格式化**：Black `26.10.1`
- **C++ 格式化**：`clang-format-19`（配置见 [.clang-format](../.clang-format)）
- **通用编辑器约定**：见 [.editorconfig](../.editorconfig)

在 Debian / Ubuntu 上准备本地质量检查环境：

```sh
sudo apt-get install -y openjdk-21-jdk-headless python3-venv clang-format-19 cmake g++ ccache
python3 -m venv .venv
.venv/bin/python -m pip install black==26.10.1
export WHISP_BLACK="$PWD/.venv/bin/black"
```

> 如本机 `clang-format-19` 位于自定义路径，可通过环境变量 `WHISP_CLANG_FORMAT` 指定。

---

## 格式化与质量门禁

### 一键命令

```sh
./gradlew fmt            # 自动格式化 Kotlin、Gradle、Python、C++ 与文本/Markdown 文件
./gradlew qualityCheck   # 执行完整质量门禁（与 CI 一致）
```

### `qualityCheck` 包含的检查项

| 子任务 | 检查范围与作用 |
| --- | --- |
| `spotlessCheck` | 校验 `app/src/**/*.kt`、`*.gradle.kts` 以及 Markdown、属性文件、ProGuard 规则和版本目录的尾随空白与末尾换行 |
| `pythonFormatCheck` | 使用 `black --required-version 26.10.1 --check tools` 校验 Python 工具脚本格式 |
| `publicContentCheck` | 执行 `python3 tools/check-public-content.py`，拦截敏感信息与非公开文件 |
| `cppFormatCheck` | 使用 `clang-format-19 --dry-run --Werror` 校验 `app/src/main/cpp/` 下 JNI 桥接与测试代码 |
| `nativeHostTest` | 在主机端以 `-DWHISP_BUILD_HOST_TESTS=ON` 编译并运行 C++ JNI 桥接测试（启用 ASan + UBSan） |
| `:app:testDebugUnitTest` | 运行全部 JVM 单元测试（并行 fork，离线 Robolectric） |
| `:app:lintDebug` | Debug 变体 Android Lint 检查 |
| `:app:lintRelease` | Release 变体 Android Lint 检查 |

---

## 公开内容检查

[tools/check-public-content.py](../tools/check-public-content.py) 同时扫描 Git 工作区候选文件与暂存区（staged blobs），在不打印敏感明文的前提下拦截以下内容：

- **个人主机路径**：如本机用户目录、挂载目录路径（示例 `smb://`、`nfs://`、`https://`、`content://` URL 中的路径段除外）。
- **私有内网 IPv4 地址**：RFC 1918 网段地址（仅放行本机回环 `127.0.0.1` 与模拟器宿主 `10.0.2.2`）。
- **凭据与密钥**：私钥 PEM 块、常见访问令牌格式、带引号的字面签名密码字段。
- **禁止入库的目录与扩展名**：`.signing/`、`.gradle/`、`build/`、`fixtures/`、`screenshots/`、`artifacts/`、`local.properties`、`.env*`，以及密钥库、APK/AAB、音视频媒体、数据库和压缩包文件（仓库内两个已知 SHA-256 的合成测试视频样本除外）。

提交前建议配合 Git 差异检查：

```sh
python3 tools/check-public-content.py
git diff --cached --check
```

---

## 构建性能与缓存

### Gradle 缓存与并行

[gradle.properties](../gradle.properties) 默认开启并行构建、任务输出缓存（Build Cache）和配置缓存（Configuration Cache），本机连续构建会自动复用 Daemon 与已配置任务图。

- 启用 `WHISP_SMB_TEST_CONFIG` 运行真实 Samba 合同测试时，Gradle 会自动禁用单元测试缓存，确保每次真实校验远端服务状态。
- Robolectric 预插桩 Android SDK 依赖由 Gradle `robolectricSdk` 配置解析（并在 [settings.gradle.kts](../settings.gradle.kts) 优先使用 Google Maven Central 镜像），测试运行时启用 `-Drobolectric.offline=true`，避免运行时向 Maven Central 拉包触发限流。

### 按需裁剪 ABI

日常开发和设备测试不需要每次编译全部 4 种原生架构，可通过 `-Pabis` 指定目标架构（支持逗号分隔）：

```sh
# 仅构建真机常用的 arm64-v8a Debug 包
./gradlew -Pabis=arm64-v8a :app:assembleDebug

# 仅构建 x86_64 模拟器隔离测试包
./gradlew -Pabis=x86_64 -PsmokeTest :app:assembleSmoke :app:assembleSmokeAndroidTest
```

### 使用 `ccache` 加速原生编译

启用 `-Pccache` 可缓存 `libnfs` 与 `nfs_bridge.cpp` 的 C/C++ 编译对象：

```sh
./gradlew -Pccache -Pabis=arm64-v8a :app:assembleDebug
ccache --show-stats
```

### 构建耗时分析

如需定位本机构建瓶颈，可附加 `--profile` 生成 HTML 报告（输出在 `build/reports/profile/`）：

```sh
./gradlew -Pabis=arm64-v8a :app:assembleDebug --profile
```

对比耗时变化时，请保持任务、ABI、Daemon 状态一致，并区分冷构建、清理后缓存恢复与增量构建。

---

## CI 流水线

GitHub Actions 流水线定义在 [.github/workflows/quality.yml](../.github/workflows/quality.yml)，在 `main` 分支推送、Pull Request 及手动触发时运行：

1. **环境与缓存恢复**：在 `ubuntu-24.04` 上安装 JDK 21、Python 3.12、`black==26.10.1`、`clang-format-19`、`ccache`、`cmake`、`g++` 及固定版本 Android SDK/NDK；通过 `setup-gradle` 复用依赖与 Gradle 任务输出缓存，并按 NDK/CMake 版本与 `app/src/main/cpp/**` 哈希恢复 200 MiB 上限的 `ccache` 目录。
2. **质量检查与测试包构建**：以 `-Pccache -Pabis=x86_64` 执行 `qualityCheck` 与 `:app:assembleDebugAndroidTest`。
3. **发布产物验证**：执行 `-Pccache -PsplitApks :app:assembleRelease`，完整验证 4 种 ABI 拆包与通用 APK 的 R8 混淆和原生构建。
4. **缓存写回**：仅当 `main` 分支非 PR 构建成功且原缓存未命中时写回 `ccache`。

---

## 编译器与工具链说明

- **严格编译**：Kotlin 启用 `allWarningsAsErrors = true`，C++ 原生桥接启用 `-Wall -Wextra -Werror`。
- **SDK 目标范围**：`compileSdk = 37`、`targetSdk = 36`、`minSdk = 26`。Release Lint 可能会提示面向 API 37 的前瞻性适配建议，属于预期追踪项。
- **Material 3 与 Compose 实验 API**：Material 3 固定为 `1.5.0-beta01` 以使用公开的 `MotionScheme` API；共享元素转场依赖 Compose 实验 API，并由设备端 UI 回归测试守护。
- **上游弃用提示**：Gradle 9.8 报告的 `Configuration.setVisible` 弃用警告来自 AGP 9.4.1 内部实现，待后续 AGP 升级跟进。
