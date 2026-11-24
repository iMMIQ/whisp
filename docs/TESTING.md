# 测试

公开测试数据使用示例名称、编号、地址和账号，媒体样本由脚本生成，网络测试使用专用共享。

## 单元测试

格式、测试与 Lint 的统一入口见 [工程质量](QUALITY.md)。

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
```

默认测试覆盖索引、包装目录、跨来源身份、录音版本、字幕匹配、播放队列、精确定位、进度写入、循环状态、缓存、WebDAV、NFS、DLsite 与性能优化。配置专用共享后，可通过 `WHISP_SMB_TEST_CONFIG` 启用真实 Samba 合同测试。

## 生成媒体样本

样本脚本使用 FFmpeg 生成约 31 分钟的静音 MP3，并生成纯色封面、字幕和文本。MP3 约 71 MiB，用于长距离跳转和 40 MB 偏移读取。

```sh
sudo apt-get install ffmpeg
python3 tools/prepare-device-fixture.py --output fixtures/device
```

每次生成使用新的输出目录，已有样本保留在原目录。`fixtures/` 由 Git 排除。

SMB 偏移测试另需一个超过 4 GiB 的稀疏文件：

```sh
python3 tools/prepare-device-fixture.py --output fixtures/samba --smb
```

稀疏文件在支持稀疏存储的文件系统上仅占少量磁盘空间；复制到其他文件系统可能分配完整容量。

## 真实 Samba 合同测试

将生成的 Samba 样本放在专用只读测试共享中。配置保存在 Git 排除的本地文件，例如 `.signing/smb-test.properties`，字段为：

| 字段 | 用途 |
| --- | --- |
| `root` | 测试 SMB 来源地址 |
| `username` | 测试账号，可包含域 |
| `password` | 测试账号密码 |
| `fixture` | 同一生成样本在测试主机上的目录 |

通过环境变量 `WHISP_SMB_TEST_CONFIG` 指向该文件，然后运行 `SmbServerTest`。测试覆盖鉴权、目录扫描、随机读取、EOF、超过 4 GiB 的偏移、MP3 时长索引、复用会话和错误密码处理。配置与服务器日志作为本机测试资料保存。

## NFS 验证

`NfsAddressTest` 与 `NfsClientTest` 使用示例地址和内存目录，验证协议、Unix 身份、Unicode 文件名、来源隔离、扫描并发及连接释放。SMB 与 NFS 共用 `RemoteFileDataSource` 的 EOF、读取范围和 64 位偏移处理。

`NfsIntegrationTest` 与 `NfsSourceUiIntegrationTest` 使用隔离包，通过 instrumentation 参数 `nfsRoot` 指定专用合成共享。样本包含带保留字符与 emoji 的 WAV、VTT、封面、目录及符号链接，以及超过 4 GiB 的稀疏文件。测试覆盖三种协议的随机读取、扫描、字幕、播放、跳转、离线下载和来源移除，以及配置页面的重建。

测试来源应为单独创建的样本目录，服务器地址通过参数传入。稀疏文件的逻辑长度超过 4 GiB，实际磁盘占用由样本写入范围决定；测试完成后清理该专用目录。

## 隔离设备测试

`VideoPlaybackIntegrationTest` 使用 `assets/video-example.mp4` 的合成玫瑰色画面与静音音轨，校验实际渲染像素、SRT、进度定位、全屏与旋转、只听模式、后台播放、页面重建、混合队列，以及来源移除后的离线画面。样本通过 FFmpeg 生成，长度为 40 秒，编码为 H.264/AAC，测试文件包含合成标题标记。

```sh
ffmpeg -f lavfi -i 'color=c=0xEE9EBE:s=320x180:r=12' -f lavfi -i 'anullsrc=channel_layout=stereo:sample_rate=44100' -t 40 -c:v libx264 -preset veryfast -crf 30 -pix_fmt yuv420p -c:a aac -b:a 32k -movflags +faststart -metadata title='Whisp synthetic video fixture' app/src/androidTest/assets/video-example.mp4
ffmpeg -f lavfi -i 'color=c=0xEE9EBE:s=180x320:r=12' -f lavfi -i 'anullsrc=channel_layout=stereo:sample_rate=44100' -t 40 -c:v libx264 -preset veryfast -crf 30 -pix_fmt yuv420p -c:a aac -b:a 32k -movflags +faststart -metadata title='Whisp synthetic portrait video fixture' app/src/androidTest/assets/video-portrait-example.mp4
```

性能、系统媒体、动效和存储测试使用独立的 `app.whisp.smoketest` 包，测试数据由该包管理。

```sh
./gradlew -PsmokeTest :app:assembleSmoke :app:assembleSmokeAndroidTest
adb install -r app/build/outputs/apk/smoke/app-smoke.apk
adb install -r app/build/outputs/apk/androidTest/smoke/app-smoke-androidTest.apk
adb shell am instrument -w -e class app.whisp.PerformanceIntegrationTest app.whisp.smoketest.test/androidx.test.runner.AndroidJUnitRunner
```

每个测试类别使用新的隔离应用数据，清理目标为专用测试设备或模拟器上的 `app.whisp.smoketest`。界面测试在设备解锁、测试应用处于前台时运行。常规构建的通用安装包覆盖四种 ABI，可安装到对应设备或模拟器。

`PerformanceIntegrationTest`、`SystemMediaIntegrationTest`、`MotionNavigationIntegrationTest` 和 `StorageIntegrationTest` 的样本由测试自行生成。`SmbIntegrationTest`、`PlaybackFlowIntegrationTest` 和 `StorageAudioIntegrationTest` 使用专用 SMB 共享，地址与测试凭据通过 instrumentation 参数传入；参数定义见各测试源码。

## ASMR.ONE 来源测试

`AsmrOneIntegrationTest` 使用设备内的 MockWebServer 和生成的立体声静音样本，验证在线目录、搜索、按需入库、刷新后的身份与收藏、字幕、封面、下载及来源移除后的离线播放。它同时验证网络请求与 NAS 凭据的隔离，测试包为 `app.whisp.smoketest`。

## WebDAV 与本地目录选择器测试

这两项测试在专用测试设备上运行，使用 Debug 包和预先推送的生成样本。测试会建立媒体索引，本地目录选择器测试会创建 `Download/WhispFixture`。目录选择器测试使用英文系统按钮文字。

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
python3 tools/prepare-device-fixture.py --output fixtures/device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb push fixtures/device /data/local/tmp/whisp-fixture
adb shell run-as app.whisp mkdir -p files/fixture
adb shell run-as app.whisp cp -r /data/local/tmp/whisp-fixture/. files/fixture/
adb shell am instrument -w -e class app.whisp.PlaybackIntegrationTest app.whisp.test/androidx.test.runner.AndroidJUnitRunner
```

WebDAV 测试在设备内启动带测试鉴权和 Range 的本机服务。网络配置为远程 WebDAV 使用 HTTPS，为 localhost 与 Android 模拟器测试地址提供 HTTP。运行 `LocalImportIntegrationTest` 可验证系统目录授权和持久读取权限；各测试使用独立的专用测试数据。

## DLsite 真机连接诊断

`DlsiteAccountClientTest` 和 `DlsiteFileTreeTest` 使用合成目录与 MockWebServer，覆盖会话隔离、Cookie 路径和有效期、增量购买库、授权更新、字幕关联、目录层级及跨账号身份。

`DlsiteLibraryIntegrationTest` 验证已购作品检索、详情与删除、退出账号后的购买库清理，以及诊断导出的字段和容量。`LibraryMigrationIntegrationTest` 验证已有数据库升级到版本 6 时保留标题、收藏、进度、书签和离线状态。

`DlsiteAccountLiveIntegrationTest` 在隔离包的官方登录页完成登录后，通过 `-e probeDlsiteAccount true` 启用，验证已购作品、附带的时间轴字幕、原生播放、暂停、跳转、音轨下载和退出账号后的离线播放。测试完成后清理该测试作品和账号会话。常规测试套件跳过这项账号验证。

`DlsiteLiveIntegrationTest` 使用隔离测试包读取目标 RJ 作品的官方元数据。通过 `probeRj` 参数启用；常规测试套件将其跳过。设置本机环境变量 `WHISP_DLSITE_TEST_RJ` 后执行：

```sh
adb shell am instrument --user 0 -w -e class app.whisp.DlsiteLiveIntegrationTest -e probeRj "$WHISP_DLSITE_TEST_RJ" app.whisp.smoketest.test/androidx.test.runner.AndroidJUnitRunner
```

添加 `-e probeDetails true` 验证创作者、标签、介绍、图片与精确评分。添加 `-e probeAsmr true` 可同时验证在线作品信息、曲目索引与字幕关联。诊断记录请求阶段、HTTP 状态、耗时和异常类型，作品内容保留在进程内存中。测试直接使用网络客户端，前台应用及作品库由用户继续操作。

## 提交前检查

`LibraryIndexerTest` 与 `StableIdTest` 验证目录索引与按来源路径生成的稳定身份连续性。`RepositoryMutationIntegrationTest` 覆盖扫描与来源更新、作品编辑、离线删除、共享封面和下载清理之间的并发行为。

`PlaybackRegressionIntegrationTest` 验证继续播放、文件结尾 A–B 循环和部分离线队列。`PlaybackSubtitleRetryIntegrationTest` 与 `PlayerStateUiIntegrationTest` 验证字幕恢复和播放器实际队列；`SourceNavigationIntegrationTest` 与 `DlsiteLoginLifecycleIntegrationTest` 验证导航、编辑草稿和官方登录页重建。这些测试使用隔离包与合成数据。

`DlsiteScanLifecycleIntegrationTest` 验证重新登录与扫描交错时的会话归属、结果提交和忙碌状态，网络响应由设备内 MockWebServer 提供。

```sh
python3 tools/check-public-content.py
git diff --cached --check
git diff --cached
```

提交范围为项目源码、构建配置、文档和合成测试数据。调试日志、设计资料、发布密钥、设备备份及媒体由本机管理。
