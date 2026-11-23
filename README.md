# Whisp

Whisp 是面向本地文件、NAS 和在线作品的 Android 音视频播放器，适合音声、ASMR 和带字幕的媒体。导入会为用户选择的媒体建立索引，离线下载会将所选作品保存到设备。

## 功能

- 本地目录授权导入、SMB 2/3、NFS 3 / 4.0 / 4.2 和 HTTPS WebDAV 来源。
- ASMR.ONE 在线来源、分页浏览、搜索、最新 / 热门排序和字幕筛选，打开作品后加入作品库。
- DLsite 官方账号登录、已购音声检索、原生播放和离线下载。
- 作品库、系列、收藏、搜索，以及音轨、台本和附赠资料。
- 本地与 NAS 视频、ASMR.ONE 视频直链，支持画面、全屏与只听声音模式；音频和视频共用队列、进度及离线下载。
- 后台播放、断点续听、书签、A–B 循环、单曲／列表循环、播放倍速、睡眠定时和精确跳转。
- VTT / LRC / SRT 字幕、同名 TXT 台本，支持 UTF-8 / UTF-16 / Shift_JIS / GB18030 自动识别与手动切换；固定阅读锚点和手动翻阅后的恢复跟随。
- 按目录及正篇／附赠组织音轨，各录音版本有独立的播放队列。
- 离线下载、可调节的在线播放缓存上限和独立图片缓存。
- 系统媒体控制、系统续播、通知封面隐私和最近任务预览隐私。
- 共享封面转场、系统返回手势和减少动态效果设置。

运行环境为 Android 8.0 或更新系统，支持 `arm64-v8a`、`armeabi-v7a`、`x86_64` 和 `x86`。GitHub Release 提供四种架构的独立安装包及覆盖全部架构的通用安装包。

## 文件组织

文件识别支持普通作品目录和带编号的作品目录；以下编号与名称均为合成示例。

```text
示例系列/
  RJ00000001 测试作品/
    音频/
      01_示例音轨.mp3
      01_示例音轨.mp3.vtt
    台本/
      01_示例音轨.txt
    特典/
      封面.png
```

支持 MP3、M4A、FLAC、WAV、Ogg、Opus 和 AAC，实际解码能力取决于设备。同编号的重复包装目录折叠为一个作品，不同编号的嵌套作品分别识别。字幕支持双扩展名或同名文件。中文、日文及其他 Unicode 文件名均可使用。

在「来源」添加本地目录、SMB 共享或 WebDAV 服务。SMB 地址示例为 `smb://nas.example/media/Audio/`，支持自定义端口；账号支持 `用户名` 和 `域\用户名`。WebDAV 使用 HTTPS。服务器、共享、目录与账号均通过来源页面配置。

NFS 地址示例为 `nfs://nas.example/media/Audio`，可选择 NFS 3、4.0 或 4.2，并配置 UID/GID；默认 Unix 身份为 `65534:65534`。连接使用 TCP 与 AUTH_SYS，NAS 的导出权限需覆盖手机地址并允许非特权客户端端口。建议选择音频所在的导出目录或允许挂载的子目录。扫描、字幕、随机跳转和离线下载共用现有作品库与播放器。

在「来源 → 添加来源 → DLsite」打开官方登录页，使用 viviON ID 登录后同步已购音声作品。来源覆盖 RJ 同人音声和 VJ 商业音声，支持作品名、声优、社团和作品编号搜索，以及购买时间或作品名排序。打开作品时读取官方曲目目录，音频和关联字幕使用统一的播放器与离线下载。社交登录用户可在现有 viviON ID 的账号管理中设置登录 ID 和专用密码。

DLsite 账号会话由 Android Keystore 加密保存；购买库按账号分开缓存，曲目按需读取，播放授权在资源打开时获取和更新。在线播放采用 DLsite Play 提供的优化音频，音质以实际资源为准。来源管理支持重新登录、同步和退出账号；已下载的离线副本由存储管理维护。

已购作品页的「导出诊断」提供最近 64 次账号请求的固定阶段、HTTP 状态码和耗时，用于远程排查登录、同步和播放问题。

在「来源」选择 ASMR.ONE 可浏览公开作品目录，按作品、声优或 RJ 编号搜索。选中作品时读取曲目树并加入作品库，音轨、字幕、台本和资料沿用统一播放器与离线下载。来源管理中的「更新已加入作品」刷新已导入作品的文件地址与元数据。

ASMR.ONE 提供常驻搜索框，支持作品名、声优、RJ 编号与作品链接。声优、社团、标签和字幕条件可组合筛选；最近搜索保存在本机。搜索结果采用封面与标题列表，返回时保留检索条件和浏览位置。

作品预览展示封面、曲目和筛选标签；「加入作品库」保存作品，播放时自动加入。元数据读取失败可在预览页重试。「介绍」展示官方正文与图片，「资料」展示社团、创作者分工、发布日期、类型、标签、系列、语言版本和精确评分。作品按 RJ 编号从 DLsite 官方完整 JSON 接口读取作品名、声优、系列和封面，官方键值接口作为兼容路径。作品信息中的「更新 DLsite 元数据」支持已有的 RJ 作品；来源刷新保留手动修改的标题、声优和封面。在线作品可通过「作品信息 → 删除作品」移除，同时清理该作品的下载、书签和播放进度。

在线目录使用 Paging 3 分页，内存保留最多约 100 条目录记录；作品数据库保存用户打开的作品。封面使用现有的 64 MiB 图片缓存，音频使用可调节的在线播放缓存。ASMR.ONE 的媒体请求使用独立网络客户端与站点 Referer。

## 构建

项目使用 Kotlin、Jetpack Compose、Navigation 3、Media3、Room、DataStore、WorkManager、Coil、OkHttp 和 SMBJ。依赖版本固定在 Gradle 配置中；Material 3 使用 `1.5.0-beta01` 的公开 MotionScheme API。

构建环境为 JDK 21、Android SDK Platform 37、Build Tools 36.0.0、NDK 30.0.16248370 和 CMake 3.31.6。Gradle Wrapper 固定为 9.8.1。SDK 路径通过 Android Studio、`ANDROID_HOME` 或本机的 `local.properties` 配置。

Debian 上可通过 apt 安装基础工具：

```sh
sudo apt-get install openjdk-21-jdk-headless adb cmake ninja-build clang-format-19
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Android SDK 需自行安装相应平台。Debug APK 位于 `app/build/outputs/apk/debug/`。

NDK 通过 Android SDK Manager 安装。CMake 可使用 SDK 内的 `cmake;3.31.6`，或在本机 `local.properties` 中用 `cmake.dir` 指向相同版本的系统安装。NFS 使用 libnfs 8.0.0，共享库与 JNI 桥接层分开构建；CMake 按固定提交和 SHA256 下载源码，构建时需要访问对应源代码地址。第三方来源与许可证见 [原生依赖](docs/NATIVE.md)。

```sh
./gradlew -PsplitApks :app:assembleRelease :app:lintRelease
```

Release 启用 R8 与资源裁剪，默认产物为待签名 APK。签名配置位于本机的 `.signing/signing.properties`，字段为 `storeFile`、`storePassword`、`keyAlias` 和 `keyPassword`，其中 `storeFile` 相对 `.signing/`。配置完成后构建会生成已签名 APK；签名目录由本机管理，并由 Git 排除。

`-PsplitApks` 使用 Android Gradle Plugin 的 ABI 拆分功能，在 `app/build/outputs/apk/release/` 生成四个架构安装包和 `app-universal-release.apk`。常规构建生成包含全部架构的单个安装包。

开发与验收版本采用 `X.Y.Z-rc.N` 命名，准备阶段使用 GitHub 预发布草稿供项目维护者验收。获得具体版本的发布批准后，公开对应的预发布条目，流程见 [版本发布](docs/RELEASING.md)。

## 测试与公开内容检查

工程检查通过 `./gradlew fmt` 和 `./gradlew qualityCheck` 执行，具体工具与实现分工见 [工程质量](docs/QUALITY.md)。

单元测试使用合成目录、示例名称、生成的字节和本机模拟服务。真实 Samba 合同测试使用专用测试共享，设备集成测试使用生成样本和隔离测试包。具体步骤见 [测试说明](docs/TESTING.md)。

```sh
python3 tools/check-public-content.py
```

公开内容检查会扫描 Git 候选文件和暂存内容，标记个人机器路径、局域网地址、密钥和媒体文件。提交前结合 `git diff --cached` 审阅改动。仓库内容为应用源码、构建配置、文档和合成测试数据；来源配置、签名、媒体及调试资料保存在本机。

## 播放与系统行为

- 来源凭据通过 Android Keystore 加密保存在设备上，音轨 URL 使用文件位置标识。应用数据保存在当前设备。
- 网络来源支持 SMB2/3、NFS 和 HTTPS WebDAV，SMB 与 NFS 使用只读操作。SMB 默认要求加密，兼容旧 NAS 时可在添加来源时允许未加密的局域网共享。
- 音频按原左右声道播放。A–B 循环通过播放器跳转实现，衔接精度受解码器和缓冲状态影响。
- 台本与字幕默认自动识别 UTF-8、UTF-16、Shift_JIS 与 GB18030，并支持手动切换编码；资料预览提供文本阅读和图片查看。
- 视频支持 MP4、M4V、MKV、WebM、MOV、3GP、MPEG-TS 与 MPEG-PS 容器，解码能力由设备提供。观看时保持屏幕亮起，后台和只听模式关闭视频轨道；全屏按画面方向显示，字幕保持可见。
- 睡眠定时使用单调时钟并优先通过 `AlarmManager` 调度，暂停期间持续计时，同时允许夜间保持 DSP 硬件音频卸载；生命周期与播放服务一致。设备重启后的播放由用户发起。
- 播放进度通常每 15 秒保存，暂停、切曲与手动跳转及时提交；突然终止可能丢失最近约 15 秒。
- 前台播放页更新播放时钟，下载进度与存储统计按需更新。
- Android 14 及更新系统提供原生音频输出选择器；旧系统使用兼容入口。通知和系统历史媒体卡片的显示也受系统行为影响。

## 许可证

Whisp 项目代码采用 [GNU LGPL v3 或更新版本](LICENSE)，SPDX 标识为 `LGPL-3.0-or-later`。版权与授权声明见 [NOTICE](NOTICE)；LGPL 引用的 GPL v3 正文见 [COPYING](COPYING)。Gradle Wrapper 和第三方依赖保留各自许可证。
