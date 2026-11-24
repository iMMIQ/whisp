# 原生依赖（libnfs 与 JNI 桥接）

Whisp 通过原生共享库接入 NFS 网络文件系统，本文档记录上游依赖固定版本、构建架构、离线源码重建步骤以及开源许可合规要求。

---

## 固定上游源码与校验

| 项目 | 固定值 |
| --- | --- |
| **上游项目** | [sahlberg/libnfs](https://github.com/sahlberg/libnfs) `8.0.0` |
| **固定 Commit** | `5b1bc60260b5ddac34900c5315010b88b73e7edd` |
| **源码包地址** | `https://codeload.github.com/sahlberg/libnfs/tar.gz/5b1bc60260b5ddac34900c5315010b88b73e7edd` |
| **源码包 SHA-256** | `80b67a8905f7e2051245c6fadc1c4beb7a6481da278e7664fee146e3bbe4b0f1` |
| **Android NDK / CMake** | NDK `30.0.16248370` · CMake `3.31.6` |
| **支持 ABI** | `arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86` |

源码下载与 SHA-256 校验由 [app/src/main/cpp/CMakeLists.txt](../app/src/main/cpp/CMakeLists.txt) 中的 CMake `FetchContent` 自动完成。

---

## 架构与运行约束

Android 原生部分拆分为两个独立的共享库，所有 4 种 ABI 均由相同源码构建：

1. **`libnfs.so`**：由上游 `libnfs 8.0.0` 编译（构建时关闭测试、工具、示例、文档、内部多线程与 TLS，仅保留轻量客户端核心）。
2. **`libwhisp_nfs.so`**：由 Whisp 自研的 [app/src/main/cpp/nfs_bridge.cpp](../app/src/main/cpp/nfs_bridge.cpp) 编译（C++17、`-fvisibility=hidden`、`-Wall -Wextra -Werror`），通过 JNI 向 Kotlin 层 `NativeNfsSession` 暴露只读目录遍历、文件属性查询和随机读取接口。

关键行为边界：
- **协议与传输**：支持 NFS `3`、`4.0`、`4.2`，使用 TCP 与 `AUTH_SYS`（UID/GID 身份）。
- **只读原则**：客户端仅执行只读挂载并仅打开只读文件句柄；所有连接、文件句柄和缓冲区随操作结束显式释放。
- **并发与缓冲**：每个连接复用对应的 RPC 上下文；目录扫描最多使用 4 个独立连接，媒体读取使用 256 KiB 缓冲区，并限制单次超时与重连次数。
- **编码与大文件**：文件名以原始 UTF-8 字节数组在 JNI 两侧传递（拒绝含空字节或超长路径），文件长度与随机读取偏移统一使用 64 位整数，完整支持大于 4 GiB 的文件。
- **可复现路径前缀**：编译参数启用 `-ffile-prefix-map`，将源码、构建目录与 NDK 绝对路径统一映射为 `/whisp/source`、`/whisp/build`、`/android/ndk`，避免本机绝对路径进入二进制符号。

---

## 构建与离线源码重建

### 常规构建（自动拉取）

常规执行 Gradle 构建（如 `./gradlew :app:assembleDebug`）时，CMake 会自动从上述固定地址下载并校验 `libnfs` 源码。

### 使用配套源码离线重建

每个 Release 附带的源码归档包会将完整的固定版本 `libnfs` 源码放置在 `third_party/libnfs/`。离线环境或需要基于配套源码独立验证重建时，可通过 CMake 参数 `FETCHCONTENT_SOURCE_DIR_LIBNFS` 指向该目录：

```sh
# 方式一：在独立 CMake 目录中交叉编译校验
cmake -S app/src/main/cpp -B build/nfs-offline \
  -DFETCHCONTENT_SOURCE_DIR_LIBNFS="$PWD/third_party/libnfs"

# 方式二：检查源码包校验值后由 Android Gradle Plugin 完整打包并重新签名
```

将 `third_party/libnfs` 解压到本机后，向 AGP 的 CMake 参数传入 `-DFETCHCONTENT_SOURCE_DIR_LIBNFS=<路径>` 即可完全离线重建 `libnfs.so` 与 `libwhisp_nfs.so`，并通过常规 Release 流程重新打包与签名。

---

## 主机端测试与代码规范

JNI 桥接层无需 Android 设备即可在主机端通过 stub 头文件（`app/src/main/cpp/test_stubs/nfsc/libnfs.h`）独立运行消毒器测试：

```sh
# 编译并运行 AddressSanitizer + UndefinedBehaviorSanitizer 测试
./gradlew nativeHostTest

# 检查与格式化 C++ 代码（需 clang-format-19）
./gradlew cppFormatCheck
./gradlew cppFormat
```

---

## 许可证与源码分发义务

- **libnfs 8.0.0**：库实现采用 `LGPL-2.1-or-later`，RPC/NFS 协议定义及生成文件采用上游提供的 BSD 许可；上游许可说明见 [libnfs COPYING](https://github.com/sahlberg/libnfs/blob/libnfs-8.0.0/COPYING)，各源码文件保留其原有声明。
- **Whisp JNI 桥接层**：采用本项目许可证 `LGPL-3.0-or-later`（见 [LICENSE](../LICENSE) 与 [NOTICE](../NOTICE)）。
- **二进制分发要求**：由于 `libnfs.so` 与 `libwhisp_nfs.so` 以独立共享库形式随 APK 分发，每次发布原生安装包时均须同时提供配套源码归档，包含 Whisp 构建脚本、JNI 桥接源码以及上述固定提交的完整 `libnfs` 源码，以便用户独立重新编译并替换对应共享库。
