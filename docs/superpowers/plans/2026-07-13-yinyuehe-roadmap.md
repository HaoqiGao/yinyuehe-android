# 音悦盒 6–8 周实施路线图

> 本路线图把已批准的产品规格拆成六个可独立评审、构建和验收的里程碑。每个里程碑在开始实现前拥有自己的详细实施计划；前一里程碑的真实代码与验证结果是后一计划的输入。

**设计规格：** `docs/superpowers/specs/2026-07-13-yinyuehe-product-design.md`

## 顺序与依赖

```text
M1 独立工程 + Demo 播放
  ↓
M2 MediaStore + Room 曲库
  ↓
M3 播放会话 + 队列恢复
  ↓
M4 歌单 + 自适应完整 UI
  ↓
M5 在线歌词 + 元数据缓存
  ↓
M6 性能、稳定性、CI 与 Release
```

每个里程碑结束时，`main` 都必须保持可构建、可安装且主流程可演示。任何新范围必须替换同等工作量，不能延长六个里程碑。

## 规格覆盖矩阵

| 设计规格章节 | 实施里程碑 |
| --- | --- |
| 1–4 背景、目标、非目标、技术基线 | M1 |
| 5–6 模块架构、状态与依赖原则 | M1 建立边界；M2–M5 按边界扩展；M6 总体验证 |
| 7 产品信息架构 | M1 首页雏形；M2 曲库；M3 播放页；M4 歌单与设置；M5 歌词与歌曲信息 |
| 8 本地数据设计 | M2；播放快照由 M3 完成；设置由 M4 完成；诊断事件由 M6 完成 |
| 9 在线歌词与元数据 | M5 |
| 10 播放架构 | M1 建立服务与控制器；M3 完整会话和恢复 |
| 11 错误处理 | M2 权限/存储；M3 播放；M5 网络/解析；M6 数据库恢复与诊断 |
| 12 UI 与设计系统 | M1 建立 token；M4 完成自适应、深色与可访问性 |
| 13 测试策略 | 每个里程碑随功能增加测试；M6 汇总覆盖率和设备矩阵 |
| 14 性能、内存与稳定性 | M6 |
| 15 CI 与发布 | M1 基础 CI；M6 完整门禁、签名和 Release |
| 16 GitHub 作品集材料 | M6 |
| 17 迁移策略 | M1–M5 渐进迁移；M6 完成 Demo 音频压缩与 APK 体积验证 |
| 18 风险与缓解 | 每个里程碑维护对应风险；M6 复核全部风险 |
| 19 完成标准 | M6 发布门禁 |

## M1：独立工程与可运行 Demo 播放

**目标：** 从 Media3 release 源码树中建立独立多模块项目，并用正式 Media3 依赖播放四首内置 Demo 曲。

**包含：**

- Kotlin DSL、version catalog、convention plugins 和 Gradle wrapper。
- `:app`、`:core:common`、`:core:data`、`:core:player`、`:core:designsystem`、`:core:testing`、`:feature:library`。
- 领域 `Track`、Demo Repository、MediaLibraryService、MediaController 适配和“温暖唱片馆”首屏。
- ViewModel 单元测试、Compose UI 测试、Lint、Debug 构建和基础 CI。

**退出条件：** 全新 clone 可构建；四首 Demo 曲可见；点击歌曲可启动播放服务；系统媒体会话处于活动状态；在 API 32 以下或用户已授权通知时显示媒体通知；单元测试、UI 测试和 Lint 通过。

**详细计划：** `docs/superpowers/plans/2026-07-13-m1-foundation-demo-playback.md`

## M2：本地曲库、Room 与搜索

**目标：** 授权后扫描真实设备音乐，以 Room 缓存作为 UI 的离线读取入口。

**包含：**

- Android 13+ `READ_MEDIA_AUDIO` 与 Android 8–12 `READ_EXTERNAL_STORAGE` 权限状态机。
- MediaStore volume + row id 稳定标识、增量/全量扫描、事务 upsert 和不可用标记。
- `tracks`、`favorites`、`recent_plays` schema、索引、导出 schema 与 migration 测试。
- 歌曲/专辑/艺人/文件夹视图，本地搜索、排序、筛选和大数据 fixture。
- 权限拒绝、永久拒绝、空曲库、扫描失败和重新扫描 UI。

**退出条件：** API 26/30/33/36 权限与扫描路径通过；断电式中断不会破坏数据库；1000 首 fixture 搜索与滚动可用；旧缓存能在扫描失败时继续显示。

**已批准设计：** `docs/superpowers/specs/2026-07-13-m2-local-library-design.md`

**四个连续实施计划：**

1. `docs/superpowers/plans/2026-07-13-m2a-room-library-cache.md`
2. `docs/superpowers/plans/2026-07-13-m2b-mediastore-scanner.md`
3. `docs/superpowers/plans/2026-07-13-m2c-audio-permission-library-ui.md`
4. `docs/superpowers/plans/2026-07-13-m2d-library-search-views.md`

## M3：播放会话、后台控制与恢复

**目标：** 把基础播放升级为完整、可恢复的系统媒体体验。

**包含：**

- 单一 MediaLibraryService 所有权、AudioFocus、becoming-noisy、通知栏、锁屏、耳机和蓝牙控制。
- 播放队列、跳转、移除、拖动排序、顺序/单曲/随机模式。
- 当前歌曲、位置、队列和模式的 Proto DataStore 恢复快照。
- Controller 断开重连、进程回收、损坏文件、decoder 错误和跳过策略。
- `:feature:player`、迷你播放器和完整播放页。

**退出条件：** 后台、熄屏、耳机拔出和蓝牙控制真机通过；进程回收后恢复队列与位置；损坏文件不清空队列；播放器状态只有一个事实来源。

## M4：歌单、设置与自适应完整 UI

**目标：** 完成本地音乐产品的组织闭环和作品集视觉质量。

**包含：**

- `:feature:playlists` 与 `:feature:settings`。
- 自建歌单 CRUD、歌曲关系、拖动排序、收藏和最近播放系统集合。
- 主题、扫描目录、最小时长、缓存、权限、隐私与开源声明。
- 手机底部导航，横屏/折叠屏/平板 Navigation Rail 与列表/详情双栏。
- 深色主题、200% 字体、TalkBack、焦点顺序、RTL 和减少动画。

**退出条件：** 歌单事务与 UI 流程自动化通过；手机与平板布局通过截图/语义测试；可访问性清单无阻塞问题。

## M5：在线歌词与元数据增强

**目标：** 在不影响离线播放的前提下增加同步歌词、标准化元数据和封面。

**包含：**

- LRCLIB、MusicBrainz 和 Cover Art Archive Retrofit 客户端。
- 正确 User-Agent、MusicBrainz 平均 1 请求/秒队列、请求合并、指数退避和缓存。
- LRC 解析、时间轴定位、自动滚动、手动回到当前行。
- 标题/艺人/专辑/时长规范化、置信度评分、候选确认和歌曲信息 Bottom Sheet。
- 成功缓存、30 天元数据过期、24 小时负结果缓存与离线空态。
- WorkManager 只用于用户允许的延后缓存维护，不承担前台页面必须立即完成的请求。

**退出条件：** MockWebServer 成功/空结果/429/503/超时/无效 JSON 测试通过；断网不影响本地播放；中等置信候选不会自动覆盖本地标签。

## M6：性能、稳定性与 GitHub Release

**目标：** 把工程结论转化为可复现证据并发布首个求职作品版本。

**包含：**

- `:benchmark`、`:baselineprofile`、冷/温启动 TTID/TTFD 和滚动/歌词帧耗时。
- Baseline Profile 前后对比、100 次切歌压力测试、LeakCanary、StrictMode、30 分钟后台耗电抽样和 60 分钟真机冒烟。
- 脱敏本地诊断包、数据库 migration 完整矩阵和 API 26/30/33/36 设备矩阵。
- Demo 音频压缩、APK 体积前后对比、Gradle dependency verification 和 Dependabot。
- GitHub Actions 拉取请求门禁、标签签名、APK、mapping、SHA-256 和 Release Notes。
- README 演示视频、截图、架构图、数据流、测试/性能报告、隐私、许可和已知限制；补齐 CHANGELOG、PRIVACY、CONTRIBUTING、Issue 与 PR 模板。

**退出条件：** 设计规格第 19 节的“可复现、可演示、可验证、可度量、可发布”全部满足。

## 节奏与审查

- M1：5–7 个工作日。
- M2：7–9 个工作日。
- M3：6–8 个工作日。
- M4：7–9 个工作日。
- M5：6–8 个工作日。
- M6：5–7 个工作日。

每个任务采用测试先行、最小实现、验证、原子提交的节奏。每个里程碑完成后先审查规格符合性和代码质量，再编写下一里程碑的详细计划。
