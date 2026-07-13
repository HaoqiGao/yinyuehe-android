# 音悦盒产品级改造设计

日期：2026-07-13  
状态：已批准  
目标周期：全职投入 6–8 周  
目标岗位：通用 Android 业务客户端开发实习生

## 1. 背景

现有“音悦盒”位于 Media3 release 源码包的 `demos/yinyuehe` 中，已经实现 Kotlin、Jetpack Compose、Media3、后台播放、通知栏控制、MediaStore、本地演示曲、Room 收藏与最近播放、MVVM/UDF 和本地事件记录。现有构建和 Android Lint 可以通过，并沉淀了 21 项人工验收场景。

当前形态不适合作为独立求职作品：

- 项目依赖整套 Media3 源码，招聘方无法快速区分上游代码与个人代码。
- 当前目录没有 Git 历史，不能展示持续迭代、提交质量和 CI 流程。
- `MusicBoxApp.kt` 约 1000 行，`MusicBoxViewModel.kt` 约 476 行，页面、状态和业务编排边界过大。
- ViewModel 直接创建 Repository，数据对象与 Media3 `MediaItem` 渗透到 UI，不利于隔离测试。
- 没有自动化测试、CI、设备验收结果和真实的性能前后对比。
- 没有在线歌词、元数据增强、自定义歌单、完善搜索和产品级失败状态。

本设计将现有成果渐进迁移到独立仓库 `yinyuehe-android`，通过正式 Maven 依赖引入 Media3，不复制 Media3 demo 服务实现。

## 2. 产品目标

音悦盒是一款本地优先的 Android 音乐播放器。用户在没有账号、没有后端、没有网络甚至没有本地音乐的情况下，也能完成浏览、组织和播放主流程；联网时可以获取同步歌词、标准化元数据和专辑封面。

项目同时是一份可验证的 Android 求职作品，必须让招聘方在五分钟内完成以下判断：

1. 这是一个可以独立构建、安装和演示的 App。
2. 代码具有清晰模块边界、单向数据流和可测试依赖。
3. 作者掌握 Compose、Jetpack、Media3、Room、网络、异步编程、权限和后台任务。
4. 性能、内存与稳定性结论有可复现证据，而不是描述性宣称。
5. GitHub Release 提供签名 APK、校验和、版本说明和已知限制。

## 3. 明确不做

为了在 6–8 周内形成完整闭环，本阶段不实现：

- 用户账号、云同步、自建后端和跨设备同步。
- 商业音乐平台抓取、在线音乐搜索、在线播放和下载。
- 社交、评论、推荐算法和个性化画像。
- 音频标签写回、音频剪辑、格式转换和均衡器。
- Android Auto、Wear OS、桌面端或 Kotlin Multiplatform。
- 为展示技术而进行无业务价值的动态化、插件化或过细模块拆分。

## 4. 仓库与技术基线

新仓库目录为 `yinyuehe-android`，默认使用：

- `applicationId` 与 namespace：`app.yinyuehe`
- `minSdk = 26`
- `compileSdk = 36`
- `targetSdk = 36`
- JDK 17 与 JVM target 17
- Kotlin、Gradle、Android Gradle Plugin 和依赖版本全部固定在 version catalog 中，不使用动态版本
- Kotlin DSL 与 convention plugins 管理公共 Android、Compose、测试和静态分析配置

主要技术：

- Jetpack Compose、Material 3、Navigation Compose
- Lifecycle、ViewModel、SavedStateHandle、Coroutines、Flow
- Hilt 依赖注入
- Room、Proto DataStore
- Retrofit、OkHttp、kotlinx.serialization
- Media3 ExoPlayer、MediaSession、MediaLibraryService
- Coil 图片加载与磁盘缓存
- WorkManager 处理显式触发且允许延后的维护任务

## 5. 模块架构

项目包含 10 个主要工程模块和 2 个验证模块。`:core:testing` 只进入测试 classpath，不打包进 Release APK；其余模块构成产品代码。模块依赖只允许从上向下。

| 模块 | 职责 | 可依赖 |
| --- | --- | --- |
| `:app` | Application、Activity、全局导航、Hilt 装配、主题入口和启动流程 | 所有 feature、必要 core |
| `:feature:library` | 首页、曲库、歌曲/专辑/艺人/文件夹浏览、搜索、筛选、排序 | core 模块 |
| `:feature:playlists` | 自建歌单、收藏、最近播放、歌单排序 | core 模块 |
| `:feature:player` | 播放详情、迷你播放器、同步歌词和播放队列 | core 模块 |
| `:feature:settings` | 主题、扫描目录、缓存、权限、诊断、开源声明 | core 模块 |
| `:core:common` | 领域模型、类型化结果、调度器抽象、时间与日志接口 | 无 Android UI 依赖 |
| `:core:data` | Repository、Room、DataStore、MediaStore、网络 DTO、缓存和映射 | common |
| `:core:player` | MediaLibraryService、ExoPlayer、MediaSession、Controller 适配 | common、data 的最小接口 |
| `:core:designsystem` | 语义颜色、字体、间距、图标和可复用 Compose 组件 | common |
| `:core:testing` | Fake Repository、fixture、测试 dispatcher 和 Compose 测试规则 | common |
| `:benchmark` | Macrobenchmark、启动和滚动/歌词卡顿测量 | app |
| `:baselineprofile` | Baseline Profile 生成与校验 | app |

Feature 模块之间不直接依赖。页面跳转通过 `:app` 中的导航图和稳定 route 参数完成。跨 Feature 数据通过 Repository 的 `Flow` 共享，不通过全局可变对象或页面间传递大型对象。

`core:data` 首期保留为一个数据模块，通过包级边界区分 MediaStore、Room、网络和 DataStore。只有当构建耗时或独立复用形成实际问题时，才进一步拆分。

## 6. 状态与依赖原则

每个页面使用独立的 `ViewModel`、不可变 `UiState` 和用户 `Action`：

```text
Compose -> Action -> ViewModel -> Repository / PlayerController
Repository Flow / Player Flow -> ViewModel -> UiState -> Compose
```

约束如下：

- Composable 不直接访问 Room、Retrofit、MediaStore 或 MediaController。
- ViewModel 通过构造函数接收接口，不创建具体 Repository、数据库或播放器。
- Room Entity、网络 DTO 和 Media3 `MediaItem` 不进入 Feature 公共 API。
- 一次性 UI 反馈使用带消费语义的 effect channel；持久页面内容只存在于 `UiState`。
- 播放队列和播放器状态由 `core:player` 暴露为 `StateFlow`，Feature 不复制第二份真相。
- 需要跨进程或进程回收恢复的最小状态写入 Proto DataStore，不依赖内存单例。

## 7. 产品信息架构

### 7.1 首页

- 继续播放卡片。
- 最近添加、最近播放和收藏快捷入口。
- 用户创建的常用歌单。
- 曲库扫描状态、权限状态和可恢复错误。
- 无权限或空设备时仍显示内置 Demo 曲。

### 7.2 曲库

- 歌曲、专辑、艺人和文件夹四种视图。
- 按标题、艺人和专辑进行本地搜索。
- 按添加时间、标题、艺人、专辑和时长排序。
- 按文件类型、最小时长和扫描目录筛选。
- 批量加入歌单、加入队列、收藏和播放。

### 7.3 歌单

- 创建、重命名和删除自定义歌单。
- 添加、移除和拖动排序歌曲。
- 收藏与最近播放作为系统集合，不允许删除集合本身。
- 歌单删除采用确认对话框；只删除关系，不删除设备文件。

### 7.4 播放页

- 专辑封面、歌曲信息、播放/暂停、上一首/下一首、进度和播放模式。
- 可展开的播放队列，支持跳转、移除和拖动排序。
- 普通歌词与同步歌词；当前歌词自动滚动，用户滚动后可一键回到当前行。
- 歌曲信息 Bottom Sheet 展示本地标签、在线数据来源与匹配得分；中等置信的元数据候选在这里由用户确认。
- 迷你播放器在首页、曲库和歌单保持可见。
- 不支持或损坏的音频给出可理解原因，并允许跳过而不清空队列。

### 7.5 设置

- 跟随系统、浅色和深色主题。
- 扫描目录、最小时长、重新扫描和缓存清理。
- 权限状态与系统设置入口。
- 在线歌词/元数据开关和缓存说明。
- 本地诊断包导出、版本、隐私说明、开源许可和已知限制。

## 8. 本地数据设计

Room 是用户数据与增强数据的单一事实来源。MediaStore 仍是设备媒体文件的权威来源。

| 表/存储 | 主要字段与用途 |
| --- | --- |
| `tracks` | 稳定 media id、content URI、标题、艺人、专辑、时长、mime type、文件夹、添加/修改时间、可用状态和扫描代次 |
| `favorites` | track id、收藏时间 |
| `recent_plays` | track id、最近播放时间、播放次数、最近位置 |
| `playlists` | id、名称、创建和更新时间 |
| `playlist_tracks` | playlist id、track id、排序位置、加入时间 |
| `lyrics` | track fingerprint、普通/同步歌词、来源、匹配得分、抓取和过期时间、负结果过期时间 |
| `remote_metadata` | track fingerprint、MusicBrainz ids、候选字段、匹配得分、封面地址、抓取和过期时间 |
| `diagnostic_events` | 类型化错误码、组件、时间、耗时和脱敏上下文；最多保留最近 500 条 |
| Proto DataStore | 权限请求历史、主题、扫描设置、联网增强开关和最小播放恢复快照 |

MediaStore id 使用“volume + row id”构造稳定领域 id。扫描先在事务外完整读取一个可独立查询的存储卷，再在该卷的短事务中 upsert 当前媒体、标记本次未出现的旧记录不可用并推进检查点；查询未完成时不提交该卷。不同存储卷可以独立成功或保留各自上次成功缓存。收藏、歌单关系和最近播放记录持续保留，以便文件重新出现后恢复。

Android 11 及以上优先利用 MediaStore generation 判断变化；Android 8–10 使用修改时间和显式全量扫描。扫描在 IO dispatcher 执行并提供取消、进度和最终统计。

数据库 schema 必须导出并纳入版本控制。Release 构建禁止 destructive migration；每次 schema 变化都提供 migration 和迁移测试。

## 9. 在线歌词与元数据

联网增强不参与本地播放的成功条件。

### 9.1 服务选择

- LRCLIB：按标题、艺人、专辑和时长获取普通歌词与同步歌词；不需要 API key。
- MusicBrainz：获取标准化 recording、artist、release 和 MBID；客户端设置可联系的 User-Agent，并在进程内限制为平均每秒最多一次请求。
- Cover Art Archive：使用 MusicBrainz release MBID 获取封面。

参考：

- <https://lrclib.net/docs>
- <https://musicbrainz.org/doc/MusicBrainz_API>
- <https://musicbrainz.org/doc/MusicBrainz_API/Rate_Limiting>
- <https://musicbrainz.org/doc/Cover_Art_Archive/API>

### 9.2 请求与匹配

网络请求只在以下时机触发：

- 用户首次打开某首歌的歌词页。
- 用户主动刷新歌词或元数据。
- 已有缓存过期，且页面正在使用该数据。

匹配器依次规范化大小写、空白、括号版本信息和常见 featuring 标记，再综合标题、艺人、专辑和时长差计算置信度。高置信结果自动缓存；中等置信结果展示候选供用户选择；低置信结果视为未匹配，不覆盖本地标签。

缓存策略：

- 已成功歌词默认长期可用，用户刷新时才强制更新。
- 元数据缓存 30 天，命中缓存时立即显示并在后台刷新过期项。
- 未匹配结果缓存 24 小时，避免重复请求。
- HTTP 429/503 与网络瞬断采用带抖动的指数退避。
- MusicBrainz 请求经过单进程队列限速；并发 UI 请求合并相同 fingerprint。

所有在线内容保留来源字段。设置页允许关闭联网增强和清除缓存。服务不可用、超时或解析失败只影响增强内容，不影响曲库和播放。

## 10. 播放架构

`:core:player` 提供独立于 UI 的播放能力：

- `PlaybackService` 继承 `MediaLibraryService`，持有唯一 ExoPlayer 和 MediaLibrarySession。
- UI 进程通过 MediaBrowser/MediaController 连接服务。
- `PlayerController` 把 Media3 callback 转换为领域 `PlaybackState`、`QueueItem` 和类型化 `PlaybackError` Flow。
- Media3 负责 AudioFocus，应用监听 `ACTION_AUDIO_BECOMING_NOISY` 并暂停播放。
- 通知栏、锁屏、耳机按键和蓝牙媒体控制与应用内状态使用同一 MediaSession。
- 播放队列、当前索引、位置、播放模式和速度定期写入最小恢复快照。
- 进程回收后恢复队列和位置，但只有在用户主动播放或系统媒体恢复请求时才启动实际播放。
- `core:player` 不继承或复制上游 Media3 demo service；只依赖正式发布的 Media3 artifact。

## 11. 错误处理

数据层使用类型化 `AppError`，至少区分 Permission、Storage、Database、Network、RateLimit、Parsing、Playback 和 Unknown。异常不以字符串跨层传播。

| 场景 | 产品行为 |
| --- | --- |
| 首次未授权 | 解释权限用途，提供授权按钮，同时展示 Demo 曲 |
| 临时拒绝 | 保持 Demo 曲和设置入口，不连续弹窗 |
| 永久拒绝 | 显示系统设置入口，返回应用后重新检查权限 |
| 空曲库 | 显示扫描目录、过滤条件和重新扫描入口 |
| 扫描失败 | 保留上次成功曲库，显示可重试提示和脱敏错误码 |
| 网络离线/超时 | 优先展示缓存；无缓存时显示非阻塞空态和重试 |
| API 限流/服务错误 | 排队或退避，不在前台快速循环重试 |
| 歌词无匹配 | 允许再次搜索和手动选择候选，不显示为播放错误 |
| 音频损坏/不支持 | 保留队列，允许重试或跳过，记录播放器错误码 |
| 数据库迁移失败 | 阻止静默清库，展示恢复说明并允许导出诊断信息 |

页面状态采用“加载、内容、空态、可恢复错误”四类稳定状态。已有内容时，刷新错误通过内联提示或 Snackbar 表达，不用全屏错误覆盖可用内容。

## 12. UI 与设计系统

视觉方向为“温暖唱片馆”：米白、珊瑚橙和深棕形成主要品牌色，辅助使用低饱和绿、沙色和灰棕。页面像现代唱片杂志，但保持 Material 3 的交互一致性。

设计约束：

- 所有颜色、字体、形状、间距和阴影使用语义 token，不在 Feature 页面写死。
- 完整实现浅色与深色主题；主题切换不重建业务状态。
- 手机使用底部导航；横屏、折叠屏和平板使用 Navigation Rail 和列表/详情双栏。
- 所有触控目标不小于 48dp。
- 核心文本和背景达到 WCAG AA 对比度。
- 支持系统字体缩放，不裁切 200% 字号下的核心操作。
- 提供 TalkBack 描述、合理焦点顺序、键盘导航、RTL 和减少动画设置。
- 列表稳定 key、分页式 UI 计算和合适的图片尺寸避免曲库滚动抖动。

## 13. 测试策略

### 13.1 单元测试

覆盖：

- ViewModel 状态转换和一次性 effect。
- 曲库筛选、排序和搜索。
- 歌单增删、排序和收藏逻辑。
- LRC 解析、时间轴查找和当前歌词定位。
- 远程元数据规范化、匹配评分、缓存过期和请求合并。
- 播放队列状态转换与恢复快照。
- 各类错误到 UI 状态的映射。

关键业务逻辑的行覆盖率目标为 85% 以上。覆盖率只作为缺口提示，不替代断言质量。

### 13.2 集成测试

- Room DAO、事务、外键和所有 schema migration。
- Repository 的本地优先、缓存命中、缓存过期、退避和错误保留行为。
- MockWebServer 下的成功、空结果、429、503、超时和无效 JSON。
- Fake MediaController 下的连接、播放、队列修改、错误与恢复。

### 13.3 UI 与设备测试

- Compose 测试覆盖权限入口、曲库搜索、创建歌单、播放 Demo 曲、队列和歌词状态。
- API 26、30、33 和 36 模拟器执行安装、首次启动、权限拒绝、Demo 曲播放、后台 Service 连接和进程重建恢复；API 36 额外执行完整 Compose 主流程套件。
- 至少一台 Android 13 或以上真机执行后台播放、熄屏、耳机拔出、蓝牙控制和 60 分钟连续冒烟。
- 自适应布局在手机竖屏、手机横屏和至少一个平板尺寸执行截图测试或语义断言。

## 14. 性能、内存与稳定性

### 14.1 启动性能

`:benchmark` 对 release-like 构建执行多轮冷启动与温启动，记录 TTID 和 TTFD。`:baselineprofile` 覆盖启动、进入曲库、打开播放页和滚动歌词。README 报告同一设备上启用 Baseline Profile 前后的中位数与分位数，不跨设备宣称绝对性能。

启动路径禁止同步数据库扫描、网络请求和全量 MediaStore 查询。首页先显示 Room 缓存或 Demo 曲，扫描和增强数据异步更新。

### 14.2 卡顿与内存

- Macrobenchmark 测量千首曲库滚动、播放页切换和同步歌词滚动的帧耗时。
- LeakCanary 只在 debug 构建启用。
- 压力场景包括反复进出播放页、切歌 100 次、服务断开重连和主题切换。
- 压力测试完成后不能存在确认的 Activity、ViewModel、MediaController 或 Service 泄漏，内存不能随每轮操作持续单调增长。
- StrictMode 只在 debug 开启，用于发现主线程磁盘/网络访问和资源泄漏。

### 14.3 稳定性与诊断

- 不默认接入广告、用户画像或远程分析 SDK。
- 本地诊断事件使用固定错误码和环形上限。
- 用户主动导出的 JSON 只包含版本、Android 版本、设备类别、扫描统计、耗时和错误码；不包含歌曲标题、文件名、原始路径、歌词或稳定设备标识。
- 关键协程由明确 scope 管理；SupervisorJob 只用于允许单任务失败不取消同级任务的边界。
- 所有 callback、receiver、controller 和 listener 都有对称释放路径及测试。

## 15. CI 与发布

GitHub Actions 分为拉取请求和标签发布两条流程。

拉取请求门禁：

1. 格式检查与 detekt。
2. Android Lint。
3. JVM 单元测试与覆盖率报告。
4. Debug 和 unsigned Release 构建。
5. 模拟器上的关键数据库与 Compose 测试。

标签发布：

1. 重复执行全部拉取请求门禁。
2. 从 GitHub Actions secrets 注入 keystore、别名和密码；仓库不保存签名密钥。
3. 生成签名 APK、mapping、SHA-256 校验和和版本说明。
4. 将 APK 与校验和附加到 GitHub Release。

仓库启用 Gradle dependency verification，提交 wrapper checksum，固定 Action 主版本或 commit，并通过 Dependabot 提交受测试保护的依赖更新。

## 16. GitHub 作品集材料

README 首屏包含：

- 一句话定位和 30 秒演示 GIF/视频。
- 手机浅色/深色和平板双栏截图。
- 本地优先、后台播放、在线歌词、模块化和质量验证五个亮点。
- Release APK 下载入口和 SHA-256 校验方法。

README 后续包含：

- 功能矩阵与已知限制。
- 模块架构图和离线优先数据流图。
- 技术选型、关键权衡和没有采用的方案。
- 一键构建、测试、基准测试和诊断包导出命令。
- 性能前后对比、测试设备和测量方法。
- 隐私说明、第三方数据来源、开源许可和贡献规范。

仓库还提供 `CHANGELOG.md`、`LICENSE`、`PRIVACY.md`、`CONTRIBUTING.md`、Issue 模板、PR 模板和版本化验收报告。

## 17. 迁移策略

迁移采用渐进替换，不从零重写：

1. 建立独立构建、version catalog、convention plugins 和 CI 骨架。
2. 迁移 4 首 Demo 曲并转为适合 APK 体积的压缩格式；保留来源与许可说明。
3. 先定义领域模型与 Repository/PlayerController 接口，再迁移 MediaStore、Room 和播放服务。
4. 用正式 Media3 artifact 替换对 `demo-session-service` 和上游源码模块的依赖。
5. 按首页、曲库、歌单、播放页、设置顺序拆分 Compose 和 ViewModel。
6. 每迁移一条功能链路，同时迁移或新增对应自动化测试；旧实现通过验收后再删除。
7. 在线增强、基准测试、诊断与作品集材料在本地主流程稳定后加入。

原 Demo 未作为正式产品分发，因此新仓库不承担旧 `applicationId` 或数据库升级兼容；现有 Room 数据只作为功能参考，不设计跨应用数据迁移。

## 18. 风险与缓解

| 风险 | 缓解措施 |
| --- | --- |
| MusicBrainz 限流或短期不可用 | 1 请求/秒队列、合并、缓存、退避；本地功能不依赖它 |
| 自动匹配错误覆盖用户标签 | 远程数据独立保存；中等置信候选由用户确认；不写回媒体文件 |
| 大曲库扫描和列表卡顿 | 后台增量扫描、Room 索引、稳定 key、基准测试和真实大数据 fixture |
| MediaSession 生命周期复杂 | 单一 Service 所有权、Controller 适配层、恢复测试和对称释放 |
| 多模块拖慢开发 | 只保留 10 个主要工程模块，`:core:testing` 不进入生产依赖，数据实现暂不继续拆分 |
| 作品功能扩张 | 坚持“不做”列表；新增功能必须替换同等工作量而不是扩张周期 |
| 真机差异导致验收延迟 | 第 2 周开始持续真机验证，不在发布前集中测试 |

## 19. 完成标准

项目同时满足以下条件才可发布首个作品集版本：

### 可复现

- 全新 clone 按 README 指令可使用 JDK 17 构建 Debug。
- CI 的格式、静态分析、Lint、测试和 Debug/Release 构建全部通过。

### 可演示

- 无音频权限、无设备歌曲和断网状态下仍可使用 Demo 曲完成浏览、播放、队列、收藏和歌单主流程。
- 授权后可扫描真实本地音乐，并完成搜索、排序、播放、后台控制、歌词和恢复。

### 可验证

- 关键业务逻辑行覆盖率达到 85%。
- 所有 Room migration、核心 Repository 和关键 Compose 流程有自动化测试。
- API 26、30、33、36 的安装、首次启动、权限拒绝、Demo 曲播放、后台 Service 连接和进程重建恢复验收通过；API 36 的完整 Compose 主流程套件通过。

### 可度量

- 启动、滚动/歌词卡顿和 Baseline Profile 前后对比包含设备、构建类型、轮次和原始结果。
- 切歌 100 次、反复页面导航和服务重连后没有确认泄漏或持续内存增长。
- 至少一台真机完成 60 分钟后台/熄屏/耳机/蓝牙验收并记录结果。

### 可发布

- GitHub Release 包含签名 APK、SHA-256、CHANGELOG 和已知限制。
- README 包含演示、截图、架构、构建、测试、性能和隐私信息。
- 仓库不包含签名密钥、API secret、个人媒体文件或未脱敏诊断数据。
