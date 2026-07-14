# 音悦盒验收矩阵

状态只允许 `AUTOMATED_PASS`、`MANUAL_PASS`、`PENDING_DEVICE`、`FAIL`。`PASS` 表示已经执行本行证据；仅有代码或尚未执行的步骤不能标记为通过。设备记录、原始输出摘录和完整命令见 [2026-07-14 验证记录](result-2026-07-14.md)。

| ID | 场景与通过标准 | 状态 | 可复现证据 |
| --- | --- | --- | --- |
| F01 | Android 13+ 音频权限边界：拒绝时可使用 Demo，授权后触发扫描 | MANUAL_PASS | API 36 无权限显示 Demo、授权并回前台后自动扫描；另有 App 设备测试 4/4 与 ViewModel 权限测试。见[权限与 MediaStore](result-2026-07-14.md#权限与-mediastore)。 |
| F02 | MediaStore 扫描：真实音频授权后进入本地曲库 | MANUAL_PASS | 向模拟器 MediaStore 写入 WAV 后，UI 显示“本地曲库/codex_resume_test”，Room 保存 `external_primary` 可用行。见[权限与 MediaStore](result-2026-07-14.md#权限与-mediastore)。 |
| F03 | 撤权与冷启动：不泄露本地曲目，缓存仍可在重新授权后恢复 | MANUAL_PASS | 撤权、force-stop、冷启动显示 Demo；Room 本地缓存仍为 1，重新授权后恢复本地曲库。见[权限与 MediaStore](result-2026-07-14.md#权限与-mediastore)。 |
| F04 | 无本地曲目时稳定回退四首内置 Demo | AUTOMATED_PASS | `DemoTrackCatalogTest`、`RoomTrackRepositoryTest` 和 `AppLaunchTest` 已执行；App 设备测试 4/4。见[自动化结果](result-2026-07-14.md#自动化结果)。 |
| F05 | Compose 仅有曲库、播放、歌单三页，事件经 UDF/ViewModel 分发 | AUTOMATED_PASS | `LibraryScreenTest` 6/6、`LibraryViewModelTest` 及 `bottomNavigation_reachesExactlyHomePlayerAndPlaylists` 已执行。见[自动化结果](result-2026-07-14.md#自动化结果)。 |
| F06 | 单曲、播放全部与随机播放能够建立 Media3 队列并起播 | MANUAL_PASS | Demo 播放全部真实进入 `PLAYING`；5 轮播放循环均起播，队列自动推进；play/random/selected-index 另有 JVM 测试。见[后台播放与系统控制](result-2026-07-14.md#后台播放与系统控制)。 |
| F07 | 播放/暂停、上一首/下一首与进度跳转正确回流状态 | AUTOMATED_PASS | `PlaybackCommandTest`、`PlayerSnapshotTest`、`LibraryViewModelTest.transportSeekAndQueueActions_delegateExactlyOnce` 与 Compose transport/seek 测试已执行。见[自动化结果](result-2026-07-14.md#自动化结果)。 |
| F08 | 队列支持追加、删除、跳转与重复曲目 | AUTOMATED_PASS | `PlaybackCommandTest` 加上 `playerQueue_allowsRepeatedTrackIdsWithoutDuplicateLazyKeys`、队列 occurrence 测试已执行。见[自动化结果](result-2026-07-14.md#自动化结果)。 |
| F09 | 收藏添加/取消并通过 Room Flow 持久化 | MANUAL_PASS | 点击收藏后 Room 与 `FAVORITE_CHANGED` 有记录，force-stop 冷启动后仍显示“取消收藏晨间节拍”；DAO/Repository 测试已执行。见[收藏与重启](result-2026-07-14.md#收藏与重启)。 |
| F10 | 最近播放按曲目聚合、最新优先并硬限制 20 条 | AUTOMATED_PASS | `FavoriteRecentDaoTest.recentTracks_areNewestFirstAndHardLimitedToTwenty` 与 Demo/本地混合 20 条测试已执行。见[自动化结果](result-2026-07-14.md#自动化结果)。 |
| F11 | 退到后台后 MediaLibraryService 继续播放 | MANUAL_PASS | MainActivity 为 last-paused 时 MediaSession 保持 active 且 `PLAYING`。见[后台播放与系统控制](result-2026-07-14.md#后台播放与系统控制)。 |
| F12 | 起播后存在可用媒体通知 | MANUAL_PASS | API 36 `dumpsys notification --noredact` 显示 id=1001、transport、FOREGROUND_SERVICE、MediaStyle 与 Media3 session token。见[媒体通知](result-2026-07-14.md#媒体通知)。 |
| F13 | 系统媒体 pause/play 控制与会话状态同步 | MANUAL_PASS | 系统按键实测 `PLAYING → PAUSED → PLAYING`；5 轮后台循环重复得到相同状态序列。见[后台播放与系统控制](result-2026-07-14.md#后台播放与系统控制)。 |
| F14 | AudioFocus 抢占时行为在真实设备上符合预期 | PENDING_DEVICE | ExoPlayer 已配置管理 AudioFocus，但没有在物理设备用第二个真实媒体应用执行瞬时/永久抢占。待验步骤见[物理设备待验](result-2026-07-14.md#物理设备待验)。 |
| F15 | 有线或蓝牙输出断开（becoming noisy）时自动暂停 | PENDING_DEVICE | 已启用 `setHandleAudioBecomingNoisy(true)`，但模拟器媒体按键不能替代真实有线/蓝牙路由断开。待验步骤见[物理设备待验](result-2026-07-14.md#物理设备待验)。 |
| F16 | 起播、切歌、完播、收藏等匿名事件正确落库 | MANUAL_PASS | 设备 Room 查询实际包含 requested/started/changed/completed/favorite；事件顺序与 500 条上限有 JVM 测试，实体字段审阅确认不含标题、路径或 URI。见[事件证据](result-2026-07-14.md#事件证据)。 |
| F17 | 首帧与起播耗时事件只在正确边界记录 | MANUAL_PASS | 设备数据库实际记录 `FIRST_FRAME=365ms`、`PLAY_START_LATENCY=312ms`；exactly-once 与匹配曲目规则有设备/JVM 测试。见[事件证据](result-2026-07-14.md#事件证据)。 |
| F18 | Room 1→2 迁移与进程重启后用户数据可恢复 | MANUAL_PASS | API 36 migration instrumentation 1/1；收藏、最近记录与 schema v2 经 force-stop 冷启动后仍存在。见[迁移与重启](result-2026-07-14.md#迁移与重启)。 |
| F19 | API 36 模拟器冷启动基线可复现且无启动崩溃 | MANUAL_PASS | 5 次 `am start -W` 冷启动均 `Status: ok/COLD`，774–800ms；仅作为模拟器基线。见[启动基线](result-2026-07-14.md#启动基线)。 |
| F20 | 播放场景内存快照可复现且进程存活 | MANUAL_PASS | `PLAYING` 时 `dumpsys meminfo`：PSS 89,495KB、RSS 224,632KB、swap 0；仅为单次快照。见[内存快照](result-2026-07-14.md#内存快照)。 |
| F21 | 完整 Gradle 门禁与短时稳定性循环无失败、崩溃或 ANR | MANUAL_PASS | 完整门禁 exit 0；5 轮冷启/播放/后台/系统 pause-play 全部通过，过滤 logcat 无 FATAL/ANR。见[完整门禁](result-2026-07-14.md#完整门禁)和[稳定性循环](result-2026-07-14.md#稳定性循环)。 |

状态统计：`AUTOMATED_PASS=5`、`MANUAL_PASS=14`、`PENDING_DEVICE=2`、`FAIL=0`。

## 矩阵结构校验

在仓库根目录执行：

```bash
python3 - <<'PY'
import pathlib, re

path = pathlib.Path("verification/acceptance-scenarios.md")
rows = re.findall(
    r"^\| (F\d{2}) \|.*\| (AUTOMATED_PASS|MANUAL_PASS|PENDING_DEVICE|FAIL) \|",
    path.read_text(),
    re.MULTILINE,
)
ids = [item[0] for item in rows]
assert ids == [f"F{i:02d}" for i in range(1, 22)], ids
assert len(ids) == len(set(ids)) == 21
print("acceptance matrix: 21 unique IDs and valid statuses")
PY
```
