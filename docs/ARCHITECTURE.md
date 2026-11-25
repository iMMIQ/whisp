# 架构与设计

本文档面向项目贡献者与维护者，说明 Whisp 的模块分层、数据模型、播放管线与各来源协议的实现边界。

---

## 总体分层与依赖图

Whisp 在应用入口 [WhispApp.kt](../app/src/main/java/app/whisp/WhispApp.kt) 中通过扁平的 `AppGraph` 组织所有核心依赖，形成无环依赖图（DAG），避免单体 Repository 或 God ViewModel：

```mermaid
flowchart TD
    UI["Compose UI + Navigation 3 (app.whisp.ui)"]
    PB["PlaybackCoordinator / PlaybackService (app.whisp.playback)"]
    SYNC["LibrarySync / ScanWorker"]
    LIB["LibraryStore"]
    OFF["OfflineStore"]
    IMG["WhispImages (Coil 3)"]
    ROUTE["MediaSourceRouting (Media3 Cache / DownloadManager)"]
    SRC["SourceRegistry"]
    DB["Room LibraryDatabase (v1 + FTS4)"]
    SET["SettingsStore (Typed DataStore) & SecretStore (Keystore)"]
    PROTO["Local SAF / SMBJ / libnfs JNI / WebDAV / ASMR.ONE / DLsite"]

    UI --> PB
    UI --> LIB
    UI --> SYNC
    UI --> OFF
    UI --> IMG
    UI --> SET
    PB --> LIB
    PB --> IMG
    PB --> SET
    PB --> ROUTE
    SYNC --> LIB
    SYNC --> SRC
    SYNC --> OFF
    SYNC --> IMG
    OFF --> LIB
    OFF --> ROUTE
    OFF --> IMG
    LIB --> DB
    LIB --> SRC
    LIB --> ROUTE
    IMG --> DB
    IMG --> SRC
    ROUTE --> SRC
    SRC --> SET
    SRC --> PROTO
```

### 核心包与职责

| 模块 / 包 | 代表文件 | 主要职责 |
| --- | --- | --- |
| `:core:model` | `MediaFormats.kt`, `TextEncoding.kt`, `DlsiteDetails.kt`, `SmbAddress.kt`, `NfsAddress.kt`, `AudioDeviceMonitor.kt`, `ShakeDetector.kt` | 纯领域模型、格式与编码解码、网络路径编解码与音频路由/传感器状态机 |
| `:core:native` | `CMakeLists.txt`, `nfs_bridge.cpp`, `smb_bridge.cpp`, `dsp_bridge.cpp`, `NfsNative.kt`, `SmbNative.kt`, `DspNative.kt` | `libnfs 8.0.0`、`libsmb2 7.0.0` 与 ARM64 NEON 音频 DSP 原生库、无 STL JNI 桥接与 ASan/UBSan 主机测试 |
| `:app`（入口） | `WhispApp.kt`, `MainActivity.kt`, `DlsiteLoginActivity.kt` | 懒加载 `AppGraph` 依赖装配、画中画（PiP）、后台多任务隐私保护、DLsite 官方登录容器 |
| `:app`（数据与传输） | `LibraryDatabase.kt`, `LibraryStore.kt`, `LibrarySync.kt`, `SmbClient.kt`, `NfsClient.kt`, `MediaSourceRouting.kt` | Room v1（FTS4 + Paging 3 + `track_state`）、类型化 DataStore、原生 SMB/NFS 连接池与离线存储 |
| `:app`（播放） | `PlaybackService.kt`, `PlaybackCoordinator.kt`, `PlaybackControllers.kt`, `WhispAudioProcessor.kt`, `OfflineService.kt` | `MediaLibraryService`、ExoPlayer 生命周期、原生 NEON DSP、硬件卸载、A–B 循环、睡眠定时与系统续播 |
| `:app`（界面） | `WhispRoot.kt`, `LibraryPage.kt`, `DetailPage.kt`, `PlayerPage.kt`, `PlayerControls.kt`, `PlayerSubtitlePane.kt`, `WhispIcons.kt` | Jetpack Compose 界面、Paging 3 分页网格、Navigation 3、本地精简矢量图标与共享元素转场 |
| `:benchmark` | `BaselineProfileGenerator.kt`, `StartupBenchmark.kt` | AndroidX Macrobenchmark 冷启动基准测试与 Baseline Profile 自动采集模块 |

---

## 数据存储与身份模型

### Room 数据库（Schema v1，FTS4 + 单曲状态表）

数据库定义位于 [LibraryDatabase.kt](../app/src/main/java/app/whisp/data/LibraryDatabase.kt)，导出 Schema 存放在 `app/schemas/app.whisp.data.LibraryDatabase/`。

| 表名 | 用途与关系 |
| --- | --- |
| `sources` | 来源配置（`local` / `smb` / `nfs` / `webdav` / `asmrone` / `dlsite`）、加密凭据密文、上次扫描时间与错误信息 |
| `works` / `works_fts` | 作品元数据与 Room FTS4 倒排索引表（`sourceId` ON DELETE SET NULL） |
| `tracks` / `tracks_fts` | 音视频轨条目与 FTS4 索引表（复合索引 `(workId, order, id)`；外键级联删除） |
| `track_state` | 单曲独立播放进度、字幕时间轴偏移与主/副双字幕选择（外键指向 `tracks(id)` 级联删除，无 LRU 条数上限） |
| `attachments` / `attachments_fts` | 字幕、台本、图片与 PDF 附赠资料与 FTS4 索引表（外键级联删除） |
| `progress` | 每部作品的最新播放位置（`workId` 为主键，首页通过 SQL 聚合视图 `WorkProgressSummary` 计算进度占比） |
| `bookmarks` | 作品片段书签，支持时间点书签与带 `endMs` 的区间书签（外键级联删除） |
| `dlsite_metadata` | 按作品缓存的 DLsite 结构化介绍、创作者分工、标签与评分快照（外键级联删除） |
| `dlsite_purchases` | 按 DLsite 账号 `sourceId` 隔离缓存的已购音声目录（外键级联删除） |

### 稳定身份与状态持久化

- **稳定 ID**：统一通过 `stableId()` 对标识串做 Okio SHA-256 后取前 128 位十六进制编码。本地/NAS 作品按 `$sourceId|$root` 生成，确保增删单条音轨时不破坏作品身份、收藏、书签和进度；同时记录 `originalTitle` / `originalArtist`，来源刷新时自动保留用户手动编辑过的标题、声优与自定义封面。
- **全局设置（`SettingsStore`）**：基于类型化 `DataStore<AppPreferences>`（直接序列化 JSON，去除 Preferences Protobuf 双层包装与历史旧键回退），保存全局开关、按耳机设备听感配置（最多 24 条）与 ASMR.ONE 搜索历史（最多 20 条）；单曲进度、字幕偏移与双字幕选择全部存储在 Room `track_state` 表中随音轨级联清理。
- **进度写入防抖（`ProgressWriter`）**：按 `workId` 保留待落盘队列并合并 150 ms 内的连续事件（如快速拖动/切曲）；暂停、退出与定时停止调用 `saveImmediate()` 立即落盘；写入失败时自动退避重试。

### 缓存与离线存储体系

- **在线播放流式缓存**：Media3 `SimpleCache` 搭配自定义 `AdjustableCacheEvictor`（仅使用 `Cache` 公开 API 与独立锁维护 LRU，支持运行时动态调整 64 / 128 / 256 / 512 MiB 上限）。
- **离线下载**：Media3 `DownloadManager` + `OfflineService` 管理音视频离线缓存；字幕、台本与封面等附赠资产由 `OfflineStore` 原子写入应用私有目录 `files/offline/assets/`。
- **图片缓存**：Coil 3 统一承担内存与磁盘封面缓存，结合永久离线资产目录与自定义封面目录 `files/custom-artwork/`。

---

## 播放引擎与音频管线

### 播放服务与音频处理

- **服务架构**：`PlaybackService` 继承 Media3 `MediaLibraryService`，在 `onConnect` 中校验外部控制器身份，向系统媒体控件与锁屏暴露播放控制、可配置步长的快进/后退按钮及最近收听续播目录。
- **低开销 PCM 音频处理器（`WhispAudioProcessor`）**：
  - 支持 16-bit PCM 与 Float PCM；所有开关均使用 `@Volatile`，切换实时生效于下一音频帧而无需重建播放器。
  - 仅开启左右声道互换或单声道合并时，走 16-bit 整数无损快路径，避免浮点往返。
  - 开启夜间防炸耳柔化时，对轻声施加约 +3 dB 增益补偿，对高于 -16 dBFS 的突发峰值以 4:1 压缩比（4 ms 快速起音 / 180 ms 平滑释放）与约 -2.5 dBFS 硬顶配合软削波膝点限制。
- **硬件 DSP 音频卸载（`AudioOffload`）**：Android 10（API 29）及以上默认启用硬件卸载以节省夜间电量；仅当 A–B 循环激活、睡眠定时需要 AP 精确调度或音频 DSP 效果开启时自动关闭卸载。开启「连续音轨无缝衔接」或变速播放时，按需向音轨选择参数声明无缝/变速硬件支持要求，设备不支持时自动回退软件路径。

### 缓冲、重试与睡眠定时

- **突发友好缓冲（`WhispLoadControl`）**：流式播放采用 25s–180s 宽滞回窗口（本地播放 15s–60s），内存上限固定 32 MiB，便于音频成批拉取后让 Wi-Fi / 蜂窝射频进入休眠，同时避免视频占用过多内存。
- **智能重试（`WhispLoadErrorHandlingPolicy`）**：对瞬时网络波动提供最多 5 次退避重试；遇到 `DlsiteLoginRequired`、`FileNotFoundException` 或 HTTP 401/403/404/410 等不可恢复错误时立即快速失败，并通过 `PlaybackErrorFormatter` 转换为面向用户的可执行提示。
- **睡眠定时（`SleepTimerController`）**：优先使用 `AlarmManager.setAndAllowWhileIdle` 在 Doze 模式下按单调时钟唤醒（保持主等待期间硬件音频卸载）；淡出窗口内申请短时 `PARTIAL_WAKE_LOCK` 按二次感知曲线平滑衰减音量，到期暂停后延迟恢复音量基准至下次播放，避免尾音爆破。

### 字幕与视频

- **字幕解析**：WebVTT 复用 Media3 `WebvttParser`（完整支持样式、注释与重叠字幕），LRC 自研支持多时间标签与 `[offset:]` 偏移，SRT 规范解析时间戳与多行文本；时间轴查找使用 Kotlin 标准库 `binarySearch`，字幕内容由 `LruCache` 缓存。
- **多编码解码（`TextEncoding`）**：按「显式 BOM（UTF-8 / UTF-16LE / UTF-16BE）→ 严格 UTF-8 校验 → 日文（Windows-31J）与中文（GB18030）字符分布打分」顺序自动判定，并支持手动强制指定编码。
- **视频渲染**：通过 Media3 `PlayerSurface` 与 Compose `ContentScale` 渲染，进入后台或开启只听模式时禁用视频轨道；视频活跃播放期间自动配置画中画（PiP）宽高比与自动进入参数（Android 12+）。

---

## 来源协议与外部接口边界

| 来源 | 核心实现 | 关键设计约束 |
| --- | --- | --- |
| **本地目录** | `LocalDirectory` | 基于 SAF `DocumentsContract`，持久化树 URI 授权，单来源索引上限 100,000 条目 |
| **SMB 2/3** | `SmbClient`（SMBJ） | 只读访问，默认强制 SMB 加密，可选局域网兼容开关；与 NFS 共用 `RemoteFileDataSource` 实现 64 位偏移随机读与 EOF 处理；播放空闲 30 秒后自动关闭空闲连接池 |
| **NFS** | `NfsClient` + `libwhisp_nfs.so` | 支持 NFS 3 / 4.0 / 4.2（TCP + `AUTH_SYS`），扫描最多 4 并发连接，读取采用 256 KiB 缓冲；详见 [原生依赖](NATIVE.md) |
| **WebDAV** | `WebDavClient`（OkHttp + SAX） | 远程强制 HTTPS；SAX 解析禁止 DTD/外部实体防止 XXE；限制最多 6 次同来源根目录内重定向 |
| **ASMR.ONE** | `AsmrOneClient` + `AsmrCatalogController` | 独立网络配置与站点 Referer；按作用域缓存分页状态（最多 6 个 LRU 作用域）；媒体使用 `whisp-asmr` 内部 URI，按需入库，不污染未打开作品的本地数据库 |
| **DLsite（元数据与账号）** | `DlsiteClient` + `DlsiteAccountClient` + `DlsiteDiagnostics` | 元数据优先请求官方完整 JSON 接口并兼容 AJAX 键值接口，Jsoup 提取结构化介绍与图片；账号会话使用 Android Keystore 加密并严格校验受信任域名；`DlsiteDiagnostics` 仅在内存环形缓冲中保留最近 64 次请求的固定阶段/状态码/耗时，防抖落盘供用户手动导出排查 |

---

## 技术栈与依赖管理

所有直接依赖版本统一维护在 [gradle/libs.versions.toml](../gradle/libs.versions.toml)，此处仅记录关键框架选型与约束：

- **UI 与导航**：Jetpack Compose + Material 3（使用 `1.5.0-beta01` 公开的 `MotionScheme` 动效接口）+ Navigation 3（每个顶层标签页独立维护返回栈与滚动状态）+ Coil 3。
- **媒体与后台**：AndroidX Media3（ExoPlayer、Session、OkHttp DataSource、UI Compose）+ Paging 3 + WorkManager + Room + 类型化 DataStore + ProfileInstaller / Macrobenchmark。
- **网络与协议**：OkHttp 5 + Okio + kotlinx.serialization + Jsoup + `libsmb2 7.0.0` + `libnfs 8.0.0`（无 SMBJ / BouncyCastle 依赖）。
- **SDK 策略**：`compileSdk = 37`，`targetSdk = 36`，`minSdk = 28`，仅支持 64 位 ABI（`arm64-v8a` / `x86_64`），构建期开启 Kotlin `allWarningsAsErrors = true`。
