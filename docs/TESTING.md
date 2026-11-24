# 测试指南

Whisp 的自动化测试遵循「公开数据全部合成、设备测试默认隔离、外部实网探针显式开启」原则。

---

## 测试分层一览

| 测试层级 | 运行命令 | 运行环境 | 说明 |
| --- | --- | --- | --- |
| **主机 JNI 消毒器测试** | `./gradlew nativeHostTest` | 开发机 / CI | 以 stub 头文件编译 `nfs_bridge_test.cpp`，开启 ASan + UBSan 校验边界与内存安全 |
| **JVM 单元测试** | `./gradlew :app:testDebugUnitTest` | 开发机 / CI | 并行多进程执行；覆盖索引、ID、字幕、编码、队列、DSP、进度、循环、各客户端解析等 |
| **真实 Samba 合同测试（JVM）** | `WHISP_SMB_TEST_CONFIG=... ./gradlew :app:testDebugUnitTest --tests app.whisp.data.SmbServerTest` | 开发机 + 专用只读 Samba | 验证真实 SMB 鉴权、扫描、64 位大偏移读取与连接复用 |
| **隔离设备测试（`smoke`）** | `./gradlew -PsmokeTest :app:assembleSmoke :app:assembleSmokeAndroidTest` + `adb shell am instrument` | Android 真机 / 模拟器 | 安装为独立包名 `app.whisp.smoketest`，保留设备已装的正式版及用户作品库 |
| **Debug 设备测试** | `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` + `adb shell am instrument` | 专用测试设备 / 模拟器 | 覆盖本机 WebDAV 服务与系统 SAF 目录选择器 |
| **DLsite 实时连接探针** | 见下文探针参数 | 隔离测试包 + 网络 | 默认跳过，按需传入 `probeRj` 或 `probeDlsiteAccount` 诊断线上协议兼容性 |

---

## 主机端测试

```sh
# 仅运行主机 C++ JNI 桥接测试（ASan + UBSan）
./gradlew nativeHostTest

# 运行全部 JVM 单元测试与 Debug Lint
./gradlew :app:testDebugUnitTest :app:lintDebug

# 运行单个测试类
./gradlew :app:testDebugUnitTest --tests app.whisp.data.LibraryIndexerTest
```

JVM 单元测试默认覆盖：
- 目录索引、重复包装目录折叠、嵌套作品识别与稳定 ID 连续性（`LibraryIndexerTest`、`StableIdTest`、`TrackSectionsTest`）
- 字幕解析（WebVTT 注释/样式/重叠、LRC 多时间标签与偏移、SRT）、多编码检测与阅读跟随（`SubtitleTimelineTest`、`TextEncodingTest`、`SubtitleFollowTest`）
- 播放队列、精细拖动状态机、防抖进度写入、A–B 循环、音频卸载与夜间 DSP（`PlaybackQueueTest`、`PrecisionScrubberTest`、`ProgressWriterTest`、`LoopStateTest`、`AudioOffloadTest`、`PlaybackExperienceOptimizationTest`）
- 来源协议与元数据客户端（`SmbAddressTest`、`SmbDataSourceTest`、`NfsAddressTest`、`NfsClientTest`、`WebDavClientTest`、`AsmrOneClientTest`、`AsmrSearchTest`、`DlsiteClientTest`、`DlsiteDetailsTest`、`DlsiteAccountClientTest`、`DlsiteFileTreeTest`、`DlsiteDataSourceTest`）
- 性能与发布前回归（`PerformanceOptimizationTest`、`PreReleaseRegressionTest`）

---

## 生成合成媒体样本

部分设备测试与真实 Samba 合同测试需要本地生成的静音媒体样本（不会读取任何个人媒体库）。输出目录必须位于 Git 忽略的 `fixtures/` 或仓库外部，且每次需使用新目录：

```sh
sudo apt-get install -y ffmpeg

# 1. 生成常规测试样本（约 31 分钟 / 71 MiB 静音 MP3 + 纯色封面 + 台本，用于长跳转与 40 MB 偏移校验）
python3 tools/prepare-device-fixture.py --output fixtures/device

# 2. 为真实 SMB 偏移测试额外生成超过 4 GiB 的稀疏文件
python3 tools/prepare-device-fixture.py --output fixtures/samba --smb
```

> 稀疏文件在支持稀疏存储的文件系统上仅占用极少实际空间；复制到不支持稀疏存储的介质时可能分配完整容量。

仓库内置的两个合成玫瑰色静音视频样本（`app/src/androidTest/assets/video-example.mp4` 与 `video-portrait-example.mp4`，各 40 秒 H.264/AAC）由以下 FFmpeg 命令生成，并由公开内容检查脚本校验固定 SHA-256：

```sh
ffmpeg -f lavfi -i 'color=c=0xEE9EBE:s=320x180:r=12' -f lavfi -i 'anullsrc=channel_layout=stereo:sample_rate=44100' -t 40 -c:v libx264 -preset veryfast -crf 30 -pix_fmt yuv420p -c:a aac -b:a 32k -movflags +faststart -metadata title='Whisp synthetic video fixture' app/src/androidTest/assets/video-example.mp4
ffmpeg -f lavfi -i 'color=c=0xEE9EBE:s=180x320:r=12' -f lavfi -i 'anullsrc=channel_layout=stereo:sample_rate=44100' -t 40 -c:v libx264 -preset veryfast -crf 30 -pix_fmt yuv420p -c:a aac -b:a 32k -movflags +faststart -metadata title='Whisp synthetic portrait video fixture' app/src/androidTest/assets/video-portrait-example.mp4
```

---

## 隔离设备测试（`smoke` 构建）

为了在不影响设备上正式版 `app.whisp` 及其用户数据的前提下验证近似 Release（开启 R8）的运行行为，设备集成测试默认使用 `-PsmokeTest` 构建独立的 `app.whisp.smoketest` 包。

### 构建与安装

```sh
# 可按需附加 -Pabis=arm64-v8a 或 -Pabis=x86_64 加快编译
./gradlew -PsmokeTest :app:assembleSmoke :app:assembleSmokeAndroidTest
adb install -r app/build/outputs/apk/smoke/app-smoke.apk
adb install -r app/build/outputs/apk/androidTest/smoke/app-smoke-androidTest.apk
```

运行设备测试时请确保设备处于解锁状态；每次测试前建议清理 `app.whisp.smoketest` 数据：

```sh
adb shell pm clear app.whisp.smoketest
adb shell am instrument -w \
  -e class app.whisp.PerformanceIntegrationTest \
  app.whisp.smoketest.test/androidx.test.runner.AndroidJUnitRunner
```

### 自包含测试套件（无需外部服务器）

以下测试完全使用设备内合成样本或本机 MockWebServer，可直接在任意真机或模拟器上运行：

| 测试类 | 验证重点 |
| --- | --- |
| `PerformanceIntegrationTest` | 列表滚动、首屏渲染与资源占用基线 |
| `SystemMediaIntegrationTest` | 系统媒体通知、媒体按钮、隐私遮罩与系统续播 |
| `SystemUiIntegrationTest` | 系统栏边到边显示与窗口隐私标志 |
| `MotionNavigationIntegrationTest` | 共享封面转场、返回手势与减少动态效果 |
| `StorageIntegrationTest` | 缓存统计、上限调整与离线存储清理 |
| `VideoPlaybackIntegrationTest` | 视频帧像素渲染、SRT 字幕、进度定位、全屏旋转、只听模式、后台播放、混合队列与离线画面 |
| `AsmrOneIntegrationTest` | 在线目录分页、搜索、按需入库、刷新身份保留、字幕、下载、离线播放与 NAS 凭据隔离 |
| `DlsiteLibraryIntegrationTest` | 已购作品检索、详情与删除、退出账号清理购买库、诊断报告导出字段与容量上限 |
| `DlsiteLoginLifecycleIntegrationTest` | 官方登录页配置变更与生命周期重建 |
| `DlsiteScanLifecycleIntegrationTest` | 重新登录与扫描并发交错时的会话归属、提交一致性与忙碌状态 |
| `LibraryMigrationIntegrationTest` | 旧版数据库升级至 v6 时保留标题、收藏、进度、书签与离线状态 |
| `RepositoryMutationIntegrationTest` | 扫描刷新、作品编辑、离线删除、共享封面与下载清理之间的并发安全性 |
| `PlaybackRegressionIntegrationTest` | 续播恢复、曲末 A–B 循环与部分离线队列 |
| `PlaybackSubtitleRetryIntegrationTest` / `PlayerStateUiIntegrationTest` | 字幕加载重试恢复与播放器真实队列状态同步 |
| `SourceNavigationIntegrationTest` | 来源页面导航、表单编辑草稿保留 |

---

## 真实网络存储合同测试

### Samba（SMB 2/3）

1. 将 `prepare-device-fixture.py --output fixtures/samba --smb` 生成的样本放入专用只读测试共享。
2. 在 Git 排除的本地路径（如 `.signing/smb-test.properties`）写入测试配置：

| 字段 | 说明 |
| --- | --- |
| `root` | 测试 SMB 来源地址（如 `smb://nas.example/test-share/`） |
| `username` | 测试账号（支持 `用户名` 或 `域\用户名`） |
| `password` | 测试密码 |
| `fixture` | 同一生成样本在测试主机上的本地目录路径 |

3. 运行 JVM 合同测试（覆盖鉴权、扫描、随机读取、EOF、> 4 GiB 偏移、MP3 时长、会话复用与错误密码）：

```sh
export WHISP_SMB_TEST_CONFIG="$PWD/.signing/smb-test.properties"
./gradlew :app:testDebugUnitTest --tests app.whisp.data.SmbServerTest
```

4. 设备端 SMB 集成测试（`SmbIntegrationTest`、`PlaybackFlowIntegrationTest`、`StorageAudioIntegrationTest`）运行在 `app.whisp.smoketest`，通过 instrumentation 参数传入专用共享信息：

```sh
adb shell am instrument -w \
  -e class app.whisp.SmbIntegrationTest \
  -e smbRoot "smb://nas.example/test-share/" \
  -e smbUser "testuser" \
  -e smbPassword "testpass" \
  app.whisp.smoketest.test/androidx.test.runner.AndroidJUnitRunner
```

### NFS（3 / 4.0 / 4.2）

`NfsIntegrationTest` 与 `NfsSourceUiIntegrationTest` 运行在 `app.whisp.smoketest`，通过 `-e nfsRoot` 指定专用合成 NFS 导出目录（需包含带保留字符与 emoji 的 WAV、VTT、封面、目录、循环符号链接以及超过 4 GiB 的稀疏文件）。测试覆盖 NFS 3 / 4.0 / 4.2 三种协议的随机读、扫描、字幕、播放、跳转、离线下载、来源移除与配置页重建：

```sh
adb shell am instrument -w \
  -e class app.whisp.NfsIntegrationTest \
  -e nfsRoot "nfs://nas.example/export/whisp-fixture" \
  app.whisp.smoketest.test/androidx.test.runner.AndroidJUnitRunner
```

测试完成后请及时清理专用测试导出目录。

---

## Debug 设备测试（WebDAV 与本地目录选择器）

`PlaybackIntegrationTest`（本机 WebDAV 服务）与 `LocalImportIntegrationTest`（系统 SAF 目录授权）在专用测试设备上使用 Debug 包运行。Debug 与 `smoke` 变体的网络安全配置仅对 `localhost` / `10.0.2.2` 测试地址放行明文 HTTP，远程 WebDAV 始终要求 HTTPS。

> `LocalImportIntegrationTest` 会在设备创建 `Download/WhispFixture` 并依赖系统文件选择器的英文按钮文案。

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
python3 tools/prepare-device-fixture.py --output fixtures/device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

adb push fixtures/device /data/local/tmp/whisp-fixture
adb shell run-as app.whisp mkdir -p files/fixture
adb shell run-as app.whisp cp -r /data/local/tmp/whisp-fixture/. files/fixture/

adb shell am instrument -w \
  -e class app.whisp.PlaybackIntegrationTest \
  app.whisp.test/androidx.test.runner.AndroidJUnitRunner
```

---

## 可选的 DLsite 真机实时探针

以下两项测试在常规套件中自动跳过，仅在手动排查线上协议变动时通过参数显式启用（使用 `app.whisp.smoketest`）：

1. **公开元数据与目录探针（`DlsiteLiveIntegrationTest`）**：设置本机环境变量 `WHISP_DLSITE_TEST_RJ` 为待检 RJ 编号：

```sh
adb shell am instrument --user 0 -w \
  -e class app.whisp.DlsiteLiveIntegrationTest \
  -e probeRj "$WHISP_DLSITE_TEST_RJ" \
  -e probeDetails true \
  -e probeAsmr true \
  app.whisp.smoketest.test/androidx.test.runner.AndroidJUnitRunner
```

- `-e probeDetails true`（可选）：校验创作者、标签、介绍、图片与精确评分。
- `-e probeAsmr true`（可选）：同时校验 ASMR.ONE 在线作品信息、曲目索引与字幕关联。
- 诊断仅输出阶段、HTTP 状态、耗时与异常类型，作品内容仅保留在进程内存中。

2. **已购账号链路探针（`DlsiteAccountLiveIntegrationTest`）**：在隔离测试包的官方登录页完成登录后，传入 `-e probeDlsiteAccount true` 验证已购列表、附带字幕、原生播放、暂停、跳转、下载及退出账号后的离线播放；结束后自动清理测试作品与账号会话。

---

## 提交前检查清单

1. 执行 `./gradlew fmt` 与 `./gradlew qualityCheck` 且全部通过。
2. 如改动涉及数据库迁移、播放器状态、UI 交互或网络传输，在真机/模拟器上运行对应的 `smoke` 隔离集成测试。
3. 执行公开内容扫描与暂存区审阅：

```sh
python3 tools/check-public-content.py
git diff --cached --check
git diff --cached
```

> 仅提交项目源码、构建配置、文档和合成测试数据；调试日志、设计稿、签名密钥、设备备份与个人媒体文件保存在本机。
