# 音悦盒验收矩阵

状态只允许 `AUTOMATED_PASS`、`MANUAL_PASS`、`PENDING_DEVICE`、`FAIL`。`PASS` 表示已经执行本行检查；仅有代码或尚未执行的步骤不能标记为通过。本次执行记录、关键命令与关键输出摘录见 [2026-07-14 验证记录](result-2026-07-14.md)；完整终端 transcript 未提交。

| ID | 场景与通过标准 | 状态 | 本次执行记录 |
| --- | --- | --- | --- |
| F01 | Android 13+ 音频权限边界：拒绝时可使用 Demo，授权后触发扫描 | MANUAL_PASS | 最终 `deae6a82` 在 API 36 完成 grant/revoke/regrant 三段冷启动，授权显示本地曲库、撤权显示 Demo、重授权恢复本地曲库；另有 App 设备测试 4/4 与 ViewModel 权限测试。见[最终硬化回归](result-2026-07-14.md#最终硬化回归)和[权限与 MediaStore](result-2026-07-14.md#权限与-mediastore)。 |
| F02 | MediaStore 扫描：真实音频授权后进入本地曲库 | MANUAL_PASS | 最终 `deae6a82` 授权冷启动显示“本地曲库/codex_resume_test”；`d9b04564` 基线还记录了 Room 的 `external_primary` 可用行。见[最终硬化回归](result-2026-07-14.md#最终硬化回归)和[权限与 MediaStore](result-2026-07-14.md#权限与-mediastore)。 |
| F03 | 撤权与冷启动：不泄露本地曲目，缓存仍可在重新授权后恢复 | MANUAL_PASS | 最终 `deae6a82` 撤权冷启动显示 Demo、重授权冷启动恢复本地曲库；Room 缓存仍为 1 的细节来自 `d9b04564` 基线。见[最终硬化回归](result-2026-07-14.md#最终硬化回归)和[权限与 MediaStore](result-2026-07-14.md#权限与-mediastore)。 |
| F04 | 无本地曲目时稳定回退四首内置 Demo | AUTOMATED_PASS | 最终 `deae6a82` 的 `DemoTrackCatalogTest`、`RoomTrackRepositoryTest` 和 `AppLaunchTest` 已执行；App 设备测试 4/4。见[自动化结果](result-2026-07-14.md#自动化结果)。 |
| F05 | Compose 仅有曲库、播放、歌单三页，事件经 UDF/ViewModel 分发 | AUTOMATED_PASS | 最终 `deae6a82` 的 `LibraryScreenTest` 7/7、`LibraryViewModelTest` 及 `bottomNavigation_reachesExactlyHomePlayerAndPlaylists` 已执行。见[自动化结果](result-2026-07-14.md#自动化结果)。 |
| F06 | 单曲、播放全部与随机播放能够建立 Media3 队列并起播 | MANUAL_PASS | `d9b04564` 基线的 Demo 播放全部与 5 轮循环均起播；最终 `deae6a82` 的四首队列自然完播后又能单击从 item 3 起播。play/random/selected-index 另有最终 JVM 测试。见[后台播放与系统控制](result-2026-07-14.md#后台播放与系统控制)和[最终硬化回归](result-2026-07-14.md#最终硬化回归)。 |
| F07 | 播放/暂停、上一首/下一首与进度跳转正确回流状态 | AUTOMATED_PASS | 最终 `deae6a82` 的 `PlaybackCommandTest`、`PlayerSnapshotTest`、ViewModel/Compose transport/seek 测试均已执行；自然结束后的 UI `Play` 单击也回流为 `PLAYING`。见[自动化结果](result-2026-07-14.md#自动化结果)和[最终硬化回归](result-2026-07-14.md#最终硬化回归)。 |
| F08 | 队列支持追加、删除、跳转与重复曲目 | AUTOMATED_PASS | `PlaybackCommandTest` 加上 `playerQueue_allowsRepeatedTrackIdsWithoutDuplicateLazyKeys`、队列 occurrence 测试已执行。见[自动化结果](result-2026-07-14.md#自动化结果)。 |
| F09 | 收藏添加/取消并通过 Room Flow 持久化 | MANUAL_PASS | `d9b04564` 基线中，点击收藏后 Room 与 `FAVORITE_CHANGED` 有记录，force-stop 冷启动后仍显示“取消收藏晨间节拍”；最终 DAO/Repository 测试已执行。见[收藏与重启](result-2026-07-14.md#收藏与重启)。 |
| F10 | 最近播放按曲目聚合、最新优先并硬限制 20 条 | AUTOMATED_PASS | `FavoriteRecentDaoTest.recentTracks_areNewestFirstAndHardLimitedToTwenty` 与 Demo/本地混合 20 条测试已执行。见[自动化结果](result-2026-07-14.md#自动化结果)。 |
| F11 | 退到后台后 MediaLibraryService 继续播放 | MANUAL_PASS | `d9b04564` 基线时间戳记录中，MainActivity 为 last-paused 时 MediaSession 保持 active 且 `PLAYING`。见[后台播放与系统控制](result-2026-07-14.md#后台播放与系统控制)。 |
| F12 | 起播后存在可用媒体通知 | MANUAL_PASS | `d9b04564` 基线的 API 36 `dumpsys notification --noredact` 显示 id=1001、transport、FOREGROUND_SERVICE、MediaStyle 与 Media3 session token。见[媒体通知](result-2026-07-14.md#媒体通知)。 |
| F13 | 系统媒体 pause/play 控制与会话状态同步 | MANUAL_PASS | `d9b04564` 基线系统按键记录为 `PLAYING → PAUSED → PLAYING`；5 轮后台循环重复得到相同状态序列。见[后台播放与系统控制](result-2026-07-14.md#后台播放与系统控制)。 |
| F14 | AudioFocus 抢占时行为在真实设备上符合预期 | PENDING_DEVICE | ExoPlayer 已配置管理 AudioFocus，但没有在物理设备用第二个真实媒体应用执行瞬时/永久抢占。待验步骤见[物理设备待验](result-2026-07-14.md#物理设备待验)。 |
| F15 | 有线或蓝牙输出断开（becoming noisy）时自动暂停 | PENDING_DEVICE | 已启用 `setHandleAudioBecomingNoisy(true)`，但模拟器媒体按键不能替代真实有线/蓝牙路由断开。待验步骤见[物理设备待验](result-2026-07-14.md#物理设备待验)。 |
| F16 | 起播、切歌、完播、收藏等事件仅在本地正确落库 | MANUAL_PASS | `d9b04564` 基线 Room 查询包含 requested/started/changed/completed/favorite。记录含可选稳定 `trackId`，可在本地关联同一曲目；无上传链路，不含标题、路径或 URI。最终 JVM 测试覆盖事件归因。见[事件证据](result-2026-07-14.md#事件证据)。 |
| F17 | 首帧与起播耗时事件只在正确边界记录 | MANUAL_PASS | `d9b04564` 基线数据库值为 `FIRST_FRAME=365ms`、`PLAY_START_LATENCY=312ms`；最终 `deae6a82` 同 PID 旋转保持 `FIRST_FRAME` 计数 `2→2`，并由 JVM 测试覆盖起播来源/匹配曲目归因。见[事件证据](result-2026-07-14.md#事件证据)和[最终硬化回归](result-2026-07-14.md#最终硬化回归)。 |
| F18 | Room 1→2 迁移与进程重启后用户数据可恢复 | MANUAL_PASS | 最终 `deae6a82` 的 API 36 migration instrumentation 为 1/1；收藏、最近记录与 schema v2 经 force-stop 冷启动仍存在的设备记录来自 `d9b04564` 基线。见[迁移与重启](result-2026-07-14.md#迁移与重启)。 |
| F19 | API 36 模拟器冷启动样本已采集且无启动崩溃 | MANUAL_PASS | `d9b04564` 基线的 5 次 `am start -W` 冷启动均为 `Status: ok/COLD`，774–800ms；不外推为最终快照性能。见[启动基线](result-2026-07-14.md#启动基线)。 |
| F20 | 播放场景已采集的内存单点基线且进程存活 | MANUAL_PASS | `d9b04564` 的 `PLAYING` 单点快照为 PSS 89,495KB、RSS 224,632KB、swap 0；不表示最终快照内存、趋势、优化或无泄漏。见[内存快照](result-2026-07-14.md#内存快照)。 |
| F21 | 完整 Gradle 门禁与短时稳定性循环无失败、崩溃或 ANR | MANUAL_PASS | 最终 `deae6a82` 完整门禁 exit 0，并完成自然完播/旋转补充回归；无 FATAL/ANR 的 5 轮冷启/播放/后台/system pause-play 记录来自 `d9b04564` 基线。见[完整门禁](result-2026-07-14.md#完整门禁)、[最终硬化回归](result-2026-07-14.md#最终硬化回归)和[稳定性循环](result-2026-07-14.md#稳定性循环)。 |

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
