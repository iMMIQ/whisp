# 原生依赖（libnfs、libsmb2 与 NEON DSP）

Whisp 在独立原生模块 `:core:native` 中集成 NFS、SMB 2/3 网络文件系统客户端以及音频 DSP 加速库，本文档记录上游依赖固定版本、构建架构、离线源码重建步骤以及开源许可合规要求。

---

## 固定上游源码与校验

| 项目 | 固定版本 / Commit | SHA-256 | 上游许可 |
| --- | --- | --- | --- |
| **[sahlberg/libnfs](https://github.com/sahlberg/libnfs)** | `8.0.0` (`5b1bc60260b5ddac34900c5315010b88b73e7edd`) | `80b67a8905f7e2051245c6fadc1c4beb7a6481da278e7664fee146e3bbe4b0f1` | LGPL-2.1-or-later / BSD |
| **[sahlberg/libsmb2](https://github.com/sahlberg/libsmb2)** | `7.0.0` (`62a861a043fadb69ee375464efb17e94099f8f76`) | `b5a55dd1597f1de81f927015cf4cb865fe58ea48d80a0a6006f74c94eb915e29` | LGPL-2.1-or-later |

| 构建工具链 | 固定值 |
| --- | --- |
| **Android NDK / CMake** | NDK `30.0.16248370` · CMake `3.31.6` |
| **支持 ABI** | `arm64-v8a`、`x86_64`（64 位架构，默认构建 `arm64-v8a`） |
| **C++ 运行时** | 无 STL（`-DANDROID_STL=none`，`-fno-exceptions -fno-rtti`） |

源码下载与 SHA-256 校验由 [core/native/src/main/cpp/CMakeLists.txt](../core/native/src/main/cpp/CMakeLists.txt) 中的 CMake `FetchContent` 自动完成。

---

## 架构与运行约束

Android 原生部分拆分为 5 个独立共享库，所有支持的 64 位 ABI 均由相同源码构建：

1. **`libnfs.so`**：由上游 `libnfs 8.0.0` 编译（关闭测试、工具、示例、文档、内部多线程与 TLS）。
2. **`libsmb2.so`**：由上游 `libsmb2 7.0.0` 编译（关闭 Kerberos / GSSAPI / 工具 / 示例，内置 NTLMSSP 认证与 SMB3 AES 加密）。
3. **`libwhisp_nfs.so`**：由 [core/native/src/main/cpp/nfs_bridge.cpp](../core/native/src/main/cpp/nfs_bridge.cpp) 编译，向 `NfsNative` 暴露只读目录遍历、`DirectByteBuffer` 零拷贝随机读与常规数组读接口。
4. **`libwhisp_smb.so`**：由 [core/native/src/main/cpp/smb_bridge.cpp](../core/native/src/main/cpp/smb_bridge.cpp) 编译，向 `SmbNative` 暴露 SMB 2/3（默认强制 SMB3 加密，可选未加密局域网共享）只读目录遍历、`DirectByteBuffer` 零拷贝随机读与常规数组读接口，凭据字节在使用后立即清零。
5. **`libwhisp_dsp.so`**：由 [core/native/src/main/cpp/dsp_bridge.cpp](../core/native/src/main/cpp/dsp_bridge.cpp) 编译（`-O3`），为 `WhispAudioProcessor` 提供基于 `DirectByteBuffer` 零拷贝与 ARM64 NEON SIMD 向量化加速的双声道声道互换、单声道合并与夜间防炸耳柔化处理。

关键行为边界：
- **只读原则**：SMB 与 NFS 客户端仅执行只读挂载并仅打开只读文件句柄；所有连接、文件句柄和缓冲区随操作结束显式释放。
- **编码与大文件**：文件名与路径以原始 UTF-8 字节数组在 JNI 两侧传递（拒绝含空字节或超长路径），文件长度与随机读取偏移统一使用 64 位整数，完整支持大于 4 GiB 的文件。
- **可复现路径前缀**：编译参数启用 `-ffile-prefix-map`，将源码、构建目录与 NDK 绝对路径统一映射为 `/whisp/source`、`/whisp/build`、`/android/ndk`，避免本机绝对路径进入二进制符号。

---

## 构建与离线源码重建

### 使用配套源码离线重建

每个 Release 附带的源码归档包会将固定版本的完整源码分别放置在 `third_party/libnfs/` 与 `third_party/libsmb2/`。离线环境可通过 CMake 参数指向对应目录离线重建全部原生库：

```sh
cmake -S core/native/src/main/cpp -B build/native-offline \
  -DFETCHCONTENT_SOURCE_DIR_LIBNFS="$PWD/third_party/libnfs" \
  -DFETCHCONTENT_SOURCE_DIR_LIBSMB2="$PWD/third_party/libsmb2"
```

---

## 主机端测试与代码规范

全部 3 个 JNI 桥接层均可在主机端通过 stub 头文件独立运行 AddressSanitizer + UndefinedBehaviorSanitizer 测试：

```sh
# 编译并运行 NFS / SMB2 / DSP 全部主机端 ASan + UBSan 测试
./gradlew nativeHostTest

# 检查与格式化 C/C++ 代码（需 clang-format-19）
./gradlew cppFormatCheck
./gradlew cppFormat
```

---

## 许可证与源码分发义务

- **libnfs 8.0.0**：`LGPL-2.1-or-later` / BSD；**libsmb2 7.0.0**：`LGPL-2.1-or-later`。
- **Whisp 原生桥接与 DSP 层**：`LGPL-3.0-or-later`（见 [LICENSE](../LICENSE) 与 [NOTICE](../NOTICE)）。
- **二进制分发要求**：每次发布原生安装包时均须同时提供配套源码归档，包含 Whisp 工程源码、构建脚本、JNI 桥接源码以及上述固定提交的完整 `libnfs` 与 `libsmb2` 源码。
