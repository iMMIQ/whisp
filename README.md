# Whisp

面向本地文件、NAS 与在线作品的 Android 音视频播放器，专为音声、ASMR 与带字幕媒体打造。

> **运行要求**：Android 8.0（API 26）及以上 · 支持 `arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86` · 许可证 [LGPL-3.0-or-later](LICENSE)

---

## 核心特性

### 多源统一作品库
- **本地目录**：通过系统目录授权（SAF）按需建立索引，支持中文、日文及各类 Unicode 文件名。
- **NAS 远程直连**：原生支持 **SMB 2/3**、**NFS 3 / 4.0 / 4.2** 与 **HTTPS WebDAV**，扫描、在线播放、随机跳转与离线下载共用同一套作品库与播放器。
- **DLsite 官方已购音声**：通过官方登录页使用 viviON ID 登录，同步已购 RJ 同人音声与 VJ 商业音声，按需读取曲目树并原生播放或离线下载。
- **ASMR.ONE 在线目录**：支持分页浏览、关键字/声优/社团/标签/字幕组合筛选、RJ 号与链接直达、官方 DLsite 元数据补全，打开或收藏后自动加入作品库。
- **作品组织**：支持作品库多维度排序（添加时间、最近收听、标题、离线优先）、系列浏览、收藏、全文检索，以及按目录和正篇/附赠自动区分录音版本（各版本拥有独立播放队列）。

### 为音声与夜间收听设计的播放引擎
- **低功耗与硬件卸载**：默认开启 Android 硬件 DSP 音频卸载，配合大窗口突发友好缓冲降低夜间后台功耗。
- **夜间听感处理（实时零中断切换）**：
  - **夜间防炸耳柔化**：轻度提亮轻声耳语，并对突发高音/拍手峰值进行柔和压缩与限幅。
  - **左右声道互换**：修正双耳声道录反或睡眠耳机反戴。
  - **单声道合并**：侧睡单耳佩戴时完整收听双耳内容。
  - **夜间精细音量**：支持 15%–100% 细腻衰减。
- **细腻播放细节**：支持连续音轨无缝衔接优先、恢复播放微淡入、长暂停（超过 5 分钟）后自动回退 3 秒、通知/导航提示音压低音量而非中断播放。
- **精确定位与循环**：
  - **精细拖动进度条**：拖动进度条时纵向拉出即可缩放至 60 秒精细窗口，附带实时字幕预览与书签刻度。
  - **片段书签与 A–B 循环**：支持时间点书签、区间书签与字幕行一键加签，以及任意片段 A–B 循环。
  - **灵活控制**：可配置快进/后退步长（5 / 10 / 15 / 30 秒）、0.5x–2.0x 播放倍速、队列随机与手动重排。
- **助眠定时停止**：支持按时长（最长 180 分钟）或「播完当前音轨」停止，优先使用 `AlarmManager` 调度以保持深度休眠与硬件卸载，结束前按二次感知曲线平滑淡出（12 / 15 / 30 秒可选）并记录起睡位置锚点。

### 字幕、台本与附赠资料
- **多格式支持**：支持 VTT、LRC、SRT 时间轴字幕，以及同名 TXT 台本、图片与 PDF 附赠资料。
- **主副双字幕与偏移**：支持同时开启主/副两条字幕（例如中日双语对照），支持单曲独立字幕选择与 ±10 秒时间轴微调。
- **多编码自动识别**：自动识别 UTF-8、UTF-16（LE/BE）、Shift_JIS（Windows-31J）与 GB18030，并支持随时手动切换编码。
- **舒适阅读交互**：固定阅读锚点、手动翻阅后一键恢复跟随、点击字幕跳转播放、直接从字幕行创建书签。

### 音视频一体化
- 支持本地、NAS 与 ASMR.ONE 视频直链，音频与视频共用播放队列、断点进度和离线下载。
- 支持画中画（PiP）、按画面比例自动切换横竖屏的全屏播放、只听声音模式（后台及只听模式自动关闭视频轨道省电）。
- 全屏手势支持水平拖动寻址、左右侧滑调节亮度/音量、长按 2x 倍速播放，全屏期间双字幕保持可见。

### 离线管理、隐私与视觉体验
- **离线与缓存**：支持整部作品或单曲离线下载（移除在线/NAS 来源后已下载副本仍可独立播放）；在线播放缓存上限可调（64 / 128 / 256 / 512 MiB），封面与图片由独立 Coil 3 缓存与永久离线目录管理。
- **隐私保护**：支持隐藏系统媒体通知封面与标题（仅显示「正在收听」）、隐藏最近任务多任务预览（Android 13+）；所有来源凭据与账号会话均由 Android Keystore（AES-GCM）加密存储在本机。
- **视觉与动效**：支持 OLED 深夜纯黑背景、触觉反馈开关、共享封面转场、预测性返回手势与减少动态效果模式。

---

## 支持的格式与来源

| 类别 | 支持范围 |
| --- | --- |
| **音频格式** | `MP3`、`M4A`、`FLAC`、`WAV`、`OGG`、`OPUS`、`AAC`（实际解码能力取决于设备） |
| **视频容器** | `MP4`、`M4V`、`MKV`、`WebM`、`MOV`、`3GP`、`TS`、`M2TS`、`MPG`、`MPEG` |
| **字幕与资料** | 字幕：`VTT`、`LRC`、`SRT`；台本/资料：`TXT`、`JPG`、`JPEG`、`PNG`、`WebP`、`PDF` |
| **来源类型** | 本地目录（SAF）、SMB 2/3、NFS 3 / 4.0 / 4.2、HTTPS WebDAV、DLsite 已购库、ASMR.ONE |

---

## 来源配置与目录规范

### 目录与文件识别规则

Whisp 同时支持普通作品目录和带 `RJ` 编号的作品目录。以下路径与编号均为合成示例：

```text
示例系列/
  RJ00000001 测试作品/
    音频/
      01_示例音轨.mp3
      01_示例音轨.mp3.vtt        # 双扩展名或同名 .vtt / .lrc / .srt 均可自动匹配
      01_示例音轨.zh.vtt         # 带语言后缀的多语字幕可作为双字幕候选
    台本/
      01_示例音轨.txt
    特典/
      封面.png                   # 含「封面 / cover / folder」等关键词的图片优先作为封面
```

- 同编号的多层包装目录会自动折叠为同一部作品；不同编号的嵌套作品会分别独立识别。
- 目录名含「附赠 / 特典 / おまけ / omake / bonus」的音轨与文本会自动归类为附赠分组。

### 网络来源配置要点

| 来源 | 地址格式示例 | 配置说明 |
| --- | --- | --- |
| **SMB 2/3** | `smb://nas.example/media/Audio/` | 支持自定义端口；账号支持 `用户名` 或 `域\用户名`；默认强制加密，兼容老旧 NAS 时可在添加页勾选「允许未加密的局域网共享」。 |
| **NFS** | `nfs://nas.example/media/Audio` | 支持 NFS `3`、`4.0`、`4.2`，可配置 UID/GID（默认 `65534:65534`）；使用 TCP 与 `AUTH_SYS` 只读挂载，NAS 导出规则需覆盖设备地址并允许非特权客户端端口。 |
| **WebDAV** | `https://dav.example/media/Audio/` | 远程服务强制使用 HTTPS，采用 Basic 认证；仅跟随同来源根目录内的安全重定向。 |
| **DLsite** | 在「来源 → 添加来源 → DLsite」登录 | 打开官方登录页使用 viviON ID 登录后同步已购库（支持社交登录用户在官方账号页设置的登录 ID 与专用密码）。已购页提供「导出诊断」，包含最近 64 次账号请求的固定阶段、HTTP 状态码与耗时（不含凭据或作品正文）。 |
| **ASMR.ONE** | 在「来源」添加公开目录 | 支持作品名、声优、社团、标签、RJ 编号与官方链接检索；支持刷新已加入作品的链接与 DLsite 官方元数据，刷新时保留用户手动编辑过的标题、声优与封面。 |

---

## 从源码构建

### 环境要求

| 工具 | 固定版本 | 说明 |
| --- | --- | --- |
| **JDK** | 21 | 编译目标为 Java 17，Gradle 运行需 JDK 21 |
| **Gradle** | 9.8.1 | 已由 `./gradlew` 锁定并校验 SHA-256 |
| **Android SDK** | Platform 37（`compileSdk = 37`, `targetSdk = 36`, `minSdk = 26`） | 通过 `ANDROID_HOME` 或本机 `local.properties` 配置 |
| **Build Tools** | 36.0.0 | 通过 Android SDK Manager 安装 |
| **Android NDK** | `30.0.16248370` | 用于编译 `libnfs.so` 与 `libwhisp_nfs.so` |
| **CMake** | `3.31.6` | 可使用 SDK 内 `cmake;3.31.6` 或在 `local.properties` 中通过 `cmake.dir` 指定同版本系统路径 |

Debian / Ubuntu 上可安装基础工具：

```sh
sudo apt-get install openjdk-21-jdk-headless adb cmake ninja-build clang-format-19
```

> 构建原生 NFS 库时，CMake 默认按固定提交和 SHA-256 下载 `libnfs 8.0.0` 源码；离线重建方式见 [原生依赖说明](docs/NATIVE.md)。

### 常用构建命令

```sh
# 1. 构建 Debug 包、运行单元测试与 Debug Lint
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

# 2. 日常开发仅构建单一架构（显著加快原生编译速度，支持逗号分隔多架构）
./gradlew -Pabis=arm64-v8a :app:assembleDebug

# 3. 配合 ccache 加速原生重复编译
./gradlew -Pccache -Pabis=arm64-v8a :app:assembleDebug

# 4. 构建发布版（启用 R8 与资源裁剪；-PsplitApks 生成 4 个分架构 APK + 通用 APK）
./gradlew -PsplitApks :app:assembleRelease :app:lintRelease
```

产物位置：
- Debug APK：`app/build/outputs/apk/debug/`
- Release APK：`app/build/outputs/apk/release/`（默认生成未签名包；在本机创建 Git 排除的 `.signing/signing.properties` 后自动签名，详见 [版本发布指南](docs/RELEASING.md)）

### 工程检查与公开内容检查

```sh
./gradlew fmt                        # 统一格式化 Kotlin / Gradle / Python / C++ / 文本文件
./gradlew qualityCheck               # 格式检查 + 公开内容检查 + 主机 JNI 消毒器测试 + 单元测试 + Debug/Release Lint
python3 tools/check-public-content.py # 单独执行公开仓库敏感信息扫描
```

---

## 文档索引

| 文档 | 内容说明 |
| --- | --- |
| [架构与设计](docs/ARCHITECTURE.md) | 模块分层、`AppGraph` 依赖图、Room v6 数据模型、音频/播放引擎与来源协议实现细节 |
| [工程质量与构建](docs/QUALITY.md) | 代码格式规范、`qualityCheck` 门禁、缓存与构建加速、公开内容检查及 CI 配置 |
| [测试指南](docs/TESTING.md) | 单元测试、主机 JNI ASan/UBSan 测试、合成样本生成、隔离设备测试（`smoke`）与真实 NAS 合同测试 |
| [原生依赖（libnfs）](docs/NATIVE.md) | `libnfs 8.0.0` 固定源码与校验、JNI 桥接架构、离线重建步骤与 LGPL/BSD 许可说明 |
| [版本发布指南](docs/RELEASING.md) | RC / 正式版命名与版本号规则、本地签名配置、发布构建、配套源码打包与审批流程 |

---

## 安全与隐私说明

- **本地数据归属**：应用数据、播放进度、书签与下载副本仅保存在当前设备（已禁用系统云备份 `allowBackup="false"`）。
- **凭据加密**：SMB、WebDAV 密码与 DLsite 会话 Cookie 经 Android Keystore（AES-GCM）加密保存，不写入音轨或媒体 URI。
- **只读与最小权限**：SMB 与 NFS 客户端仅执行只读挂载与只读文件读取；远程 WebDAV 强制 HTTPS 并拦截 DTD/XXE 与跨源重定向；导出的媒体服务通过 `MediaLibrarySession.onConnect` 校验控制器身份。
- **播放进度可靠性**：播放进度默认定期每 15 秒保存，在暂停、切曲、手动跳转与睡眠定时结束时立即提交（异常强杀进程最多可能丢失最近约 15 秒进度）。

---

## 许可证

Whisp 项目源码采用 [GNU Lesser General Public License v3.0 或更新版本](LICENSE)（SPDX：`LGPL-3.0-or-later`）。

- 版权与第三方声明见 [NOTICE](NOTICE)
- LGPL v3 引用的 GPL v3 正文见 [COPYING](COPYING)
- 原生 NFS 依赖 `libnfs 8.0.0` 的上游许可（LGPL-2.1-or-later / BSD）与配套源码重建说明见 [docs/NATIVE.md](docs/NATIVE.md)
- Gradle Wrapper 与其余第三方依赖各自保留其原有许可证
