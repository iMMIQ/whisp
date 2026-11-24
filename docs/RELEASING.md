# 版本发布指南

本文档定义 Whisp 的版本号规范、本地签名配置、发布构建步骤与审批流程。

---

## 版本命名与递增规则

- **候选版（RC）**：开发与验收阶段使用 `X.Y.Z-rc.N` 命名（例如 `0.5.0-rc.3`），对应 Git 标签为 `vX.Y.Z-rc.N`。
- **正式版**：验收通过后发布 `X.Y.Z`（例如 `0.5.0`），对应 Git 标签为 `vX.Y.Z`。
- **`versionCode` 单调递增**：每一次新的 RC 或正式版构建都必须递增 [app/build.gradle.kts](../app/build.gradle.kts) 中的 `versionCode`，确保设备可以直接覆盖安装而无需卸载重装。

---

## 本地签名配置

Release 构建默认产出未签名 APK。如本机已配置 `.signing/signing.properties`（该目录由 `.gitignore` 与公开内容检查双重排除，**严禁提交到仓库**），Gradle 会自动启用 `localRelease` 签名配置。

`.signing/signing.properties` 包含以下 4 个键（`storeFile` 为相对 `.signing/` 目录的文件名）：

```properties
storeFile=whisp-release.jks
storePassword=<keystore-password>
keyAlias=<key-alias>
keyPassword=<key-password>
```

---

## 候选版（RC）准备流程

### 1. 更新版本并完成验证

1. 更新 `app/build.gradle.kts` 中的 `versionCode` 与 `versionName`。
2. 执行完整格式化、质量门禁与公开内容检查：

```sh
./gradlew fmt
./gradlew qualityCheck
python3 tools/check-public-content.py
```

3. 完成对应变更范围的真机或隔离测试（见 [测试指南](TESTING.md)）。

### 2. 构建多架构与通用签名 APK

```sh
./gradlew -PsplitApks :app:assembleRelease :app:lintRelease
```

产物位于 `app/build/outputs/apk/release/`，核对所有 5 个 APK 的版本号、目标 ABI 与签名完整性：

- `app-arm64-v8a-release.apk`
- `app-armeabi-v7a-release.apk`
- `app-x86_64-release.apk`
- `app-x86-release.apk`
- `app-universal-release.apk`

可使用 `apksigner verify --print-certs <apk>` 验证签名证书指纹无误。

### 3. 准备配套源码与校验文件

为满足 LGPL 分发义务（详见 [docs/NATIVE.md](NATIVE.md)）：

1. 打包对应提交的完整项目源码归档，并在归档内 `third_party/libnfs/` 放入固定提交与校验值一致的完整 `libnfs 8.0.0` 源码。
2. 为所有 APK 与源码归档生成 `SHA256SUMS` 校验文件：

```sh
sha256sum app/build/outputs/apk/release/*.apk <source-archive>.zip > SHA256SUMS
```

3. 编写清晰的版本说明（包含新特性、修复项、已知限制与升级注意事项）。

### 4. 创建预发布草稿等待验收

在 GitHub Releases 创建 **Pre-release 草稿（Draft）**，上传全部 5 个 APK、配套源码归档与 `SHA256SUMS`，交由项目维护者验收。

---

## 发布审批与公开

> **硬性约束**：每一次公开发布（无论是 RC 预发布还是正式版）都必须获得项目维护者对该具体版本的明确批准。功能开发、版本准备以及以往版本的发布授权均不自动等同于当前版本的公开许可。

- **批准公开 RC 后**：创建对应的 `vX.Y.Z-rc.N` Git 标签并推送，随后公开对应的 GitHub Pre-release 条目。
- **批准发布正式版后**：
  1. 将 `versionName` 更新为正式版本号 `X.Y.Z`，并再次递增 `versionCode`。
  2. 重新执行 `qualityCheck` 与 `-PsplitApks :app:assembleRelease :app:lintRelease`。
  3. 重新生成配套源码归档与 `SHA256SUMS`，创建 `vX.Y.Z` 标签并发布正式 Release 条目。

---

## 发布产物清单

| 产物 | 用途 |
| --- | --- |
| 4 个分架构 Release APK（`arm64-v8a` / `armeabi-v7a` / `x86_64` / `x86`） | 供已知设备架构用户下载，体积更小 |
| `app-universal-release.apk` | 覆盖全部四种 ABI 的通用安装包 |
| 配套源码归档（含 `third_party/libnfs/`） | 满足 LGPL 源码分发与原生共享库独立重建要求 |
| `SHA256SUMS` | 供用户校验所有安装包与源码归档完整性 |
