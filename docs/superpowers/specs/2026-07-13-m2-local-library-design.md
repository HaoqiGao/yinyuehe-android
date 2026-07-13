# M2 本地曲库、Room 与搜索设计

日期：2026-07-13

状态：已批准

父级规格：`docs/superpowers/specs/2026-07-13-yinyuehe-product-design.md`

路线图：`docs/superpowers/plans/2026-07-13-yinyuehe-roadmap.md`

## 1. 背景与目标

M1 已经交付独立多模块 Android 工程、正式 Media3 依赖、四首内置 Demo 曲、应用内播放入口和基础 CI。M2 在不改写现有播放链路的前提下，把曲库来源升级为真实设备音频，并让 Room 成为本地曲库 UI 的离线读取入口。

M2 完成后，用户应当能够：

1. 在 Android 8–16 上完成与系统版本匹配的音频读取授权。
2. 授权后扫描真实设备中的音乐，并在后续启动时立即看到上次成功缓存。
3. 按歌曲、专辑、艺人和文件夹浏览本地曲库。
4. 按标题、艺人、专辑和文件夹搜索，并进行本地排序和筛选。
5. 在无权限、空曲库或首次扫描失败时继续使用内置 Demo 曲。
6. 在已有缓存的扫描失败场景中继续浏览旧曲库，不被全屏错误覆盖。

工程目标是形成可审查的 Room、MediaStore、权限状态机、事务一致性和 Compose 状态管理证据，而不是只完成一条理想路径。

## 2. 明确范围

### 2.1 M2 包含

- API 33+ `READ_MEDIA_AUDIO` 和 API 26–32 `READ_EXTERNAL_STORAGE`。
- Room 曲目缓存、收藏和最近播放的 schema 基础。
- MediaStore 多存储卷查询、稳定 ID、增量/全量扫描和删除对账。
- 权限、扫描、空内容和可恢复错误界面。
- 歌曲、专辑、艺人和文件夹视图。
- 本地搜索、排序、筛选和 1000 首固定 fixture 验证。
- 导出 Room schema、migration 测试基础和 API 26/30/33/36 验收。

### 2.2 M2 不包含

- `POST_NOTIFICATIONS`、完整媒体通知、队列恢复或播放进程恢复；这些属于 M3。
- 自建歌单、收藏页面、最近播放页面和设置页面；这些属于 M4。M2 只建立收藏与最近播放的数据结构。
- 扫描目录偏好和最小时长的持久化设置；M2 的筛选只影响当前曲库查询。
- 在线歌词、在线元数据、封面增强或任何联网能力；这些属于 M5。
- Baseline Profile、正式启动优化、内存专项和稳定性长测；这些属于 M6。
- 为 M2 新增工程模块、Paging、FTS 或把 `core:data` 继续拆分。1000 首目标不需要这些复杂度。

## 3. 已确认的关键决策

1. M2 采用四个连续、小范围 PR，而不是一个纵向大 PR。
2. 顺序固定为 Room → MediaStore → 权限与状态 UI → 搜索与多视图。
3. 每个实现任务交给新的子代理；每个 PR 依次完成需求符合性审查和代码质量/稳定性审查。
4. Demo 曲只作为兜底，不与真实本地曲目混合展示。
5. 无音频权限时，即使 Room 中有旧本地缓存，也不把可能无法读取的 Content URI 暴露为可播放曲目；数据库记录保留，界面切换到 Demo。
6. 有权限且扫描失败时优先展示旧缓存；只有没有可用旧缓存时才展示 Demo。
7. `POST_NOTIFICATIONS` 延后到 M3，与完整系统媒体体验一起设计和验证。
8. M2 不新增模块，继续沿用 M1 已建立的模块边界和正式 Media3 依赖。

## 4. 模块与组件边界

### 4.1 `:app`

- 在 Manifest 中声明版本匹配的音频读取权限。
- 承载 Activity Result 权限 launcher 和应用级 Hilt 装配。
- 不直接执行 MediaStore 查询或数据库事务。

### 4.2 `:core:common`

- 保留纯 Kotlin 领域 `Track`，补充曲库所需但与 Android 无关的字段和类型。
- 定义查询、排序、筛选、分组摘要、扫描统计和类型化错误等领域模型。
- 不暴露 Room Entity、Cursor、Uri、MediaStore 常量或 Compose 类型。

### 4.3 `:core:data`

按包边界组织，不增加新 Gradle 模块：

- `local.db`：Room Entity、DAO、Database、migration 和映射。
- `local.mediastore`：平台查询、Cursor 读取、存储卷枚举和平台模型。
- `scan`：扫描策略、单任务编排、进度、检查点和结果。
- `repository`：Room 曲库流、Demo 兜底策略和领域查询实现。
- `permission`：权限状态读取接口及最小的“曾经请求过”持久化记录。

Room 是真实本地曲库的单一 UI 读取来源；MediaStore 是设备文件的权威来源；Demo 是独立的只读兜底数据源。

### 4.4 `:feature:library`

- `LibraryViewModel` 组合权限状态、扫描状态、查询条件和 Room 曲库流。
- Compose 只发出用户 Action、渲染不可变 `UiState` 和消费一次性 effect。
- 权限请求、系统设置跳转和 Snackbar 是一次性 effect，不存入持久内容状态。
- Feature 不读取 Room Entity、Cursor 或 MediaStore 平台类型。

### 4.5 现有播放链路

M2 保留 `TrackRepository.observeTracks()` 作为现有曲库/播放入口的兼容门面，并增加带查询条件的曲库 API。播放仍接收领域 `Track`；M2 不改变 `PlaybackService`、MediaSession 所有权或播放器恢复策略。

## 5. Room 数据设计

### 5.1 `tracks`

主要字段：

| 字段 | 用途 |
| --- | --- |
| `mediaId` | 领域主键，由编码后的 `volumeName + mediaStoreId` 确定性生成 |
| `volumeName`、`mediaStoreId` | MediaStore 复合身份，建立唯一索引 |
| `contentUri` | 播放所需 Content URI |
| `displayName`、`title`、`artist`、`album` | 原始本地元数据；缺失值保持可区分，由 UI 提供本地化占位文案 |
| `albumId`、`artworkUri` | 可用时保存本地专辑身份和封面 URI |
| `durationMs`、`mimeType`、`sizeBytes` | 展示、排序和筛选 |
| `folderKey`、`folderDisplayName` | 跨版本归一后的文件夹分组；不要求公开绝对路径 |
| `dateAddedSeconds`、`dateModifiedSeconds` | 最近添加排序和旧系统增量判断 |
| `searchText` | 使用 `Locale.ROOT` 规则生成的本地检索文本 |
| `isAvailable` | 文件当前是否仍存在且可由本次授权访问 |
| `lastSeenScanToken` | 删除对账和扫描一致性标记 |

v1 物理字段契约：

| 字段 | SQLite/Room 契约 |
| --- | --- |
| `mediaId` | `TEXT NOT NULL PRIMARY KEY` |
| `volumeName` | `TEXT NOT NULL` |
| `mediaStoreId` | `INTEGER NOT NULL` |
| `contentUri` | `TEXT NOT NULL` |
| `displayName`、`title`、`artist`、`album` | 可空 `TEXT`；UI 负责未知值文案 |
| `albumId` | 可空 `INTEGER` |
| `artworkUri`、`mimeType` | 可空 `TEXT` |
| `durationMs`、`sizeBytes` | `INTEGER NOT NULL DEFAULT 0`，映射时把非法负值归零 |
| `folderKey`、`folderDisplayName` | 可空 `TEXT`；空值归入“未知文件夹”分组 |
| `dateAddedSeconds`、`dateModifiedSeconds` | `INTEGER NOT NULL DEFAULT 0` |
| `searchText`、`titleSortKey`、`artistSortKey`、`albumSortKey`、`folderSortKey` | `TEXT NOT NULL DEFAULT ''`，由 mapper 确定性生成 |
| `metadataFingerprint` | `TEXT NOT NULL DEFAULT ''`，覆盖会影响 UI/播放映射的本地投影字段 |
| `isAvailable` | `INTEGER NOT NULL DEFAULT 1`，由 Room 映射为 Boolean |
| `lastSeenScanToken` | `TEXT NOT NULL` |

Demo 曲不写入 Room，因此 v1 不需要 `source` 列。

v1 索引固定为：唯一索引 `(volumeName, mediaStoreId)`；查询索引 `(isAvailable, titleSortKey, mediaId)`、`(isAvailable, artistSortKey, mediaId)`、`(isAvailable, albumSortKey, mediaId)`、`(isAvailable, folderSortKey, mediaId)`、`(isAvailable, dateAddedSeconds, mediaId)`、`(isAvailable, durationMs, mediaId)`；扫描对账索引 `(volumeName, lastSeenScanToken)`。所有排序最终追加 `mediaId` 作为确定性 tie-breaker。

搜索目标只有 1000 首，M2 使用转义后的 Room/SQLite `LIKE` 查询，不引入 FTS。M6 依据真实性能数据决定是否需要进一步优化。

设备文件消失时，记录只标记为不可用，不立即删除。相同稳定 ID 重新出现时更新元数据并恢复可用状态，从而保留未来的收藏、歌单和最近播放关系。

### 5.2 `favorites`

- `trackId TEXT NOT NULL PRIMARY KEY`，外键指向 `tracks.mediaId`。
- `addedAtEpochMs INTEGER NOT NULL`：收藏时间。
- 外键删除策略为 `ON DELETE CASCADE`；扫描只标记曲目不可用而不删除，所以卸载、撤权和重新扫描不会丢失收藏。

M2 只建立 Entity、DAO 和关系测试，不交付收藏业务界面。

### 5.3 `recent_plays`

- `trackId TEXT NOT NULL PRIMARY KEY`：每首曲目一条聚合记录，外键指向 `tracks.mediaId`，`ON DELETE CASCADE`。
- `lastPlayedAtEpochMs INTEGER NOT NULL DEFAULT 0`：最近播放时间。
- `playCount INTEGER NOT NULL DEFAULT 0`：播放次数。
- `lastPositionMs INTEGER NULL DEFAULT NULL`：为父级规格保留；其写入和恢复语义由 M3 最终定义。

M2 不把播放历史写入接入现有播放器，避免提前侵入 M3。

### 5.4 `scan_checkpoints`

每个存储卷一条记录：

- `volumeName TEXT NOT NULL PRIMARY KEY`。
- `mediaStoreVersion TEXT NULL`：最近一次成功提交时验证过的 MediaStore version。
- `generationUpperBound INTEGER NULL`：API 30+ 最近一次成功事务已消费的 generation 安全上界。
- `lastFullScanEpochMs INTEGER NOT NULL DEFAULT 0`。
- `lastSuccessfulScanEpochMs INTEGER NOT NULL DEFAULT 0`。
- `lastScanToken TEXT NOT NULL`。
- `isMounted INTEGER NOT NULL DEFAULT 1`，由 Room 映射为 Boolean。
- `lastDiscoveredCount`、`lastInsertedCount`、`lastUpdatedCount`、`lastUnavailableCount` 均为 `INTEGER NOT NULL DEFAULT 0`。

只有对应存储卷数据库提交成功后才能推进检查点。

### 5.5 Schema 与 migration

- `exportSchema = true`，导出目录进入版本控制。
- Release 不允许 destructive migration。
- 每次版本变化必须同时提交 migration 和迁移测试。
- 外键、唯一约束、索引和默认值均由自动化测试覆盖。

M2-A 建立初始 v1 schema、schema 导出和 `MigrationTestHelper` 基线；初始版本没有虚构的旧版本迁移。若 M2-B–D 或后续里程碑首次提升数据库版本，必须从当时已发布的前一 schema 增加真实 migration 测试。

## 6. 权限状态机

领域权限状态：

- `Checking`
- `NotRequested`
- `Granted`
- `DeniedCanRetry`
- `DeniedPermanently`

版本映射：

- API 33+ 请求 `READ_MEDIA_AUDIO`。
- API 26–32 请求 `READ_EXTERNAL_STORAGE`。
- M2 不请求 `POST_NOTIFICATIONS`。

永久拒绝不能只依赖 `shouldShowRequestPermissionRationale()`，因为首次请求前它同样可能返回 false。M2 使用最小的 Proto DataStore 权限历史记录区分 `NeverRequested`、`RequestedBefore`、`GrantedBefore` 和 `PermanentlyDenied`：

- `NeverRequested` 且未授权：`NotRequested`。
- 权限 launcher 返回拒绝且仍应显示说明：记录 `RequestedBefore`，进入 `DeniedCanRetry`。
- 权限 launcher 返回拒绝且不再显示说明：记录 `PermanentlyDenied`，进入 `DeniedPermanently`。
- 曾经是 `Granted`，后来在生命周期检查或扫描 `SecurityException` 后发现已撤销/自动重置：记录保持 `GrantedBefore`，先进入 `DeniedCanRetry`；用户再次主动申请仍立即被拒且无说明时，才进入 `DeniedPermanently`。
- 从系统设置返回后重新读取真实系统授权状态，不信任内存中的旧结果。

状态真值表：

| 系统授权 | 持久历史 | 触发/`shouldShowRationale` | 输出状态与历史更新 |
| --- | --- | --- | --- |
| 已授权 | 任意 | 冷启动、恢复或请求结果 | `Granted`；写入 `GrantedBefore` |
| 未授权 | `NeverRequested` | 冷启动或恢复 | `NotRequested`；历史不变 |
| 未授权 | `RequestedBefore` | rationale = true | `DeniedCanRetry`；历史不变 |
| 未授权 | `RequestedBefore` | rationale = false | `DeniedPermanently`；写入 `PermanentlyDenied` |
| 未授权 | `GrantedBefore` | 冷启动、恢复或扫描 `SecurityException` | `DeniedCanRetry`；保留 `GrantedBefore` |
| 未授权 | 任意非永久状态 | launcher 拒绝且 rationale = true | `DeniedCanRetry`；写入 `RequestedBefore` |
| 未授权 | 任意非永久状态 | launcher 拒绝且 rationale = false | `DeniedPermanently`；写入 `PermanentlyDenied` |
| 未授权 | `PermanentlyDenied` | 冷启动或恢复 | `DeniedPermanently`；历史不变 |

权限 launcher 位于 Compose/Activity 边界；ViewModel 只发出请求 effect 并接收结果。旋转、重组或重复点击不得同时发起多个权限请求。

## 7. 启动与内容来源切换

启动路径不允许同步扫描、同步数据库读取或阻塞首帧：

1. ViewModel 立即观察 Room 和权限状态。
2. `Granted` 且 Room 有可用曲目：立即显示缓存，同时后台发起单任务扫描。
3. `Granted` 但 Room 无可用曲目：显示 Demo 和“未发现本地音乐/正在扫描”说明。
4. `NotRequested`、`DeniedCanRetry` 或 `DeniedPermanently`：不查询 MediaStore，显示 Demo 与对应授权操作。
5. 扫描成功：Room Flow 推送新结果；只要存在真实曲目，整批 Demo 立即退出内容列表。
6. 扫描失败且有旧缓存：保持缓存，使用内联提示或 Snackbar 告知失败。
7. 扫描失败且无缓存：保持 Demo，提供重新扫描或权限修复入口。
8. 权限在运行中被撤销：停止扫描，不修改未完成存储卷的数据；保留数据库但切换到 Demo。

真实曲目和 Demo 曲永不混排。内容来源切换必须是一次完整、可解释的状态变化。

## 8. MediaStore 扫描设计

### 8.1 稳定身份

领域 ID 使用带版本前缀的确定性格式，由安全编码后的 `volumeName` 和十进制 row ID 组成，避免分隔符冲突。不同存储卷上相同 row ID 不会碰撞。

平台层负责从 `volumeName + rowId` 构建 Content URI。Feature 和播放器只接触领域 ID 与最终 URI，不拼接 MediaStore 路径。

API 29+ 枚举系统报告的外部存储卷；API 26–28 使用旧版 external 集合并映射为固定卷名。平台能力差异由 gateway 吸收，不能虚构旧系统不提供的独立卷身份。

### 8.2 扫描策略

- API 30+：MediaStore version 不变且检查点有效时，使用 generation 查询新增或修改记录。查询前同时捕获 `versionStart` 和 `generationUpperBound`，只消费上次检查点之后且不高于该安全上界的变化；查询结束再次读取 version，version 已变化则丢弃本卷快照并安排全量扫描。只有本卷短事务成功后才把检查点推进到该上界；提交后若当前 generation 已超过上界，排队执行下一次增量扫描，因此扫描期间发生的变化不会被越过。
- API 26–29：每次读取当前卷会影响 UI/播放映射的完整元数据投影、ID 和 `DATE_MODIFIED`，计算 `metadataFingerprint` 后与 Room 比较，只写入实际变化行。算法不使用会漏掉同秒更新或时间回拨的“全局最大时间”水位；同一行在同一秒内发生可见元数据变化仍会被 fingerprint 检出。首次扫描、用户显式重扫或快照不一致时执行完整覆盖与对账。
- 增量元数据扫描之后仍执行当前存储卷的轻量 ID 对账，以识别已删除、移出或失去访问权限的文件。
- 首次扫描、MediaStore version 改变、检查点损坏或用户显式要求时执行全量扫描。

只有存储卷枚举本身完整成功后，扫描器才把“本次系统报告的卷集合”与数据库中的历史卷集合对账。已卸载或移除的卷在独立短事务中标记为未挂载，其曲目变为不可用；重新挂载且卷身份一致时，扫描会用原稳定 ID 恢复曲目。枚举失败或权限丢失时不得把历史卷误判为已移除。

扫描只读取符合音频条件的 MediaStore 行；持久化扫描条件固定，不受 UI 当前搜索和筛选影响。过滤规则和缺失列通过版本化平台映射处理，不能让 Android 版本判断进入 Feature。

### 8.3 事务边界

不在持有 Room 写事务时遍历 Cursor：

1. 在 IO dispatcher 中完整读取某个存储卷并构建平台无关快照。
2. 只有查询完整成功后，才为该存储卷开启短 Room 事务。
3. 在同一事务中 upsert 新增/变化记录、恢复重新出现的记录、标记本卷缺失记录不可用并推进检查点。
4. 查询失败、取消、权限撤销或 Cursor 不完整时，不进入提交事务，也不标记旧记录不可用。

事务以存储卷为隔离单元。一个存储卷失败时，其他完整存储卷可以提交；失败存储卷继续保留上次成功缓存，并在总体结果中标记为部分失败。这是对父级规格“扫描事务”的明确细化：原子性保证在每个可独立查询的存储卷内，而不是用一个长事务包住所有 Cursor 和所有存储卷。

### 8.4 并发与取消

- 扫描器为 single-flight：同一时间最多一个扫描作业。
- 扫描作业由应用级数据 scope 持有，不依附某个 Composable 或页面 ViewModel；配置变化和页面切换不会重启扫描。进程可能在没有执行协程清理的情况下被系统终止，因此一致性依靠 SQLite 事务恢复和“检查点只随成功事务推进”，并在重启后重新扫描验证。
- 同等级自动扫描请求合并到当前作业。用户请求的全量重扫具有更高优先级：若当前已经是全量扫描则加入当前作业；若当前是自动增量扫描则设置唯一的 `pendingFullRescan`，在当前作业结束后再执行一次全量扫描，多个点击只保留一个待执行请求。权限丢失时清除待执行请求并回到权限状态机。
- 进度至少包含当前存储卷、已处理数量、可获得时的总数和最终新增/更新/不可用统计。
- 取消是正常终止，不显示数据库错误；已完整提交的存储卷保持有效，正在查询的存储卷不提交。

## 9. 曲库查询与界面状态

### 9.1 查询模型

曲库查询由稳定领域值对象表达：

- 文本搜索：标题、艺人、专辑和文件夹。
- 排序：标题、艺人、专辑、添加时间和时长；支持适用的升序/降序。
- 筛选：MIME/文件类型、最小时长和文件夹。
- 分组：歌曲、专辑、艺人和文件夹。

DAO 接收经过标准化和转义的参数，不拼接用户输入 SQL。默认查询只返回 `isAvailable = true` 的真实曲目。

专辑、艺人和文件夹首页使用 Room 聚合摘要；进入分组后再查询对应曲目。列表使用稳定 `mediaId` 作为 Compose key，所有相同主排序值最终都按 `mediaId` 排序，避免 Room 更新后列表顺序抖动。

### 9.2 `LibraryUiState`

持久页面状态至少包含：

- 当前内容来源：Demo 或本地缓存。
- 权限状态。
- 扫描状态与可选进度。
- 当前视图、搜索文本、排序和筛选。
- 内容、空态原因和是否存在可恢复的旧缓存。

一次性 effect 至少包含：

- 发起系统权限请求。
- 打开应用系统设置。
- 展示可恢复错误 Snackbar。

已有内容时，扫描进度和失败不得用全屏页面覆盖内容。无内容时才使用完整空态或错误说明。

## 10. 错误处理

数据层返回类型化错误，不把异常消息直接传到 UI：

| 错误 | 数据行为 | UI 行为 |
| --- | --- | --- |
| 未授权/权限撤销 | 不查询或终止当前卷，不推进检查点 | Demo + 授权或设置入口 |
| MediaStore 查询失败 | 不提交失败卷，不标记记录不可用 | 有缓存时内联提示；无缓存时 Demo + 重试 |
| Room 写入失败 | 当前卷事务回滚，检查点不推进 | 保留旧缓存并显示脱敏错误码 |
| 扫描取消 | 当前未提交卷保持原样 | 返回稳定内容状态，不显示错误页 |
| 部分存储卷失败 | 成功卷提交，失败卷保留旧缓存 | 内容继续可用并提示部分失败 |
| 空曲库 | 成功提交空结果/不可用标记 | Demo + 未发现本地音乐 + 重扫入口 |
| 未知错误 | 不清库、不静默吞掉 | 可重试提示和脱敏诊断码 |

日志不得记录绝对文件路径、曲名等用户媒体信息。M2 只记录类型化错误码和必要的匿名统计；完整诊断包属于 M6。

## 11. 四个连续 PR

设计规格提交不计入以下四个实现 PR。

### 11.1 M2-A：Room 曲库缓存基础

包含：

- Room/KSP 依赖和构建配置。
- `tracks`、`favorites`、`recent_plays` 及必要的检查点 schema。
- DAO、Database、Entity/领域映射、schema 导出和 migration 测试基础。
- Room-backed `TrackRepository`，保留现有无参数观察 API。
- Room 无可用本地曲目时的 Demo 兜底。

不包含：MediaStore、运行时权限、扫描 UI、搜索或多视图。

### 11.2 M2-B：MediaStore 扫描器

包含：

- MediaStore gateway、存储卷枚举和跨版本映射。
- 稳定 ID、增量/全量策略、ID 对账、逐卷事务和扫描检查点。
- single-flight、取消、进度、统计和类型化扫描错误。
- fake MediaStore gateway 单元测试，以及 fake gateway 驱动真实 in-memory Room/SQLite 的事务集成测试矩阵。

不包含：系统权限弹窗或 Feature 页面改造。测试通过注入的权限前置条件调用扫描器。

### 11.3 M2-C：权限与扫描交互

包含：

- Manifest 权限、权限状态读取、请求历史和系统设置返回检查。
- ViewModel 编排、Activity Result effect 和自动/手动扫描触发。
- 未授权、可重试拒绝、永久拒绝、空曲库、扫描中、失败和重新扫描 UI。
- Demo/缓存切换规则及 Compose 状态测试。

不包含：`POST_NOTIFICATIONS` 或系统媒体通知改造。

### 11.4 M2-D：搜索与完整曲库视图

包含：

- 歌曲、专辑、艺人和文件夹视图及分组详情。
- 搜索、排序、筛选和状态恢复。
- 1000 首固定 fixture、查询正确性和基本交互可用性验证。
- API 26/30/33/36 最终设备矩阵和 M2 证据整理。

不包含：歌单、收藏/最近播放业务页面、在线元数据或正式性能结论。

## 12. 测试策略

### 12.1 M2-A

- Entity/领域映射测试，包括缺失元数据和不可用记录。
- DAO 插入、更新、排序、唯一约束、外键和事务测试。
- 数据库关闭重建后缓存仍存在。
- Demo 兜底只在没有可用本地内容时生效。
- schema 导出存在且 migration test 基础可执行。

### 12.2 M2-B

- 同一 row ID 在不同存储卷不碰撞。
- 首次全量、generation 增量、修改时间回退和强制全量。
- 在 generation 上界捕获后注入新变化，该变化不得被当前检查点越过，并在后续增量扫描出现；扫描期间 MediaStore version 改变时丢弃本卷快照。
- API 26–29 同秒修改和系统时间回拨不会因为全局最大时间水位而漏项。
- 新增、修改、删除、移出、重新出现和元数据缺失。
- 查询失败或取消不会错误标记旧数据不可用。
- Room 写入失败回滚并且检查点不推进。
- 多卷部分失败、重复扫描合并和权限中途撤销。
- 自动增量运行时多次请求手动全量，只在当前作业之后追加一次全量扫描；当前已是全量时不重复排队。
- 整卷卸载会把该卷曲目标记不可用；重新挂载相同卷后恢复原稳定 ID。
- fake MediaStore gateway 配合真实 in-memory Room/SQLite，在 upsert 后、不可用标记后和检查点推进前注入失败，证明数据变化与检查点属于同一事务并完整回滚；fake DAO 只用于纯编排单元测试，不能代替这项证据。

### 12.3 M2-C

- 权限状态机表驱动单元测试。
- 首次请求不会被误判为永久拒绝。
- 拒绝、永久拒绝、从设置重新授权、`GrantedBefore` 运行时撤销和权限自动重置。
- 重组/重复点击只产生一次权限请求 effect。
- Demo、缓存、加载、空态和失败但有内容的 Compose 测试。

### 12.4 M2-D

- 搜索转义、空白规范化、大小写和中英文文本。
- 所有排序方向和筛选组合的 Repository/DAO 测试。
- 专辑、艺人和文件夹聚合及详情一致性。
- 1000 首 fixture 下搜索、切换视图、稳定 key 和滚动到末项正常工作。
- 1000 首只设“基本可用”门槛；启动、帧耗时和内存量化留到 M6。
- 在设备 MediaStore 中写入一首合法测试音频，扫描后点击该曲目必须同时满足：领域 `PlaybackState.currentTrackId` 等于扫描所得 `mediaId`、`isPlaying = true`，且活动 MediaSession/系统控制器报告 `PLAYING`。撤销权限后，该曲目不得出现在可点击的本地内容中。

### 12.5 每个 PR 的统一门禁

1. 新实现子代理按测试先行完成范围内工作。
2. 运行受影响模块测试，再运行仓库完整 `test testDebugUnitTest lintDebug assembleDebug` 门禁。
3. 第一阶段由独立子代理检查规格符合性和越界。
4. 修复所有 Critical/Important 后，第二阶段由另一独立子代理检查代码质量、并发、事务和稳定性。
5. 再次运行完整门禁并确认 GitHub Actions 全绿。
6. 通过 PR 合并到 `main`，确认 main CI 后才开始下一个 PR。

## 13. M2 最终验收矩阵

| 场景 | 预期 |
| --- | --- |
| API 26、30 首次安装 | 请求 `READ_EXTERNAL_STORAGE`，授权后可扫描 |
| API 33、36 首次安装 | 请求 `READ_MEDIA_AUDIO`，不请求通知权限 |
| 首次拒绝 | Demo 可播放，提供再次授权说明，不循环弹窗 |
| 永久拒绝 | Demo 可播放，提供系统设置入口 |
| 从设置重新授权 | 返回后自动重新检查并可扫描 |
| 已有缓存冷启动 | 首屏先展示 Room 缓存，扫描不阻塞启动 |
| 本地无音乐 | 展示 Demo 和明确空曲库/重扫入口 |
| 扫描失败且有缓存 | 继续显示缓存并给出非阻塞提示 |
| 扫描失败且无缓存 | 显示 Demo 和重试入口 |
| 扫描中取消或进程终止 | 已提交卷保持一致，未完成卷不被错误清空 |
| 文件新增/修改/删除/重新出现 | Room 与可用状态正确更新，稳定 ID 不变 |
| 整个存储卷卸载/重新挂载 | 卸载后该卷曲目不可用；同一卷重挂载后恢复稳定 ID |
| 多存储卷部分失败 | 成功卷更新，失败卷保留旧缓存 |
| 1000 首 fixture | 搜索、排序、筛选、分组和滚动基本可用 |
| 真实本地曲目播放 | 点击扫描所得 Content URI 后 `currentTrackId` 匹配、`isPlaying = true`、活动 MediaSession 为 `PLAYING`；撤权后不再暴露 |
| 数据库重建/升级基线 | v1 schema、索引、约束和 MigrationTestHelper 基线通过；发生真实版本升级时迁移矩阵通过 |

## 14. 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| Android 版本和厂商 MediaStore 列差异 | 平台 gateway 集中版本判断；缺失列安全回退；API 设备矩阵 |
| generation 无法直接报告删除项 | 每次增量后执行 ID 对账；版本变化时全量扫描 |
| 权限撤销导致旧 URI 不可播放 | 无权限时隐藏真实缓存并切换 Demo；重新授权后扫描 |
| 长事务阻塞或取消后半写 | Cursor 查询与 Room 写事务分离；逐存储卷短事务 |
| 多次自动/手动扫描竞争 | single-flight、同级合并和高优先级全量重扫排队 |
| 搜索方案过度设计 | 1000 首阶段使用 Room `LIKE`；M6 用测量结果决定优化 |
| M2 偷带播放、歌单或性能工作 | 四个 PR 的“不包含”清单作为审查硬边界 |

## 15. 完成定义

M2 只有在以下条件全部满足后才能标记完成：

- 四个实现 PR 按顺序合并，且每个 PR 完成两阶段独立审查。
- 本地完整门禁和对应 GitHub Actions 全绿。
- API 26/30/33/36 权限与扫描路径有可复现验收记录。
- 中断、失败、删除对账和旧缓存保留均有自动化测试或设备证据。
- 1000 首 fixture 的查询和基本 UI 可用性通过。
- `main` 可全新 clone、构建、安装并演示 Demo 与真实本地曲库两条路径。
- 没有把 M3–M6 的功能或未经批准的新模块带入 M2。

本规格一经用户审阅批准，即作为 M2 实施计划、子代理任务和两阶段审查的唯一范围基线。实现中若发现必须改变这里的产品行为或 PR 边界，应先更新规格并重新取得用户确认。
