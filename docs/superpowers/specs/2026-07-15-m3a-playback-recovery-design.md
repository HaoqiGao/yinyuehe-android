# M3-A 可恢复播放内核设计

日期：2026-07-15
状态：已批准
基线：`origin/main@6da64dc1d5202ebfc8db7e3edb79febdf8354793`
对应路线图：`M3 播放会话、后台控制与恢复`

## 1. 背景与目标

当前工程已通过 `MediaLibraryService` 持有唯一 ExoPlayer 与 MediaSession，支持后台播放、系统媒体控制、整队起播、随机播放、上一首/下一首、进度跳转以及队列追加、删除和跳转。实时 `PlaybackState` 直接来自 Media3 callback，已具备单一事实来源的基础。

但是，Service 每次创建都从空队列开始；应用还没有 Proto DataStore、跨进程回收恢复、repeat 模式、队列移动、类型化播放错误或有界重连。现有“重启恢复”证据只覆盖 Room 收藏和最近播放，不代表播放队列恢复。

M3-A 的目标是建立一个由 Service 拥有、可测试、不自动出声的恢复内核，并补齐与恢复一致性直接相关的队列模式、错误跳过与 Controller 重连。

## 2. 范围

### 2.1 包含

- 单例 Proto DataStore 及损坏恢复。
- 有序队列、当前索引、位置、shuffle 和 repeat 的原子快照。
- Demo 与当前可用本地曲目的有序解析，保留重复项。
- Service 异步恢复与序列化快照写入。
- repeat `OFF / ALL / ONE`、shuffle 及队列移动命令。
- Media3 错误分类、单项跳过、防循环与队列保留。
- Controller 单一 in-flight 连接、有界指数退避与过期 callback 隔离。
- 现有播放页中的模式、可访问队列移动和类型化错误反馈。
- JVM、Room/DataStore 集成、API 36 设备恢复验证和独立验收记录。

### 2.2 不包含

- 不拆分 `:feature:player`，不增加迷你播放器。
- 不实现歌词、在线元数据、自建歌单或设置页。
- 不把队列拖拽手势和播放页视觉重构混入本里程碑；M3-A 只交付底层 `moveQueueItem(from, to)` 及可访问的上移/下移操作。
- 不发布正式签名 APK，不将现有真机待验项改为通过。
- 不使用 Room 存储播放快照，不让 UI 或 Controller 直接读写快照。

## 3. 核心原则

1. **Service 唯一所有权**：`PlaybackService` 是恢复、快照捕获和播放错误处理的唯一业务边界。
2. **Player 唯一实时事实来源**：Proto 只在新 Player 启动时提供一次恢复输入；恢复后的 UI 只消费 Media3 callback。
3. **用户命令优先**：慢恢复不能覆盖启动后已发生的新起播或队列修改。
4. **不自动播放**：恢复可以设置队列并 `prepare()`，但必须显式保持 `playWhenReady=false`。
5. **全快照原子替换**：不分开写入索引、位置或模式，避免跨字段不一致。
6. **故障局部化**：一首曲目损坏、一条 ID 无法解析或一次快照写入失败，不得清空其余队列或使 Service 崩溃。
7. **不阻塞主线程**：DataStore、Room 解析和快照写入均异步完成；Service 销毁不使用无界 `runBlocking`。

## 4. 模块与契约

### 4.1 `:core:common`

新增不依赖 Android UI、Room、DataStore 或 Media3 类型的域契约：

- `PlaybackSnapshot`：schema 版本、`List<TrackId>`、当前索引、位置、shuffle 与 repeat。
- `PlaybackSnapshotReadResult`：`Usable(snapshot)` 或 `IncompatibleVersion(version)`，让调用者在不依赖 protobuf 类型的前提下区分可恢复数据与不可覆盖的未来版本。
- `PlaybackQueueResolution`：按原位置返回已解析曲目、永久缺失项和可选的临时阻断原因（首版为 `PERMISSION_DENIED`）。
- `PlaybackRepeatMode`：`OFF`、`ALL`、`ONE`。
- `PlaybackError`：类型、Media3 数值错误码、可选 `TrackId`；不包含原始异常字符串。
- `PlaybackConnectionError`：首版只包含 `RETRIES_EXHAUSTED`，与曲目级 `PlaybackError` 分开。
- `PlaybackSnapshotStore`：提供挂起的读取结果与原子写入。
- `PlaybackQueueResolver`：把有序 ID 恢复为 `PlaybackQueueResolution`；瞬时 Room/IO 异常向恢复协调器传播，不能伪装成“全部永久缺失”。

以下是这两个集合持有型契约的规范性值语义：

- `PlaybackSnapshot` 与 `PlaybackQueueResolution` 必须是不可变值快照。所有公共构造与 `copy` 路径都必须对集合输入做防御性复制，并且只能暴露不可修改的 `List`；构造校验、属性读取、相等性与哈希必须观察同一份内部快照。
- 公共契约保留当前的命名构造参数、属性名称与 `List` 属性类型、默认参数，以及 `copy`、`componentN`、`equals`、`hashCode`、`toString` 的值语义。
- Kotlin `data` modifier 与反射元数据 `KClass.isData` 明确不属于公共契约。实现使用普通 final class，以避免 `data class` 主构造与生成的浅 `copy` 保存调用方可变集合别名。

`core:player` 与 `core:data` 都只依赖这些契约，不互相引入，避免模块环。

### 4.2 `:core:data`

- 实现 Proto serializer、corruption handler 和进程级单例 DataStore。
- 实现 `PlaybackSnapshotStore`，将整个域快照映射到一个 protobuf message。
- 实现 `PlaybackQueueResolver`：Demo ID 由 `DemoTrackCatalog` 解析；本地 ID 去重后以每批最多 900 个 bind 参数分块查询 Room，再按原始列表重建顺序与重复项，避免超出 SQLite 变量上限。
- 本地项只使用 `isAvailable=1` 的记录。当待解析列表包含本地 ID 且当前 Android 音频读取权限缺失时，仍解析无需权限的 Demo，但结果标记临时 `PERMISSION_DENIED`，不得把未解析的本地 ID 当作永久删除或回写覆盖；纯 Demo 快照不受该门禁影响。
- 解析阶段不为每首歌同步打开 content URI；过期 MediaStore 行由实际播放错误策略局部跳过。

### 4.3 `:core:player`

- `PlaybackRestoreCoordinator`：读取、规范化、解析并生成恢复计划，只在无新用户变更时应用。
- `PlaybackSnapshotWriter`：捕获 Player 全快照，以单一写入循环串行、合并并持久化。
- `PlaybackFailurePolicy`：将 Media3 错误映射为域错误，追踪本轮已失败 occurrence，决定跳过、暂停或等待重试。
- `ControllerConnectionCoordinator`：管理唯一连接 Future、退避与 generation。
- `RestorePersistenceGate`：维护 `RESTORE_PENDING / APPLIED / SUPERSEDED / INCOMPATIBLE / FAILED`，在恢复结论明确前阻止任何空 callback 或销毁快照覆盖已存数据。
- 内部窄 `RestorablePlayer` 适配器隔离恢复编排和真实 ExoPlayer，使时序、竞态与错误策略可以用确定性 Fake 测试。

### 4.4 现有 UI

`PlaybackController` 扩展域命令与事件，但 UI 不读 DataStore：

- `setRepeatMode(mode)`
- `moveQueueItem(fromIndex, toIndex)`
- 已有 shuffle 命令继续使用
- `PlaybackState` 增加 repeat、曲目级 `playbackError`、独立的 `connectionError`，以及权限部分恢复时的 `queuePersistenceLimited`
- 非重放的 `PlaybackController.notices` 发送一次性 `PlaybackNotice.TrackSkipped`，用于告知已局部跳过

成功命令必须先由 Media3 执行，再经 callback 更新 UI；ViewModel 不做乐观的第二份播放状态。

## 5. Proto 快照设计

protobuf 消息包含：

| 字段 | 语义 |
| --- | --- |
| `schema_version` | 快照 schema 版本，首版为 1 |
| `media_ids` | 有序稳定 `TrackId` 字符串，允许重复 |
| `current_index` | 原队列当前索引，空队列用 -1 |
| `position_ms` | 非负播放位置 |
| `shuffle_enabled` | 随机模式 |
| `repeat_mode` | `OFF / ALL / ONE` |

快照不包含 URI、标题、艺术家、文件路径、MediaItem、异常文本或稳定设备标识。数据只位于应用私有目录，没有上传链路。稳定 `TrackId` 在本地仍可关联具体曲目，因此不将快照描述为匿名数据。

读取时统一规范化：

- protobuf 默认实例（`schema_version=0` 且其余字段均为默认值）映射为当前版本空快照，用于首次安装；不得把它当成不兼容文件。
- `schema_version=0` 但包含非默认载荷、负版本或高于当前支持版本都返回 `IncompatibleVersion`，不猜测迁移。首版没有其他可迁移的旧正版本。
- 空白 `media_ids` 安全丢弃，不构造会抛异常的 `TrackId`。如果原当前项仍是有效非空 ID，新索引按它之前保留的项数重算并保留位置；如果原当前项为空白，选后继再前驱且位置归零。
- 空队列强制 `currentIndex=-1`、`positionMs=0`。
- 非空队列的越界索引夹取到有效范围。
- 负位置归零。
- 未知 repeat 值归为 `OFF`。
- 损坏 protobuf 通过 corruption handler 替换为空快照，不导致 Service 启动失败。
- `IncompatibleVersion` 使恢复侧按空队列运行，但启动期空 callback 和 Service 最终快照都不能覆盖原文件；只有用户之后发起且由 Media3 确认成功的队列内容变更（整队设置、添加、移除或移动），才进入 `SUPERSEDED` 并建立当前版本快照。权限部分恢复采用下节更严格的整队替代规则。

## 6. 恢复时序与竞态

1. Service 同步创建 ExoPlayer 与 MediaLibrarySession，显式保持暂停；持久化门禁从第一条 Player callback 之前就进入 `RESTORE_PENDING`。
2. `RESTORE_PENDING` 期间可以在内存观察 Player，但禁止 callback、5 秒采样和 `onDestroy` 最终快照写入；因此初始空 timeline 与提前销毁都不能覆盖已有文件。
3. 记录当前 `mutationGeneration`，异步读取与解析快照。解析结果按原 ID 列表重建，保留重复 occurrence 和每项的原始索引。
4. 回到 Player 所在 looper 前再次校验 generation 未变、没有新队列变更且当前 timeline 仍为空。
5. 恢复完成前，Controller 发起且 Media3 确认成功的队列内容变更时立即进入 `SUPERSEDED`：旧 read/resolve 结果永久失效，门禁只对这份用户队列解除并提交它的全快照。repeat、seek、被拒命令或启动空 callback 不构成抢占。
6. 对 `Usable` 且解析无临时阻断的结果，进入 `applyingRestore` 临界区：暂停中间 callback 的写入和 generation 增长，设置 media items、shuffle、repeat 与位置，调用 `prepare()`，最后再次确保 `playWhenReady=false`。完整应用后进入 `APPLIED`，门禁解除并提交一份规范化全快照。
7. 对 `IncompatibleVersion` 进入 `INCOMPATIBLE`；对 DataStore/Room/IO 瞬时异常进入 `FAILED`。两者都不应用未知或不完整状态、不覆盖原文件，并持续抑制写入，直到后续成功的队列内容变更转入 `SUPERSEDED`。
8. 权限缺失属于 `FAILED(PERMISSION_DENIED)`：可以只应用已安全解析的 Demo 子集并保持暂停，但仍保留原 Proto 且不写入这个部分结果。该状态下，添加、移除或移动可见 Demo 都不得解除门禁；应用 UI 将这些编辑能力禁用，Service 仍以门禁保护来自其他 Controller 的 timeline callback。只有本应用 Controller 发起的完整 `setMediaItems`/整队起播，经 `MediaSession.Callback.onSetMediaItems` 识别并由后续 Player timeline 确认后，才表示用户明确用新整队替代旧快照并转入 `SUPERSEDED`。待确认标记必须绑定调用方 UID/package、预期 mediaId 序列、起始索引和 generation，只有完全匹配的 timeline 才能消费；外部 Controller 或过期 callback 不得解锁。否则必须重新授权并重启 Service 才解析完整旧队列。

当原当前曲目仍可用时，用删除的前置项数校正索引，保留非负位置；如果已知时长为正数，位置还需夹取到时长范围，未知时长则交给 Player 安全校正。如果原当前曲目缺失，优先选择它之后的第一个可用 occurrence，否则选前一个，且位置归零。全部不可用时应用空队列，并异步用空快照替换已失效数据。

上一段的“全部不可用”只指解析成功后确认的永久缺失；高版本、权限缺失或瞬时读取/解析失败绝不能走空快照替换路径。

## 7. 快照写入策略

Service 从 Player callback 捕获完整域快照，交给一个单一写入循环。不为每个事件启动互相竞争的 DataStore 写协程。下表只在持久化门禁处于 `APPLIED` 或 `SUPERSEDED` 时生效；`RESTORE_PENDING`、`INCOMPATIBLE` 和 `FAILED` 都禁止落盘。

| 触发 | 策略 |
| --- | --- |
| timeline、当前 occurrence、shuffle、repeat 变化 | 立即提交，最多 250ms 合并连续变化 |
| 用户 seek | 立即提交 |
| 暂停、停止或播放结束 | 立即提交 |
| 正在播放的位置 | 每 5 秒采样一次 |
| Service 销毁 | 释放 Player 前提交最终快照，不在主线程等待无界写入 |

写入循环只保留最新的完整快照，但正在执行的写入不被中途取消。所有完成顺序与提交序号一致，较旧状态不得在较新状态之后落盘。Service 销毁时仅在门禁允许写入时提交最终快照，再调用 writer 的关闭协议：停止接收新值，由 writer 自有 Job 最多排空 1 秒，并在成功、失败或超时的 `finally` 中取消自身；不得在主线程等待，也不得留下无界进程级孤儿。若排空超时或进程被操作系统突然终止，恢复位置以最后一次已落盘采样为准。1 秒上限作为可注入配置并用虚拟时间固定。设备验收使用“5 秒采样间隔 + 1 秒调度/落盘容差”，因此上限为 6 秒。

快照写入失败记录脱敏诊断信息，但不停止播放。后续变化仍可以重试写入，不把一次失败变为永久禁用状态。

## 8. 播放模式与队列移动

- repeat 命令与状态使用域枚举，只在 Media3 适配器中映射 `Player.REPEAT_MODE_*`。
- UI 按 `OFF → ALL → ONE → OFF` 循环，实际状态以 callback 为准。
- shuffle 继续由 Media3 管理，新快照只负责恢复开关。
- `moveQueueItem(fromIndex, toIndex)` 以 occurrence index 定位，允许队列包含相同 `TrackId`。
- 每个运行时 MediaItem occurrence 拥有内部不透明 token；`mediaId` 仍是稳定 `TrackId`。token 只用于本次 Player 生命周期的重复项区分与错误防循环，不写入 Proto；恢复时按队列位置重建新 token。
- 移动前验证索引与 Media3 command availability；拒绝的命令不更新 UI 或快照。
- `queuePersistenceLimited=true` 时，本应用禁用添加、移除和移动；播放/暂停/seek 仍可用于当前安全子集但不落盘，整队起播保持可用并被明确视为替代旧队列。
- 本阶段 UI 使用有明确 TalkBack 描述、最小 48dp 的上移/下移控件；M3-B 可在不更改底层契约的前提下增加拖拽手势。

## 9. 播放错误与跳过

Media3 `PlaybackException` 映射为：

- `SOURCE_UNAVAILABLE`
- `UNSUPPORTED_FORMAT`
- `DECODER`
- `UNKNOWN`

域错误保留数值错误码与可选 `TrackId`，不向 UI、日志事件或快照复制原始路径、URI 或异常 message。

局部跳过规则：

1. 失败单位是当前 queue occurrence 的运行时 token，不是 `TrackId` 或会变动的队列索引，因此同一首歌的另一 occurrence 仍可被单独选择。
2. 每轮连续失败记录已尝试 occurrence，候选搜索次数最多等于当前 timeline 的 occurrence 数；不得在 repeat-one 或 repeat-all 下无界回到已失败项。
3. 候选从当前 occurrence 的后继开始：shuffle 关闭时按 timeline 顺序，shuffle 开启时按 Media3 当前 shuffle 顺序；repeat-one 在故障恢复期间忽略，repeat-all 最多回绕一次。存在未失败项时跳到该项；若失败前用户意图为播放，继续播放，否则保持暂停。
4. 跳过不从 timeline 删除失败项，队列与快照仍保留它，用户可主动跳回重试。
5. 无可用候选时暂停并保留队列，将类型化错误保留在 `PlaybackState`。
6. 仅发生 `onMediaItemTransition` 不视为成功。新项实际播放位置累计前进至少 1 秒、自然播放结束，或用户明确选择重试/替换队列后，才清除终止错误并开始新的失败轮次；这样可防止“刚切入就报错”的曲目重置集合后形成循环。

Service 通过受限的 MediaSession 自定义事件向本应用 Controller 发送一次性 `TrackSkipped`；非应用 Controller 不需要理解该事件。播放、锁屏和通知栏 transport 仍使用标准 MediaSession 命令。

## 10. Controller 重连

`Media3PlaybackController` 的连接生命周期收敛到 `ControllerConnectionCoordinator`：

- 任一时刻只有一个当前 `ListenableFuture<MediaController>` 和一个退避 Job。
- 启动或当前 Controller 断开时开启新一轮：先立即 build 一次；该次 build 失败后，再按 `250ms、500ms、1s、2s` 最多执行 4 次延迟重试。因此一轮最多 5 次 build，退避重试数始终最多 4 次。
- 每次构建获得单调 generation。非当前 generation 的成功、失败或断开 callback 都必须忽略；若过期 Future 返回了 Controller，立即移除 listener 并释放。
- 连接成功后重置尝试计数、清除 `PlaybackConnectionError`，直接从 MediaController 快照发布当前真实状态。
- 初始 build 与 4 次延迟重试全部失败后发布 `DISCONNECTED` 与 `PlaybackConnectionError.RETRIES_EXHAUSTED`，不把它伪装成曲目 `PlaybackError`，也不进行无界后台重试。
- 收敛为 `DISCONNECTED` 后，新的整队播放请求可以开启一轮新的“1 次立即 build + 4 次退避重试”。
- 断开期间 `PlaybackState` 关闭 transport capability，普通 UI 命令不排队，避免连接恢复后回放过期点击。

`play(tracks, startIndex, shuffle)` 仍保持可等待当前连接轮次并返回成功与否的挂起语义；其他只对已连接 Player 有意义的 transport 命令在断开时安全拒绝。

## 11. 并发与生命周期

- Player 读取、恢复应用与命令发送回到 Media3 application looper。
- DataStore 与 Room 操作只在挂起边界执行，测试用可注入 dispatcher 和虚拟时间。
- restore Job、position ticker、snapshot writer 和 reconnect Job 都有明确所有者；Service/Controller 销毁后不得留下无主 Job。
- Player listener、MediaController listener 和自定义事件 listener 都有对称移除路径。
- `CancellationException` 始终重新抛出，不被快照或诊断错误处理吞掉。
- 恢复与快照写入失败是非致命增强失败；Player 和 Session 仍可继续接受新的播放请求。

## 12. UI 与 UDF

现有 `LibraryViewModel` 继续组合 `PlaybackController.state` 与曲库 Flow，不注入 `PlaybackSnapshotStore`。新 Action 只描述用户意图：

- 切换 repeat
- 切换 shuffle
- 上移/下移队列 occurrence

ViewModel 另外收集非重放的 `PlaybackController.notices`，转发为一次性 UI effect；跳过提示不进入可恢复 `PlaybackState`，也不要求 UI 发送“消费通知”业务 Action。

当前 `PlayerScreen` 显示实际 repeat/shuffle 状态、可用的队列移动操作以及无法继续时的可理解错误。按钮在命令不可用或 Controller 未连接时禁用；所有可点击目标至少 48dp，移动按钮提供包含曲名和方向的语义描述。

恢复的短暂异步窗口不通过 UI 伪造队列：Controller 可先发布空队列，待 Service 将恢复计划应用到 Player 后，真实 callback 会更新 UI。此窗口中新的整队起播优先于恢复。

## 13. 测试设计

### 13.1 JVM 与组件测试

- Proto 默认实例、非默认 schema 0、负/高版本、空白 ID 索引重算、域映射往返、损坏字节、非法字段、写入抑制与成功队列变更解除抑制。
- 解析器的 Demo/本地混合、重复 ID、原顺序、超过 999 个唯一 ID 的分块查询、权限临时阻断、单项/当前/全部永久缺失，以及 Room/IO 异常不降级为空结果。
- 恢复计划的索引校正、位置保留/归零、不自动播放、用户命令抢占慢恢复和 `applyingRestore` 临界区。
- `RestorePersistenceGate` 覆盖初始空 callback、慢读取、读取前销毁、正常应用、用户抢占、高版本、权限阻断与瞬时失败；后三者的文件字节必须保持不变。权限部分恢复还需证明添加/移除/移动不解除门禁，只有绑定调用方、预期队列、起始索引与 generation 的 `onSetMediaItems` 标记被完全匹配 timeline 确认后才可解除；外部或过期 callback 必须忽略。
- 快照写入器的 250ms 合并、5 秒采样、序列化、最新值优先、写入失败后继续、1 秒有界关闭，以及门禁禁止最终空快照的语义。
- repeat、shuffle、队列移动和重复 occurrence 的命令/callback 映射。
- 错误分类、shuffle 遍历、repeat-one/repeat-all 有界搜索、刚切入即失败、稳定播放后重置、局部跳过、全队失败与用户重试重置。
- 重连的初始立即 build、4 个确切退避时序、单一 in-flight、过期成功/失败/断开 callback、`RETRIES_EXHAUSTED` 的设置/清除、释放与新一轮用户触发。
- `PlaybackState`、`queuePersistenceLimited` 能力禁用、ViewModel Action 与一次性 Notice 的 UDF 转换。

### 13.2 真实存储集成测试

- `DataStoreFactory` + 临时文件验证真实 protobuf 落盘、重启读取、并发更新和 corruption handler。
- Room in-memory 数据库验证每批最多 900 个 ID、至少 1,205 个唯一 ID 的多批合并、`isAvailable`、Demo 映射、重复/顺序重建和不可用项。
- 不以仅能证明 Fake 自身行为的测试取代真实存储集成测试。

### 13.3 API 36 设备回归

1. 测试侧运行时生成至少 30 秒的确定性 WAV 并通过 MediaStore 加入曲库；生产 APK 不携带这份长音频。位置恢复不得用当前仅 3.2–4.0 秒的 Demo 曲作证明。
2. 用长音频建立队列，设置 repeat/shuffle，播放并 seek 到 15 秒以后。宿主验证脚本分两阶段运行 instrumentation，并用 `run-as` 读取 Proto、按仓库 `.proto` 解码，记录已持久位置；紧接着记录杀进程前 Controller 实际位置，并先断言二者差不超过 6 秒。
3. 通过 `am force-stop` 终止应用进程并冷启动；第二阶段 instrumentation 单独断言恢复位置与已解码持久位置差不超过 1 秒，再验证保持暂停、通知/会话不显示正在播放。两条位置断言不得合并。
4. 单独建立含重复 Demo 的队列，执行进程重建，验证队列顺序、重复 occurrence、索引、repeat 与 shuffle；该用例不承担 6 秒位置指标。
5. 对 Demo + 本地混合队列撤销音频权限：只恢复安全 Demo 子集但原 Proto 字节不变；尝试添加、移除和移动 Demo 都不得改变原 Proto，只有完整整队起播可替代它。未替代时重新授权并重启 Service，本地项应再次恢复。另测一个永久不可用本地项只被局部移除，其余队列保留。
6. 在读取完成前制造初始空 callback 与提前销毁，验证原 v1 Proto 字节不变；再用新整队起播抢占慢恢复，验证只落盘新队列。
7. 注入损坏 Proto，验证冷启动无崩溃、空恢复与后续新起播正常；注入高版本 Proto，验证冷启动及销毁均不改写文件。
8. 重启播放 Service，验证 Controller 自动重连，旧 callback 不覆盖新连接状态。

以上设备证据与对应代码快照、命令和关键输出一起写入新的 M3-A 验证记录，不回写或夸大原 21 项简历验收矩阵。

## 14. 验收标准

M3-A 只在以下条件同时成立时完成：

1. 进程回收后恢复队列、重复项、当前索引、shuffle、repeat 和位置，且不自动播放。
2. 至少 30 秒测试音频在 15 秒以后终止进程；经解码确认的已持久位置与杀进程前实际位置差不超过 6 秒（5 秒采样间隔 + 1 秒调度/落盘容差），且冷启动恢复位置与该已持久位置差不超过 1 秒。短 Demo 不计作该指标证据。
3. `RESTORE_PENDING` 期间的初始 callback 和提前销毁不能覆盖原快照；慢 DataStore/Room 读取也不能覆盖启动后的新整队起播。
4. 默认 Proto、空白 ID、损坏 Proto、越界值和不兼容 schema 均不导致崩溃或自动播放；不兼容文件在显式队列变更前保持字节不变。
5. 权限缺失与瞬时 DataStore/Room/IO 失败保留原快照；权限部分恢复时的添加/移除/移动不能覆盖隐藏 ID，只有明确整队替代可以解除门禁；一个确认永久不可用或播放损坏的曲目不清空其余队列。
6. repeat 模式不导致无界错误循环；repeat、shuffle 与队列移动都经过真实 Media3 callback 回流到 UI，重复 ID 的 occurrence 行为正确。
7. Controller 每轮最多执行 1 次立即 build 与 4 次退避重试；收敛后没有多余 Future、listener 或 Job，过期 callback 不得发布状态。
8. 快照写入不阻塞主线程，写入失败不停止当前播放。
9. 新增自动化、真实存储集成与 API 36 设备回归通过；完整 `test testDebugUnitTest lintDebug assembleDebug` 通过。
10. 每个实施任务完成后依次经过规格符合性与代码质量两阶段审查；PR CI 全绿后才合并。

## 15. 迁移与兼容

- 现有安装首次升级到 M3-A 时没有 Proto 文件，等价于空快照；不会为旧用户自动播放。
- 本里程碑不修改 Room schema，因此不新增 Room migration。
- Proto 从首版就包含 schema 版本；未来字段以 protobuf 兼容规则添加，不重用已删除 tag。
- `applicationId`、`versionCode`、`versionName` 和签名配置不在 M3-A 改动。

## 16. 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| 初始空状态或恢复结果覆盖有效数据 | 从首 callback 前启用状态机门禁，再叠加 generation + timeline 校验，用慢读取/提前销毁/用户抢占竞态测试固定 |
| 高频位置写入 | 5 秒采样，其他变更用全快照合并 |
| 超大或重复 ID 查询失败/丢序 | 唯一 ID 每批最多 900 个，合并映射后用原列表重建 occurrence |
| 播放错误在 repeat 下循环 | 每轮失败 occurrence 集合与穷尽后暂停 |
| 旧 Controller 晚到覆盖新状态 | generation 校验与过期 Controller 对称释放 |
| Service 销毁时写入未完成 | 播放中周期采样、暂停/seek 立即提交、关闭时完成 Job 而不阻塞主线程 |
| 权限或瞬时 IO 问题被误判为永久空队列 | 标记临时阻断并保持写入门禁；仅永久缺失结果可规范化回写 |
| 短 Demo 让位置误差指标假阳性 | 使用至少 30 秒测试音频、15 秒以后终止，并解码确认真实落盘位置 |

## 17. 交付与证据边界

实施将基于 `origin/main@6da64dc` 的独立工作树与功能分支，按测试先行、原子提交和子代理双阶段审查执行。新验收记录必须标注精确 commit、设备/API、命令、计数与局限。

AudioFocus 被第二个真实媒体应用抢占，以及有线/蓝牙输出断开，仍需要物理设备。如果实施期没有物理设备，它们继续保持 `PENDING_DEVICE`；M3-A 不把模拟器或媒体按键替代为真实音频路由证据。
