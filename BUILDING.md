# 构建 0.8.14

工程入口为 `MV播放` 本身，`shared/` 已包含所需共享源码，不依赖其他插件目录。

## 环境与依赖

需要 Windows x64、PowerShell 7、JDK 25，以及合法安装的 Steam SPW 1.19.2（受测 build 26148620）。宿主与开发工具由开发者本机提供，不随源码分发。

`-ToolsRoot` 指向工具目录，需包含：

- `jdk25/jdk-25.0.4.1/`，或用 `-JdkHome` 单独指定 JDK。
- `kotlin-stdlib-2.4.20.jar`、`kotlin-metadata-jvm-2.4.20.jar`。
- `kotlin-compiler-embeddable.jar`、`kotlin-compose-compiler-plugin-embeddable.jar`，与上述 Kotlin 版本配套。
- `annotations.jar`。
- `spw-host.jar`，编译所需的本地宿主参考库，不得上传该文件。

`-HostJar` 指向实际安装的 `app/ffmpeg-x64.dll`；它是运行检查使用的宿主类库，其旁应有原生 Skia 依赖，上一级应有 `runtime/release`。宿主文件名沿用 SPW 原名。

从 [SPW Media Runtime v0.2.0](https://github.com/GaBoron/SPW-Media-Runtime/releases/tag/v0.2.0) 的 SDK ZIP 解出 Maven 布局到 `vendor/sdk/`，保留下列三个 JAR：

```text
io/github/gaboron/spwmedia/spw-media-sdk/0.2.0/spw-media-sdk-0.2.0.jar
io/github/gaboron/spwmedia/spw-media-protocol/0.2.0/spw-media-protocol-0.2.0.jar
io/github/gaboron/spwmedia/spw-media-sdk-compose/0.2.0/spw-media-sdk-compose-0.2.0.jar
```

另将 `com.google.zxing:core:3.5.3` 的原始 JAR 命名为 `zxing-core-3.5.3.jar` 放到 `vendor/runtime-lib/`（许可证和 NOTICE 位于本项目资源目录）。

将 Maven 坐标 `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.9.0` 和 `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.9.0` 对应 JAR 放到 `vendor/runtime-lib/`。六个 JAR 的固定 SHA256 在 [dependencies.json](../vendor/dependencies.json)，构建会强制核对。SDK ZIP 的 SHA256 为 `112D30147D704BEE8DA51B344FC1D0DC20615D54A2671BE772E413BBABD372CB`。

## 编译与检查

在本工程目录运行，替换示例中的依赖路径：

```powershell
./build.ps1 -ToolsRoot 'D:/spw-tools' -JdkHome 'D:/jdk-25' -HostJar 'D:/Steam/steamapps/common/Salt Player for Windows/app/ffmpeg-x64.dll' *> build-verified.log
```

成功时输出自检结果和 `build/libs/music-video-for-spw-0.8.14.spmod`。构建不会覆盖 `release/` 的原版交付包，也不会安装插件。不同 ZIP 时间戳可能改变包摘要，核对内容时应比较条目内容。

完整后端／指针与生命周期检查还需将未修改的 Runtime 0.2.0 放入 `release/spw-media-runtime-0.2.0.spmod`。运行时测试使用合成媒体与隔离配置，不连接用户曲库：

```powershell
./scripts/TestRuntime.ps1 -Redesign *> redesign-verified.log
./scripts/TestLifecycle.ps1 *> lifecycle-verified.log
./scripts/TestRuntime.ps1 -CompileOnly *> runtime-compile.log
./scripts/AuditRelease.ps1 -PackageOnly *> audit-verified.log
```

这些脚本的默认工具位置沿用工作区布局；独立克隆时，TestRuntime 可传工具路径，TestLifecycle 需要按其开头的 `$tools` 和 `$jdk` 配置本地路径。`-PackageOnly` 仍要求同一次构建的后端／指针和生命周期记录，不代表已在用户当前 SPW 会话或物理显示器上验收。

## 上传范围

上传 `src/`、`shared/`、`scripts/`、`docs/`、`vendor/dependencies.json`、`vendor/download.mjs`、`build.ps1`、README、`.gitignore` 和发布说明／校验清单。不要直接上传整个工作区。

安装包与安装套件适合作为 Release 附件；工具、宿主、依赖缓存、测试输出和原始日志已由忽略规则排除。主项目许可证尚未选定；现存第三方许可需要继续保留。依赖准备仍是手动步骤，尚未验证全新机器从零构建。
