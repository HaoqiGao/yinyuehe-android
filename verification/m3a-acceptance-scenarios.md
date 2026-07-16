# 音悦盒 M3-A 播放恢复验收矩阵

本矩阵只记录 pre-evidence 快照 `8e694623d41af1469becf8940bb17f2c145addf8` 的 M3-A 专项证据。状态只允许 `AUTOMATED_PASS`、`MANUAL_PASS`、`PENDING_DEVICE`、`FAIL`；完整环境、命令、计数与设备值见 [2026-07-15 M3-A 验证记录](result-2026-07-15-m3a.md)。

| ID | 场景与通过标准 | 已执行的命令与证据 | 状态 |
| --- | --- | --- | --- |
| M3A01 | Proto/DataStore：字段映射、不可兼容 schema、原子读写与单实例绑定符合约束 | clean 后执行 `./gradlew :core:data:testDebugUnitTest :core:data:lintDebug`；见 [JVM、lint 与构建](result-2026-07-15-m3a.md#jvmlint-与构建)。 | AUTOMATED_PASS |
| M3A02 | resolver：按稳定 TrackId 解析、缺失/权限受限与重复 occurrence 保序 | clean 后执行 `./gradlew :core:data:testDebugUnitTest`；见 [JVM、lint 与构建](result-2026-07-15-m3a.md#jvmlint-与构建)及[权限受限与永久缺失](result-2026-07-15-m3a.md#权限受限与永久缺失)。 | AUTOMATED_PASS |
| M3A03 | restore/gate：恢复前禁止写入，不兼容/失败保留旧字节，恢复永不自动播放 | 执行 `./gradlew :core:player:testDebugUnitTest` 与完整 host suite；见 [恢复竞争、损坏与 schema-99](result-2026-07-15-m3a.md#恢复竞争损坏与-schema-99)。 | AUTOMATED_PASS |
| M3A04 | 长曲目暂停位置：真实生成 35 秒 WAV，capture `<= 6000 ms`、restore `<= 1000 ms` 且恢复后暂停 | 执行 `ANDROID_SERIAL=emulator-5554 ./scripts/run-m3a-device-acceptance.sh`；见 [长曲目位置恢复](result-2026-07-15-m3a.md#长曲目位置恢复)。 | AUTOMATED_PASS |
| M3A05 | restore races：BEFORE_READ 销毁取消与 BEFORE_APPLY 全量替换不会被旧计划覆盖 | 执行完整 host suite；见 [恢复竞争、损坏与 schema-99](result-2026-07-15-m3a.md#恢复竞争损坏与-schema-99)。 | AUTOMATED_PASS |
| M3A06 | 权限受限：增删移动保持受保护快照；本应用完整替换可 supersede；永久缺失仅移除缺失 occurrence | 执行完整 host suite；见 [权限受限与永久缺失](result-2026-07-15-m3a.md#权限受限与永久缺失)。 | AUTOMATED_PASS |
| M3A07 | repeat/shuffle/move：重复 TrackId 的 occurrence 顺序、索引、repeat、shuffle 与暂停状态精确恢复 | 执行 `./gradlew :core:player:testDebugUnitTest` 与完整 host suite；见 [重复队列与模式](result-2026-07-15-m3a.md#重复队列与模式)。 | AUTOMATED_PASS |
| M3A08 | typed bounded failure recovery：错误分类、按 timeline occurrence 数有界跳过、失败项不移除、一次性 notice | clean 后执行 `./gradlew :core:player:testDebugUnitTest :feature:library:testDebugUnitTest`；见 [JVM、lint 与构建](result-2026-07-15-m3a.md#jvmlint-与构建)。 | AUTOMATED_PASS |
| M3A09 | reconnect：single-flight 有界重试，主进程重建时 Controller identity/探针进程稳定，旧 generation 不回写 | 执行 `./gradlew :core:player:testDebugUnitTest` 与完整 host suite；见 [Controller 重连](result-2026-07-15-m3a.md#controller-重连)。 | AUTOMATED_PASS |
| M3A10 | 完整门禁：clean、focused、CI、API 36 五 case、哈希、cleanup 与残留审计全部成功 | 执行[完整命令序列](result-2026-07-15-m3a.md#命令与结果)；见 [证据完整性](result-2026-07-15-m3a.md#设备证据完整性与清理)。 | AUTOMATED_PASS |
| M3A11 | 物理设备 AudioFocus：第二个真实媒体应用触发瞬时/永久抢占后的暂停、恢复策略符合预期 | API 36 模拟器不能建立真实应用抢占证据；见 [验证边界](result-2026-07-15-m3a.md#验证边界)。 | PENDING_DEVICE |
| M3A12 | 物理设备 noisy-route removal：有线耳机或蓝牙输出真实断开时自动暂停 | 模拟器媒体按键不能替代真实 audio-becoming-noisy 路由移除；见 [验证边界](result-2026-07-15-m3a.md#验证边界)。 | PENDING_DEVICE |

状态统计：`AUTOMATED_PASS=10`、`PENDING_DEVICE=2`、`MANUAL_PASS=0`、`FAIL=0`。模拟器结果不外推为 M3A11/M3A12 的物理音频行为验证。
