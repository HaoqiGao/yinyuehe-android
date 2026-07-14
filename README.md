# 音悦盒 · YinYueHe

音悦盒是一款本地优先的 Android 音乐播放器，也是一份可复现的 Android 客户端工程作品。没有本地音频或未授予读取权限时，应用回退到四首内置 Demo；授权后通过 MediaStore 构建本地曲库。

## 已实现

- **本地曲库**：按 Android 版本申请 `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE`，扫描 API 26–36 MediaStore 音频；以卷名和行 ID 生成稳定曲目 ID，完整快照成功后再事务更新 Room。
- **三页 Compose UI**：曲库、播放、歌单三个目的地；不可变 `LibraryUiState`、密封 `MusicBoxAction` 与 ViewModel 组成单向数据流。Compose 不直接访问 Room 或 MediaController。
- **Media3 播放**：ExoPlayer + `MediaLibraryService` + MediaSession，支持后台播放、媒体通知、系统媒体按键、播放全部/随机、上一首/下一首、进度拖动、队列追加/删除/跳转。
- **用户数据**：Room + Flow 持久化收藏与最近播放；最近播放按曲目聚合、最新优先且固定最多 20 条，四首 Demo 同样可持久化。
- **系统音频协作**：Media3 管理 AudioFocus，并启用 audio-becoming-noisy 自动处理。模拟器已验证后台、通知和系统 pause/play；真实抢占及耳机/蓝牙断开仍列为物理设备待验项。
- **质量事件**：事件只写入本地 Room，当前没有上传链路。每条记录包含事件类型、可选稳定 `trackId`、时间戳和可选耗时；事件表保留最新 500 条，不记录标题、路径或 URI。稳定 `trackId` 在本地仍可关联同一曲目，因此不把这些数据描述为匿名数据。

项目当前不包含在线歌词、在线元数据、账号或后端服务。

## 工程结构

```text
:app
 └─ :feature:library        Compose 三页、ViewModel、UDF
     ├─ :core:data          MediaStore、Room、Repository
     ├─ :core:player        Media3 Controller/Service/Session
     └─ :core:designsystem  Material 3 主题

:core:data / :core:player ──> :core:common
:core:testing               测试替身
build-logic                 Android/Compose convention plugins
```

技术栈：Kotlin、Jetpack Compose、Media3 1.10.1、Room 2.8.4、Hilt、Coroutines/Flow、MVVM、JUnit、Robolectric、Compose UI Test、Android Lint 与 GitHub Actions。

## 构建与验证

要求 JDK 17 与 Android SDK 36：

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew test testDebugUnitTest lintDebug assembleDebug --stacktrace
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`

2026-07-14 的验证记录明确区分两个代码快照：`d9b04564` 基线完成真实 MediaStore 授权/撤权、后台播放、MediaStyle 通知、系统媒体控制、收藏与重启恢复、冷启动、内存单点和 5 轮短时稳定性采样；后续硬化后的 `deae6a82` 最终 pre-evidence 快照重新通过完整 Gradle 门禁（51 个 XML 报告文件、280 次 JVM 测试执行、0 失败/错误/跳过）、Room 1→2 迁移 `1/1`、Feature `7/7` 与 App `4/4` 设备测试，并补充自然完播重启和同进程旋转首帧去重回归。基线人工证据没有被表述为在最终快照全部重跑。详情：

- [21 项验收矩阵](verification/acceptance-scenarios.md)
- [2026-07-14 验证记录](verification/result-2026-07-14.md)

当前在本记录环境与本机 debug signing key 下捕获的 debug APK 为 `14,201,312` bytes，SHA-256 为 `183d5f13bae95485884e92882a8305c657e459437e7cb6d5368ca66636eef949`。clean source build 与两次 rerun 在本机得到相同字节，但该 SHA-256 只标识这份本地产物，不承诺跨机器或 keystore 字节复现；它不是签名 Release 产物。

CI 在 pull request 和 `main` push 上使用 Temurin 17、隔离的 Android SDK 36，并执行与本地相同的门禁命令。

## 已知验证边界

- AudioFocus 被其他真实应用抢占，以及有线耳机/蓝牙输出断开，尚未在物理设备执行；矩阵保持 `PENDING_DEVICE`。
- 冷启动样本与内存单点基线来自单台 API 36 模拟器，不代表启动优化结论、跨设备性能或无内存泄漏证明。
- 当前只生成 debug APK；正式 Release 仍需要签名配置、物理设备回归与发布流程。

## 设计与计划

- [产品设计](docs/superpowers/specs/2026-07-13-yinyuehe-product-design.md)
- [本地曲库设计](docs/superpowers/specs/2026-07-13-m2-local-library-design.md)
- [简历能力对齐计划](docs/superpowers/plans/2026-07-14-resume-parity.md)

## 许可

代码和内置演示音频按 Apache License 2.0 提供。详见[演示音频来源与校验和](docs/assets/demo-audio.md)。
