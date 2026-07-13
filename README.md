# 音悦盒 · YinYueHe

音悦盒是一款本地优先的 Android 音乐播放器，也是一份可复现的 Android 客户端工程作品。

## 当前里程碑

M1 提供四首内置演示曲、Jetpack Compose 曲库首页和基于 Media3 `MediaLibraryService` 的后台播放链路。它不依赖设备本地音乐、网络、账号或后端。

## 技术结构

- Kotlin + Jetpack Compose + Material 3
- MVVM 与单向数据流
- Hilt 依赖注入
- Media3 ExoPlayer、MediaSession、MediaLibraryService
- 多模块 Gradle 工程、version catalog 与 convention plugins
- JUnit、Robolectric、Compose UI Test、Android Lint、GitHub Actions

## 构建

环境：JDK 17、Android SDK 36。

```bash
./gradlew test testDebugUnitTest lintDebug assembleDebug --stacktrace
```

APK：`app/build/outputs/apk/debug/app-debug.apk`

## 设计与计划

- 产品设计：`docs/superpowers/specs/2026-07-13-yinyuehe-product-design.md`
- 6–8 周路线图：`docs/superpowers/plans/2026-07-13-yinyuehe-roadmap.md`
- M1 实施计划：`docs/superpowers/plans/2026-07-13-m1-foundation-demo-playback.md`

## 许可

代码和内置演示音频按 Apache License 2.0 提供。演示音频来源与校验和见 `docs/assets/demo-audio.md`。
