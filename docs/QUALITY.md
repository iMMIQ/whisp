# 工程质量

## 检查入口

Kotlin 和 Gradle 脚本使用 Spotless 8.10.4 与 ktfmt 0.65 的 Kotlin 官方风格，Python 工具使用 Black 26.10.1，JNI 桥接代码使用 clang-format 19。编辑器约定见 `.editorconfig` 与 `.clang-format`。

```sh
sudo apt-get install python3-venv clang-format-19
python3 -m venv .venv
.venv/bin/python -m pip install black==26.10.1
. .venv/bin/activate
export WHISP_BLACK="$PWD/.venv/bin/black"
./gradlew fmt
./gradlew qualityCheck
./gradlew -PsplitApks :app:assembleRelease :app:assembleDebugAndroidTest
```

`fmt` 统一排版，`qualityCheck` 执行格式检查、公开内容检查、主机端 JNI ASan/UBSan 桥接测试、单元测试及 Debug / Release Lint。依赖版本统一维护在 `gradle/libs.versions.toml`。GitHub Actions 对推送和 Pull Request 执行同一套检查，并编译 Release 和设备测试 APK。

## 构建速度

Gradle 默认启用任务输出缓存和配置缓存。本地连续构建复用 daemon；相同任务和输入复用已配置的任务图。构建缓存由 `setup-gradle` 在 CI 中保存，Kotlin、KSP、Lint 与测试任务可以复用匹配的输出。缓存行为遵循 [Gradle 构建缓存](https://docs.gradle.org/current/userguide/build_cache.html)和[配置缓存](https://docs.gradle.org/current/userguide/configuration_cache.html)的输入校验。

启用 `WHISP_SMB_TEST_CONFIG` 时，单元测试每次实际执行，以校验当前 Samba 服务状态。

日常构建通过 `abis` 指定设备架构，多个架构以逗号分隔：

```sh
./gradlew -Pabis=arm64-v8a :app:assembleDebug
./gradlew -Pabis=x86_64 -PsmokeTest :app:assembleSmoke :app:assembleSmokeAndroidTest
```

CI 的质量检查与设备测试 APK 使用 `x86_64`；发布构建独立执行 `-PsplitApks :app:assembleRelease`，覆盖四种 ABI 与通用 APK。两个阶段共用 Gradle daemon 和任务输出缓存，Release Lint 仍属于质量检查。

原生编译可通过 Debian 的 ccache 复用 libnfs 与 JNI 的编译结果：

```sh
sudo apt-get install ccache
./gradlew -Pccache -Pabis=arm64-v8a :app:assembleDebug
ccache --show-stats
```

CI 将 ccache 容量限制为 200 MiB，以 NDK、CMake 版本和原生源码生成缓存键。主分支成功构建写入缓存，Pull Request 读取缓存。Gradle 配置缓存保存在本地 `.gradle/`；GitHub 的配置缓存跨运行复用需要 `setup-gradle` 的加密密钥，当前 CI 由该 action 保存依赖和任务输出缓存。

通过 `./gradlew … --profile` 查看本机构建各阶段耗时，报告位于 `build/reports/profile/`。比较时使用相同任务、架构和 daemon 状态，并区分首次构建、清理后的缓存恢复和增量构建。

## 依赖版本

2026-10-12 按 [Google Maven](https://maven.google.com/) 和 [Maven Central](https://central.sonatype.com/) 发布信息升级直接依赖，版本在构建脚本中固定。常规组件采用最新稳定版；Material 3 使用当前 Beta 的动效接口。

| 组件 | 版本 |
| --- | --- |
| Gradle / AGP | 9.8.1 / 9.4.1 |
| Kotlin / KSP | 2.4.21 / 2.3.12 |
| Compose BOM / Material 3 | 2026.09.00 / 1.5.0-beta01 |
| Activity / Core | 1.13.0 / 1.19.1 |
| Lifecycle / Navigation 3 | 2.11.0 / 1.2.0 |
| Media3 / SMBJ | 1.11.1 / 0.15.0 |
| libnfs / Android NDK / CMake | 8.0.0 / 30.0.16248370 / 3.31.6 |
| Room / DataStore / WorkManager | 2.8.5 / 1.2.1 / 2.12.0 |
| Coil / OkHttp / Okio | 3.6.3 / 5.5.0 / 3.18.2 |
| Coroutines / kotlinx.serialization / Robolectric | 1.11.0 / 1.11.0 / 4.17 |
| AndroidX Test Runner / Rules / JUnit 扩展 | 1.7.0 / 1.7.0 / 1.3.0 |
| UI Automator / JUnit 4 / MockWebServer 3 | 2.4.0 / 4.13.2 / 5.5.0 |

编译 SDK 为 API 37，目标 SDK 为 API 36，最低系统为 API 26。构建使用 JDK 21 与 Build Tools 36.0.0，对应 [AGP 9.4 的兼容范围](https://developer.android.com/build/releases/agp-9-4-0-release-notes)。

Room 与 WorkManager 的扩展 API 随主模块提供。测试服务器使用 MockWebServer 3；播放器滑块和面板使用新版 Material 3 状态接口。

## 实现分工

| 能力 | 实现 |
| --- | --- |
| WebVTT 时间、标签、实体、注释及重叠字幕 | Media3 `WebvttParser` |
| 字幕缓存 | Android `LruCache` |
| SHA-256 与十六进制编码 | Okio `ByteString`，保留原 128 位截断格式 |
| 时间定位查找 | Kotlin 标准库 `binarySearch` |
| ViewModel 创建 | AndroidX `viewModelFactory` |
| 文件原子替换 | Java NIO `Files.move` |
| HTTP / SMB 传输 | OkHttp / SMBJ |
| NFS 协议与随机读取 | libnfs，共用 Media3 远程文件适配层 |
| 在线目录分页与重试 | `AsmrCatalogController` + kotlinx.serialization |
| 播放、下载与音频缓存 | Media3 |
| 视频画面、生命周期与等比例显示 | Media3 PlayerSurface 与 Compose ContentScale |
| 索引、设置与任务 | Room / DataStore / WorkManager |

应用负责作品目录识别、录音分组、字幕阅读锚点、多编码字幕台本识别、精细定位手势和按作品合并的进度写入。LRC 解析服务于多时间标签与偏移量；音频与视频时长由 Media3 播放准备后回写，扫描阶段不额外触发远程音轨读取。

在线播放缓存的淘汰器负责运行时容量调整；图片缓存统一由 Coil 3 磁盘/内存缓存与永久离线资料目录共同承担。

## API 与兼容范围

ASMR.ONE 使用公开的作品列表、搜索、作品信息和曲目接口；端点见 `AsmrOneClient`。`AsmrCatalogController` 负责分页、取消与重试，JSON 使用 kotlinx.serialization 解析。作品在加入或播放时写入 Room，来源更新刷新已加入的作品。

RJ 作品元数据由 `DlsiteClient` 优先读取 DLsite 官方 `maniax/api/=/product.json?workno=RJ…`，解析结构化声优与封面；`maniax/product/info/ajax` 是兼容接口，以响应中的作品键校验省略编号字段的合法数据。Jsoup 1.23.2 负责标题实体解码和作品介绍的结构化提取，Compose 按段落、标题与图片顺序渲染。AJAX 接口提供精确评分与销量，介绍与统计按需读取。标题、声优、系列和封面写入统一作品实体；元数据请求使用独立网络客户端。预览数据保存在内存状态中，加入或播放时统一提交事务。

ASMR.ONE 检索支持最近搜索（最多 20 条）与作用域独立的分页列表状态；RJ 和官方作品链接解析后直达作品接口，声优、社团、标签按名称组成查询条件，Navigation 3 为每个顶层标签页独立保存返回栈与列表状态。数据库当前为第 6 版：版本 1–4 通过 Room 自动迁移演进，版本 4→5（`MIGRATION_4_5`）引入外键级联并内联曲目与附件 URI，版本 5→6（`MIGRATION_5_6`）补充常用查询与排序索引；迁移保留已有作品、下载状态、书签和进度，官方原始声优用于区分后续手动编辑。

在线媒体使用 `whisp-asmr` 位置标识与独立 HTTP 数据源，播放、字幕及下载共享同一地址映射。作品 ID 由站点编号建立，曲目与资料 ID 由作品和相对路径建立。位置 ID 包含来源、媒体 ID 与 URL，使多个媒体可以共用同一文件地址。刷新保留标题编辑、收藏、书签和播放进度。

独立设备测试在 `smoke` 规则中保留 MockWebServer 使用的 OkHttp / Okio 类边界；正式 Release 使用常规 R8 裁剪规则。

数据库字段和内部 ID 格式保持兼容。新测试覆盖既有 ID 格式、WebVTT 注释与样式、重叠字幕、重复时间点和极大时间位置。

Material 3 固定为 `1.5.0-beta01`，当前动效配置使用该版本公开的 `MotionScheme` 接口；共享元素转场使用 Compose 的实验 API。对应功能通过界面回归验证。系统版本分支承担 Android 8–16 的兼容处理，`smoke` 构建承担独立设备测试。

目标 API 36 对应当前系统行为范围，Lint 提示 API 37 的目标版本适配。发布构建包含四种 ABI 和通用安装包，导出的媒体服务通过 `MediaLibrarySession.onConnect` 验证控制器身份。

Kotlin 编译器将警告视为错误。Gradle 9.8 的弃用报告中，`Configuration.setVisible` 来自 AGP 9.4.1 内部配置；后续 AGP 升级继续追踪该上游兼容提示。
