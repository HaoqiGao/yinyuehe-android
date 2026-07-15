# M3-A Recoverable Playback Kernel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Service-owned, Proto DataStore-backed playback kernel that restores queue state without autoplay, survives races and partial failures, adds repeat/move/error recovery, and reconnects the in-app MediaController with bounded retries.

**Architecture:** `:core:common` owns Android-free recovery and error contracts; `:core:data` owns the single Proto DataStore and permission-aware Room resolver; `:core:player` owns restore/persistence/failure runtimes around the one ExoPlayer and MediaSession. The existing `:feature:library` remains the UDF UI boundary and consumes only `PlaybackController` state/notices; it never reads persistence directly.

**Tech Stack:** Kotlin 2.0.20, AGP 8.12.3, JDK 17, Coroutines/Flow 1.9.0, Media3 1.10.1, Room 2.8.4, DataStore 1.2.1, protobuf Gradle plugin 0.9.5, protoc/protobuf-kotlin-lite 4.32.1, Hilt 2.57.1, Jetpack Compose Material 3, JUnit 4, Robolectric 4.16, Compose UI Test, Android instrumentation, Android Lint.

## Global Constraints

- Work only in `/Users/ghq/Downloads/media-release/yinyuehe-android/.worktrees/m3a-playback-recovery` on `feature/m3a-playback-recovery`, based on `origin/main@6da64dc1d5202ebfc8db7e3edb79febdf8354793`.
- Preserve `applicationId = app.yinyuehe`, `minSdk = 26`, `compileSdk = 36`, `targetSdk = 36`, and JDK/JVM target 17.
- Pin DataStore to `1.2.1`, protobuf plugin to `0.9.5`, and protoc/runtime to `4.32.1`; do not upgrade unrelated dependencies.
- Keep `PlaybackService` as the only owner of ExoPlayer, MediaSession, restore application, snapshot capture, queue failure recovery, and the persistence gate. Neither UI nor `Media3PlaybackController` may read/write `PlaybackSnapshotStore`.
- Keep `:core:player` dependent on `:core:common`, never on `:core:data`; Hilt resolves common interfaces from the application graph.
- Restored state always ends with `playWhenReady=false`; no restore path may emit sound without a later explicit user/system play command.
- The persistence gate starts in `RESTORE_PENDING` before the first Player callback. Only `APPLIED` and `SUPERSEDED` may write; `INCOMPATIBLE` and `FAILED` preserve existing bytes.
- Proto stores only schema version, ordered stable TrackIds (duplicates allowed), current index, position, shuffle, and repeat. It never stores URI, path, title, artist, exception message, occurrence token, or device identifier.
- Coalesce structural/mode writes for at most 250 ms, write seek/pause/stop/end immediately, sample playing position every 5 seconds, and bound writer shutdown drain to 1 second.
- Reconnect rounds are exactly one immediate build plus delayed retries at 250 ms, 500 ms, 1 s, and 2 s. A round therefore performs at most five builds.
- Failure recovery is bounded by current timeline occurrence count, ignores repeat-one for candidate traversal, allows repeat-all to wrap once, respects Media3 shuffle order, and never removes a failed item from the queue.
- Runtime occurrence tokens are opaque and unique only for one Service/Player lifetime; `mediaId` remains the stable TrackId and tokens never enter Proto.
- Permission-limited partial restore may show safe Demo entries but must keep the original Proto unchanged. Add/remove/move do not unlock it; only an exact, own-app full `setMediaItems` replacement confirmed by timeline may enter `SUPERSEDED`.
- Do not change the Room schema, split a new feature module, add mini-player/lyrics/network metadata, publish a signed APK, or mark physical AudioFocus/noisy scenarios as passed.
- Every behavior change follows RED -> GREEN -> REFACTOR. Each task gets a fresh implementation subagent, then a fresh specification-compliance reviewer, then a fresh code-quality reviewer. Resolve every Critical/Important finding before starting the next task.
- Each task ends in one focused commit. Do not fold unrelated user changes into any commit.
- Source of truth: `docs/superpowers/specs/2026-07-15-m3a-playback-recovery-design.md`.

## Required Preflight

Before every Gradle block, run:

```bash
KNOWN_JAVA17="/Users/ghq/.cache/yinyuehe/jdk17/8fa1eff40bb637a33613b2ccb8b12c70dc3661cc22cf8e784943715769a05336/jdk-17.0.19+10/Contents/Home"
export JAVA_HOME="${YINYUEHE_JAVA17_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || true)}"
if [ ! -x "$JAVA_HOME/bin/java" ] && [ -x "$KNOWN_JAVA17/bin/java" ]; then
  export JAVA_HOME="$KNOWN_JAVA17"
fi
export ANDROID_HOME="${ANDROID_HOME:-/Users/ghq/.cache/yinyuehe/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export PATH="$JAVA_HOME/bin:$PATH"
test -x "$JAVA_HOME/bin/java"
test -d "$ANDROID_HOME"
test "$("$JAVA_HOME/bin/java" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/{print $2; exit}')" = "17"
./gradlew --version | rg 'Launcher JVM: 17|JVM: 17'
git status --short
```

Expected: JDK checks exit 0, Gradle reports JVM 17, and status contains only changes belonging to the current task. If JDK 17 is not discoverable, set `YINYUEHE_JAVA17_HOME` explicitly; do not run Gradle on JDK 25.

## Scope Decision

This stays one implementation plan because persistence, restore gating, queue commands, failure recovery, Controller extras/notices, and UI capabilities share the same Service state machine and public `PlaybackState`. Tasks are still review-sized and independently testable; no task may invent a parallel contract.

## File Structure

### Create in `:core:common`

- `core/common/src/main/kotlin/app/yinyuehe/core/common/playback/PlaybackSnapshot.kt` — immutable current-schema snapshot and read/store contract.
- `core/common/src/main/kotlin/app/yinyuehe/core/common/playback/PlaybackQueueResolution.kt` — per-occurrence resolved/missing/blocked result and resolver contract.
- `core/common/src/main/kotlin/app/yinyuehe/core/common/playback/PlaybackFailure.kt` — repeat, media error, connection error, and one-shot notice domain values.
- Matching JVM tests under `core/common/src/test/kotlin/app/yinyuehe/core/common/playback/`.

### Create/modify in `:core:data`

- `core/data/src/main/proto/playback_snapshot.proto` — schema v1.
- `core/data/src/main/kotlin/app/yinyuehe/core/data/playback/PlaybackSnapshotSerializer.kt` — parse/write and corruption boundary.
- `core/data/src/main/kotlin/app/yinyuehe/core/data/playback/PlaybackSnapshotProtoMapper.kt` — default/incompatible/blank-ID normalization.
- `core/data/src/main/kotlin/app/yinyuehe/core/data/playback/ProtoPlaybackSnapshotStore.kt` — atomic full-snapshot reads/writes.
- `core/data/src/main/kotlin/app/yinyuehe/core/data/playback/PlaybackPersistenceModule.kt` — one process-wide DataStore instance.
- `core/data/src/main/kotlin/app/yinyuehe/core/data/playback/AudioReadPermissionChecker.kt` — injectable permission boundary plus API 26–36 Android implementation.
- `core/data/src/main/kotlin/app/yinyuehe/core/data/playback/RoomPlaybackQueueResolver.kt` — Demo/Room resolution with 899 media-ID keys plus one excluded-volume bind per query.
- Modify `gradle/libs.versions.toml`, root `build.gradle.kts`, `core/data/build.gradle.kts`, `DataModule.kt`, and `TrackDao.kt`.
- Add focused mapper/serializer/store/resolver tests under `core/data/src/test/kotlin/app/yinyuehe/core/data/playback/`.

### Create/modify in `:core:player`

- `service/PlaybackRestorePlan.kt`, `service/RestorePersistenceGate.kt`, `service/RestorablePlayer.kt`, and `service/PlaybackRestoreCoordinator.kt` — safe no-autoplay planning plus generation-gated restore.
- `service/PlaybackSnapshotWriter.kt` and `service/PlaybackPersistenceCoordinator.kt` — serialized latest-value writes, 5-second sampling, and bounded close.
- `service/PlaybackOccurrenceTokens.kt`, `service/Media3PlaybackBridge.kt`, `service/PlaybackLibrarySessionCallback.kt`, `service/PlaybackPersistencePlayerListener.kt`, and `service/PlaybackRestoreBarrier.kt` — occurrence identity and Service-owned Media3 integration.
- `PlaybackErrorMapper.kt`, `service/PlaybackFailurePolicy.kt`, `service/Media3FailureNavigator.kt`, `service/PlaybackFailureCoordinator.kt`, `service/StablePlaybackProgress.kt`, and `service/PlaybackFailurePlayerListener.kt` — typed errors, bounded occurrence traversal, and one-second stable-progress reset.
- `ControllerConnectionCoordinator.kt` and `PlaybackSessionProtocol.kt` — one-in-flight 1+4 reconnect and sanitized Session protocol.
- Modify `PlaybackController.kt`, `PlaybackState.kt`, `PlayerSnapshot.kt`, `PlaybackCommandDispatcher.kt`, `Media3PlaybackController.kt`, and `service/PlaybackService.kt`.
- Update `:core:testing` and all affected player/service tests.

### Create/modify in `:feature:library`

- `feature/library/src/main/kotlin/app/yinyuehe/feature/library/MusicBoxEffect.kt` — one-shot TrackSkipped UI effect.
- Modify `MusicBoxAction.kt`, `LibraryViewModel.kt`, `LibraryScreen.kt`, `HomeScreen.kt`, `PlayerScreen.kt`, and `strings.xml`.
- Extend `LibraryViewModelTest.kt`, `PlayerInteractionStateTest.kt`, and `LibraryScreenTest.kt`.

### Device evidence and documentation

- `app/src/debug/AndroidManifest.xml`, `M3ADeviceEntryPoint.kt`, `M3ARestoreBarrierModule.kt`, and `M3AControllerProbeActivity.kt` — debug-only host protocol, deterministic restore barrier, and a remote-process reconnect probe.
- `app/src/androidTest/kotlin/app/yinyuehe/M3A*DeviceTest.kt` plus fixture/controller helpers — position, queue, permission, snapshot-safety, and reconnect assertions.
- `scripts/m3a-device/lib.sh` and `scripts/run-m3a-device-acceptance.sh` — API 36 orchestration, `run-as` artifact capture, force-stop/restart, and phase selection.
- `verification/m3a-acceptance-scenarios.md` — M3-A-only matrix; do not rewrite the original 21-item matrix.
- `verification/result-2026-07-15-m3a.md` — exact commit/device/commands/output and honest pending boundaries.
- Modify `README.md` only after all corresponding evidence exists.

## Dependency Order

```text
Task 1 domain contracts
  ├─ Task 2 Proto mapper/serializer ── Task 3 DataStore/store
  ├─ Task 4 Room/permission resolver
  └─ Task 5 restore planner/gate/coordinator
       └─ Task 6 snapshot writer
            └─ Task 8 Service restore + persistence wiring

Task 7 repeat/move/tokens/state ───────┘
Task 9 failure policy + notices ── Task 10 Controller reconnect/state
                                      └─ Task 11 Compose/UDF
                                           └─ Task 12 API 36 evidence
                                                └─ Task 13 full gate/docs
```

---

## Task 1: Freeze the Android-free playback recovery contracts

**Files:**

- Create: `core/common/src/main/kotlin/app/yinyuehe/core/common/playback/PlaybackSnapshot.kt`
- Create: `core/common/src/main/kotlin/app/yinyuehe/core/common/playback/PlaybackQueueResolution.kt`
- Create: `core/common/src/main/kotlin/app/yinyuehe/core/common/playback/PlaybackFailure.kt`
- Test: `core/common/src/test/kotlin/app/yinyuehe/core/common/playback/PlaybackSnapshotTest.kt`
- Test: `core/common/src/test/kotlin/app/yinyuehe/core/common/playback/PlaybackQueueResolutionTest.kt`
- Test: `core/common/src/test/kotlin/app/yinyuehe/core/common/playback/PlaybackFailureTest.kt`

**Interfaces:**

- Consumes: existing `app.yinyuehe.core.common.model.Track` and `TrackId`.
- Produces:

```kotlin
enum class PlaybackRepeatMode { OFF, ALL, ONE }

data class PlaybackSnapshot(
  val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
  val mediaIds: List<TrackId> = emptyList(),
  val currentIndex: Int = -1,
  val positionMs: Long = 0,
  val shuffleEnabled: Boolean = false,
  val repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.OFF,
)

sealed interface PlaybackSnapshotReadResult {
  data class Usable(val snapshot: PlaybackSnapshot) : PlaybackSnapshotReadResult
  data class IncompatibleVersion(val version: Int) : PlaybackSnapshotReadResult
}

interface PlaybackSnapshotStore {
  suspend fun read(): PlaybackSnapshotReadResult
  suspend fun write(snapshot: PlaybackSnapshot)
}

enum class PlaybackQueueBlockReason { PERMISSION_DENIED }

sealed interface PlaybackQueueItemResolution

data class PlaybackQueueResolution(
  val items: List<PlaybackQueueItemResolution>,
  val temporaryBlockReason: PlaybackQueueBlockReason? = null,
)

interface PlaybackQueueResolver {
  suspend fun resolve(mediaIds: List<TrackId>): PlaybackQueueResolution
}

enum class PlaybackErrorType { SOURCE_UNAVAILABLE, UNSUPPORTED_FORMAT, DECODER, UNKNOWN }

data class PlaybackError(
  val type: PlaybackErrorType,
  val media3ErrorCode: Int,
  val trackId: TrackId?,
)

enum class PlaybackConnectionError { RETRIES_EXHAUSTED }

sealed interface PlaybackNotice {
  data class TrackSkipped(val error: PlaybackError) : PlaybackNotice
}
```

- [ ] **Step 1: Write the failing snapshot contract tests (2–5 minutes)**

Create `PlaybackSnapshotTest.kt`:

```kotlin
package app.yinyuehe.core.common.playback

import app.yinyuehe.core.common.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackSnapshotTest {
  @Test
  fun emptySnapshot_hasCurrentSchemaAndNoCurrentItem() {
    assertEquals(
      PlaybackSnapshot(
        schemaVersion = 1,
        mediaIds = emptyList(),
        currentIndex = -1,
        positionMs = 0,
        shuffleEnabled = false,
        repeatMode = PlaybackRepeatMode.OFF,
      ),
      PlaybackSnapshot.empty(),
    )
  }

  @Test
  fun duplicateTrackIds_areValidOccurrences() {
    val id = TrackId("demo:morning-pulse")

    val snapshot =
      PlaybackSnapshot(
        mediaIds = listOf(id, id),
        currentIndex = 1,
        positionMs = 250,
        shuffleEnabled = true,
        repeatMode = PlaybackRepeatMode.ALL,
      )

    assertEquals(listOf(id, id), snapshot.mediaIds)
    assertEquals(1, snapshot.currentIndex)
  }

  @Test
  fun emptyQueue_rejectsAnyCurrentIndexOtherThanMinusOne() {
    assertThrows(IllegalArgumentException::class.java) {
      PlaybackSnapshot(mediaIds = emptyList(), currentIndex = 0)
    }
  }

  @Test
  fun nonEmptyQueue_rejectsOutOfBoundsCurrentIndex() {
    assertThrows(IllegalArgumentException::class.java) {
      PlaybackSnapshot(
        mediaIds = listOf(TrackId("local:v1:ZXh0ZXJuYWw:1")),
        currentIndex = 1,
      )
    }
  }

  @Test
  fun negativePosition_isRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      PlaybackSnapshot(
        mediaIds = listOf(TrackId("demo:city-walk")),
        currentIndex = 0,
        positionMs = -1,
      )
    }
  }

  @Test
  fun nonCurrentSchema_isRejectedFromUsableDomainSnapshot() {
    assertThrows(IllegalArgumentException::class.java) {
      PlaybackSnapshot(schemaVersion = 2)
    }
  }
}
```

- [ ] **Step 2: Write the failing occurrence-resolution tests (2–5 minutes)**

Create `PlaybackQueueResolutionTest.kt`:

```kotlin
package app.yinyuehe.core.common.playback

import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackQueueResolutionTest {
  private val demoTrack =
    Track(
      id = TrackId("demo:morning-pulse"),
      title = "Morning Pulse",
      artist = "Demo Band",
      album = "Compose Sessions",
      durationMs = 3_200,
      artworkUri = null,
      sourceUri = "android.resource://app.yinyuehe/1",
      isDemo = true,
    )

  @Test
  fun resolution_preservesOneOrderedResultPerOccurrence() {
    val localId = TrackId("local:v1:ZXh0ZXJuYWw:1")
    val resolution =
      PlaybackQueueResolution(
        items =
          listOf(
            PlaybackQueueItemResolution.Resolved(0, demoTrack.id, demoTrack),
            PlaybackQueueItemResolution.TemporarilyBlocked(
              originalIndex = 1,
              trackId = localId,
              reason = PlaybackQueueBlockReason.PERMISSION_DENIED,
            ),
            PlaybackQueueItemResolution.Resolved(2, demoTrack.id, demoTrack),
          ),
        temporaryBlockReason = PlaybackQueueBlockReason.PERMISSION_DENIED,
      )

    assertEquals(listOf(0, 1, 2), resolution.items.map { it.originalIndex })
    assertEquals(
      listOf(demoTrack.id, localId, demoTrack.id),
      resolution.items.map { it.trackId },
    )
  }

  @Test
  fun nonContiguousOriginalIndexes_areRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      PlaybackQueueResolution(
        items =
          listOf(
            PlaybackQueueItemResolution.Resolved(1, demoTrack.id, demoTrack),
          )
      )
    }
  }

  @Test
  fun resolvedTrackIdMustMatchOccurrenceTrackId() {
    assertThrows(IllegalArgumentException::class.java) {
      PlaybackQueueResolution(
        items =
          listOf(
            PlaybackQueueItemResolution.Resolved(
              originalIndex = 0,
              trackId = TrackId("demo:different"),
              track = demoTrack,
            )
          )
      )
    }
  }

  @Test
  fun blockedItemsRequireTheMatchingAggregateReason() {
    assertThrows(IllegalArgumentException::class.java) {
      PlaybackQueueResolution(
        items =
          listOf(
            PlaybackQueueItemResolution.TemporarilyBlocked(
              originalIndex = 0,
              trackId = TrackId("local:v1:ZXh0ZXJuYWw:1"),
              reason = PlaybackQueueBlockReason.PERMISSION_DENIED,
            )
          ),
        temporaryBlockReason = null,
      )
    }
  }
}
```

Create `PlaybackFailureTest.kt`:

```kotlin
package app.yinyuehe.core.common.playback

import app.yinyuehe.core.common.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackFailureTest {
  @Test
  fun skippedNoticeCarriesOnlyTypedDomainErrorData() {
    val error =
      PlaybackError(
        type = PlaybackErrorType.DECODER,
        media3ErrorCode = 4003,
        trackId = TrackId("local:v1:ZXh0ZXJuYWw:7"),
      )

    assertEquals(error, PlaybackNotice.TrackSkipped(error).error)
    assertEquals(PlaybackConnectionError.RETRIES_EXHAUSTED.name, "RETRIES_EXHAUSTED")
  }
}
```

- [ ] **Step 3: Run the focused tests and verify RED (2–5 minutes)**

Run:

```bash
./gradlew :core:common:test \
  --tests '*PlaybackSnapshotTest' \
  --tests '*PlaybackQueueResolutionTest' \
  --tests '*PlaybackFailureTest' --stacktrace
```

Expected: Kotlin compilation fails with unresolved references to the snapshot, queue-resolution, typed failure, connection failure, and notice contracts.

- [ ] **Step 4: Implement the minimal snapshot contracts (2–5 minutes)**

Create `PlaybackSnapshot.kt`:

```kotlin
package app.yinyuehe.core.common.playback

import app.yinyuehe.core.common.model.TrackId

enum class PlaybackRepeatMode { OFF, ALL, ONE }

data class PlaybackSnapshot(
  val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
  val mediaIds: List<TrackId> = emptyList(),
  val currentIndex: Int = -1,
  val positionMs: Long = 0,
  val shuffleEnabled: Boolean = false,
  val repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.OFF,
) {
  init {
    require(schemaVersion == CURRENT_SCHEMA_VERSION) {
      "Usable playback snapshots must use schema $CURRENT_SCHEMA_VERSION"
    }
    require(positionMs >= 0) { "Playback position must not be negative" }
    require(
      if (mediaIds.isEmpty()) {
        currentIndex == -1
      } else {
        currentIndex in mediaIds.indices
      }
    ) { "Playback current index must match the queue" }
  }

  companion object {
    const val CURRENT_SCHEMA_VERSION: Int = 1

    fun empty(): PlaybackSnapshot = PlaybackSnapshot()
  }
}

sealed interface PlaybackSnapshotReadResult {
  data class Usable(val snapshot: PlaybackSnapshot) : PlaybackSnapshotReadResult

  data class IncompatibleVersion(val version: Int) : PlaybackSnapshotReadResult
}

interface PlaybackSnapshotStore {
  suspend fun read(): PlaybackSnapshotReadResult

  suspend fun write(snapshot: PlaybackSnapshot)
}
```

- [ ] **Step 5: Implement the minimal queue-resolution and failure contracts (2–5 minutes)**

Create `PlaybackQueueResolution.kt`:

```kotlin
package app.yinyuehe.core.common.playback

import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId

enum class PlaybackQueueBlockReason { PERMISSION_DENIED }

sealed interface PlaybackQueueItemResolution {
  val originalIndex: Int
  val trackId: TrackId

  data class Resolved(
    override val originalIndex: Int,
    override val trackId: TrackId,
    val track: Track,
  ) : PlaybackQueueItemResolution

  data class PermanentlyMissing(
    override val originalIndex: Int,
    override val trackId: TrackId,
  ) : PlaybackQueueItemResolution

  data class TemporarilyBlocked(
    override val originalIndex: Int,
    override val trackId: TrackId,
    val reason: PlaybackQueueBlockReason,
  ) : PlaybackQueueItemResolution
}

data class PlaybackQueueResolution(
  val items: List<PlaybackQueueItemResolution>,
  val temporaryBlockReason: PlaybackQueueBlockReason? = null,
) {
  init {
    require(items.map { it.originalIndex } == items.indices.toList()) {
      "Queue resolution must preserve one ordered result per occurrence"
    }
    require(
      items
        .filterIsInstance<PlaybackQueueItemResolution.Resolved>()
        .all { item -> item.track.id == item.trackId }
    ) { "Resolved track identity must match its occurrence" }
    val blocked = items.filterIsInstance<PlaybackQueueItemResolution.TemporarilyBlocked>()
    require(
      if (temporaryBlockReason == null) {
        blocked.isEmpty()
      } else {
        blocked.isNotEmpty() && blocked.all { item -> item.reason == temporaryBlockReason }
      }
    ) { "Temporary block reason must describe every blocked occurrence" }
  }
}

interface PlaybackQueueResolver {
  suspend fun resolve(mediaIds: List<TrackId>): PlaybackQueueResolution
}
```

Create `PlaybackFailure.kt`:

```kotlin
package app.yinyuehe.core.common.playback

import app.yinyuehe.core.common.model.TrackId

enum class PlaybackErrorType {
  SOURCE_UNAVAILABLE,
  UNSUPPORTED_FORMAT,
  DECODER,
  UNKNOWN,
}

data class PlaybackError(
  val type: PlaybackErrorType,
  val media3ErrorCode: Int,
  val trackId: TrackId?,
)

enum class PlaybackConnectionError { RETRIES_EXHAUSTED }

sealed interface PlaybackNotice {
  data class TrackSkipped(val error: PlaybackError) : PlaybackNotice
}
```

- [ ] **Step 6: Run GREEN and the common-module regression (2–5 minutes)**

Run:

```bash
./gradlew :core:common:test --stacktrace
```

Expected: `BUILD SUCCESSFUL`; all existing `TrackTest` cases and all three new playback contract test classes pass.

- [ ] **Step 7: Commit the domain-contract task atomically (2–5 minutes)**

```bash
git add core/common/src/main/kotlin/app/yinyuehe/core/common/playback \
  core/common/src/test/kotlin/app/yinyuehe/core/common/playback
git commit -m "feat: define playback recovery contracts"
```

- [ ] **Step 8: Run the two-stage task review gate (2–5 minutes to dispatch)**

Dispatch a fresh specification-compliance reviewer with Task 1, the approved M3-A design, the Task 1 commit, and `:core:common:test` output. After it approves, dispatch a different fresh code-quality reviewer. Resolve every Critical or Important finding, rerun `./gradlew :core:common:test --stacktrace`, amend the Task 1 commit with `git commit --amend --no-edit`, and repeat both reviews before Task 2.

---

## Task 2: Add the Proto schema, normalization mapper, and corruption-aware serializer

**Files:**

- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`
- Modify: `core/data/build.gradle.kts`
- Create: `core/data/src/main/proto/playback_snapshot.proto`
- Create: `core/data/src/main/kotlin/app/yinyuehe/core/data/playback/PlaybackSnapshotProtoMapper.kt`
- Create: `core/data/src/main/kotlin/app/yinyuehe/core/data/playback/PlaybackSnapshotSerializer.kt`
- Test: `core/data/src/test/kotlin/app/yinyuehe/core/data/playback/PlaybackSnapshotProtoMapperTest.kt`
- Test: `core/data/src/test/kotlin/app/yinyuehe/core/data/playback/PlaybackSnapshotSerializerTest.kt`

**Interfaces:**

- Consumes: `PlaybackSnapshot`, `PlaybackSnapshotReadResult`, `PlaybackRepeatMode`, and `TrackId` from Task 1.
- Produces:

```kotlin
internal fun PlaybackSnapshotProto.toReadResult(): PlaybackSnapshotReadResult
internal fun PlaybackSnapshot.toProto(): PlaybackSnapshotProto
internal object PlaybackSnapshotSerializer : Serializer<PlaybackSnapshotProto>
```

- Produces generated immutable wire types `PlaybackSnapshotProto` and `PlaybackRepeatModeProto` in `app.yinyuehe.core.data.playback.proto`.

- [ ] **Step 1: Write the failing Proto normalization tests (2–5 minutes)**

Create `PlaybackSnapshotProtoMapperTest.kt`:

```kotlin
package app.yinyuehe.core.data.playback

import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackRepeatMode
import app.yinyuehe.core.common.playback.PlaybackSnapshot
import app.yinyuehe.core.common.playback.PlaybackSnapshotReadResult
import app.yinyuehe.core.data.playback.proto.PlaybackRepeatModeProto
import app.yinyuehe.core.data.playback.proto.PlaybackSnapshotProto
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSnapshotProtoMapperTest {
  @Test
  fun zeroByteDefaultInstance_isFirstInstallEmptySnapshot() {
    assertEquals(
      PlaybackSnapshotReadResult.Usable(PlaybackSnapshot.empty()),
      PlaybackSnapshotProto.getDefaultInstance().toReadResult(),
    )
  }

  @Test
  fun schemaZeroWithPayload_isIncompatibleAndNotGuessed() {
    val proto = PlaybackSnapshotProto.newBuilder().setCurrentIndex(-1).build()

    assertEquals(
      PlaybackSnapshotReadResult.IncompatibleVersion(0),
      proto.toReadResult(),
    )
  }

  @Test
  fun futureAndNegativeVersions_areIncompatible() {
    val future = PlaybackSnapshotProto.newBuilder().setSchemaVersion(2).build()
    val negative = PlaybackSnapshotProto.newBuilder().setSchemaVersion(-1).build()

    assertEquals(
      PlaybackSnapshotReadResult.IncompatibleVersion(2),
      future.toReadResult(),
    )
    assertEquals(
      PlaybackSnapshotReadResult.IncompatibleVersion(-1),
      negative.toReadResult(),
    )
  }

  @Test
  fun blankCurrentId_selectsSuccessorAndResetsPosition() {
    val proto =
      PlaybackSnapshotProto.newBuilder()
        .setSchemaVersion(1)
        .addAllMediaIds(listOf("local:v1:ZXh0ZXJuYWw:1", " ", "demo:city-walk"))
        .setCurrentIndex(1)
        .setPositionMs(777)
        .setShuffleEnabled(true)
        .setRepeatMode(PlaybackRepeatModeProto.PLAYBACK_REPEAT_MODE_ALL)
        .build()

    assertEquals(
      PlaybackSnapshotReadResult.Usable(
        PlaybackSnapshot(
          mediaIds =
            listOf(
              TrackId("local:v1:ZXh0ZXJuYWw:1"),
              TrackId("demo:city-walk"),
            ),
          currentIndex = 1,
          positionMs = 0,
          shuffleEnabled = true,
          repeatMode = PlaybackRepeatMode.ALL,
        )
      ),
      proto.toReadResult(),
    )
  }

  @Test
  fun survivingCurrentId_reindexesAndPreservesNonNegativePosition() {
    val proto =
      PlaybackSnapshotProto.newBuilder()
        .setSchemaVersion(1)
        .addAllMediaIds(listOf(" ", "demo:city-walk", "demo:night-drive"))
        .setCurrentIndex(2)
        .setPositionMs(444)
        .build()

    assertEquals(
      PlaybackSnapshotReadResult.Usable(
        PlaybackSnapshot(
          mediaIds = listOf(TrackId("demo:city-walk"), TrackId("demo:night-drive")),
          currentIndex = 1,
          positionMs = 444,
        )
      ),
      proto.toReadResult(),
    )
  }

  @Test
  fun invalidNumbersAreNormalizedWithoutLosingModes() {
    val proto =
      PlaybackSnapshotProto.newBuilder()
        .setSchemaVersion(1)
        .addMediaIds("demo:city-walk")
        .setCurrentIndex(Int.MAX_VALUE)
        .setPositionMs(-20)
        .setShuffleEnabled(true)
        .setRepeatModeValue(99)
        .build()

    assertEquals(
      PlaybackSnapshotReadResult.Usable(
        PlaybackSnapshot(
          mediaIds = listOf(TrackId("demo:city-walk")),
          currentIndex = 0,
          positionMs = 0,
          shuffleEnabled = true,
          repeatMode = PlaybackRepeatMode.OFF,
        )
      ),
      proto.toReadResult(),
    )
  }

  @Test
  fun domainRoundTrip_preservesDuplicatesAndModes() {
    val id = TrackId("demo:soft-echo")
    val snapshot =
      PlaybackSnapshot(
        mediaIds = listOf(id, id),
        currentIndex = 1,
        positionMs = 300,
        shuffleEnabled = true,
        repeatMode = PlaybackRepeatMode.ONE,
      )

    assertEquals(
      PlaybackSnapshotReadResult.Usable(snapshot),
      snapshot.toProto().toReadResult(),
    )
  }
}
```

- [ ] **Step 2: Write the failing corruption-boundary serializer test (2–5 minutes)**

Create `PlaybackSnapshotSerializerTest.kt`:

```kotlin
package app.yinyuehe.core.data.playback

import androidx.datastore.core.CorruptionException
import app.yinyuehe.core.data.playback.proto.PlaybackSnapshotProto
import com.google.protobuf.InvalidProtocolBufferException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSnapshotSerializerTest {
  @Test
  fun invalidProto_isReportedAsCorruption() = runTest {
    val failure =
      runCatching {
        PlaybackSnapshotSerializer.readFrom(
          ByteArrayInputStream(byteArrayOf(0x0A, 0x02, 0x01))
        )
      }.exceptionOrNull()

    assertTrue(failure is CorruptionException)
    assertTrue(failure?.cause is InvalidProtocolBufferException)
  }

  @Test
  fun ordinaryIoFailure_isNotReclassifiedAsCorruption() = runTest {
    val input =
      object : ByteArrayInputStream(byteArrayOf()) {
        override fun read(): Int = throw IOException("read failed")

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
          throw IOException("read failed")
      }

    val failure = runCatching { PlaybackSnapshotSerializer.readFrom(input) }.exceptionOrNull()

    assertTrue(failure is IOException)
    assertFalse(failure is CorruptionException)
  }

  @Test
  fun writeTo_emitsExactlyTheProtoBytes() = runTest {
    val proto = PlaybackSnapshotProto.newBuilder().setSchemaVersion(1).setCurrentIndex(-1).build()
    val output = ByteArrayOutputStream()

    PlaybackSnapshotSerializer.writeTo(proto, output)

    assertArrayEquals(proto.toByteArray(), output.toByteArray())
    assertEquals(PlaybackSnapshotProto.getDefaultInstance(), PlaybackSnapshotSerializer.defaultValue)
  }
}
```

- [ ] **Step 3: Run the focused tests and verify RED (2–5 minutes)**

Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*PlaybackSnapshotProtoMapperTest' \
  --tests '*PlaybackSnapshotSerializerTest' --stacktrace
```

Expected: Kotlin compilation fails because the generated Proto types, mapper functions, and `PlaybackSnapshotSerializer` do not exist.

- [ ] **Step 4: Add the pinned stable Proto/DataStore build inputs and schema (2–5 minutes)**

Add these exact entries to `gradle/libs.versions.toml`:

```toml
[versions]
datastore = "1.2.1"
protobuf = "4.32.1"
protobufPlugin = "0.9.5"

[libraries]
androidx-datastore = { module = "androidx.datastore:datastore", version.ref = "datastore" }
protobuf-kotlin-lite = { module = "com.google.protobuf:protobuf-kotlin-lite", version.ref = "protobuf" }

[plugins]
protobuf = { id = "com.google.protobuf", version.ref = "protobufPlugin" }
```

Add the plugin declaration to the existing root `plugins` block in `build.gradle.kts`:

```kotlin
alias(libs.plugins.protobuf) apply false
```

Apply and configure it in `core/data/build.gradle.kts`:

```kotlin
plugins {
  id("yinyuehe.android.library")
  alias(libs.plugins.hilt)
  alias(libs.plugins.ksp)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.room)
}

protobuf {
  protoc {
    artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
  }
  generateProtoTasks {
    all().configureEach {
      builtins {
        named("java") { option("lite") }
        create("kotlin") { option("lite") }
      }
    }
  }
}
```

Add these two lines inside the existing `dependencies` block:

```kotlin
implementation(libs.androidx.datastore)
implementation(libs.protobuf.kotlin.lite)
```

Create `playback_snapshot.proto`:

```proto
syntax = "proto3";

package app.yinyuehe.core.data.playback.proto;
option java_package = "app.yinyuehe.core.data.playback.proto";
option java_multiple_files = true;
option java_outer_classname = "PlaybackSnapshotSchema";

enum PlaybackRepeatModeProto {
  PLAYBACK_REPEAT_MODE_OFF = 0;
  PLAYBACK_REPEAT_MODE_ALL = 1;
  PLAYBACK_REPEAT_MODE_ONE = 2;
}

message PlaybackSnapshotProto {
  int32 schema_version = 1;
  repeated string media_ids = 2;
  int32 current_index = 3;
  int64 position_ms = 4;
  bool shuffle_enabled = 5;
  PlaybackRepeatModeProto repeat_mode = 6;
}
```

- [ ] **Step 5: Implement exact normalization and wire mapping (2–5 minutes)**

Create `PlaybackSnapshotProtoMapper.kt`:

```kotlin
package app.yinyuehe.core.data.playback

import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackRepeatMode
import app.yinyuehe.core.common.playback.PlaybackSnapshot
import app.yinyuehe.core.common.playback.PlaybackSnapshotReadResult
import app.yinyuehe.core.data.playback.proto.PlaybackRepeatModeProto
import app.yinyuehe.core.data.playback.proto.PlaybackSnapshotProto

internal fun PlaybackSnapshotProto.toReadResult(): PlaybackSnapshotReadResult {
  if (schemaVersion != PlaybackSnapshot.CURRENT_SCHEMA_VERSION) {
    return if (schemaVersion == 0 && serializedSize == 0) {
      PlaybackSnapshotReadResult.Usable(PlaybackSnapshot.empty())
    } else {
      PlaybackSnapshotReadResult.IncompatibleVersion(schemaVersion)
    }
  }

  val rawIds = mediaIdsList
  val repeatMode = repeatModeFromWire()
  if (rawIds.isEmpty()) {
    return PlaybackSnapshotReadResult.Usable(
      PlaybackSnapshot(
        mediaIds = emptyList(),
        currentIndex = -1,
        positionMs = 0,
        shuffleEnabled = shuffleEnabled,
        repeatMode = repeatMode,
      )
    )
  }

  val originalCurrentIndex = currentIndex.coerceIn(rawIds.indices)
  val keptIds =
    rawIds.mapIndexedNotNull { originalIndex, rawId ->
      rawId
        .takeIf(String::isNotBlank)
        ?.let { id -> IndexedTrackId(originalIndex, TrackId(id)) }
    }
  if (keptIds.isEmpty()) {
    return PlaybackSnapshotReadResult.Usable(
      PlaybackSnapshot(
        mediaIds = emptyList(),
        currentIndex = -1,
        positionMs = 0,
        shuffleEnabled = shuffleEnabled,
        repeatMode = repeatMode,
      )
    )
  }

  val currentIdSurvives = rawIds[originalCurrentIndex].isNotBlank()
  val normalizedCurrentIndex =
    if (currentIdSurvives) {
      keptIds.indexOfFirst { item -> item.originalIndex == originalCurrentIndex }
    } else {
      val selected =
        keptIds.firstOrNull { item -> item.originalIndex > originalCurrentIndex }
          ?: keptIds.last { item -> item.originalIndex < originalCurrentIndex }
      keptIds.indexOf(selected)
    }

  return PlaybackSnapshotReadResult.Usable(
    PlaybackSnapshot(
      mediaIds = keptIds.map(IndexedTrackId::trackId),
      currentIndex = normalizedCurrentIndex,
      positionMs = if (currentIdSurvives) positionMs.coerceAtLeast(0) else 0,
      shuffleEnabled = shuffleEnabled,
      repeatMode = repeatMode,
    )
  )
}

internal fun PlaybackSnapshot.toProto(): PlaybackSnapshotProto =
  PlaybackSnapshotProto.newBuilder()
    .setSchemaVersion(schemaVersion)
    .addAllMediaIds(mediaIds.map { id -> id.value })
    .setCurrentIndex(currentIndex)
    .setPositionMs(positionMs)
    .setShuffleEnabled(shuffleEnabled)
    .setRepeatMode(
      when (repeatMode) {
        PlaybackRepeatMode.OFF -> PlaybackRepeatModeProto.PLAYBACK_REPEAT_MODE_OFF
        PlaybackRepeatMode.ALL -> PlaybackRepeatModeProto.PLAYBACK_REPEAT_MODE_ALL
        PlaybackRepeatMode.ONE -> PlaybackRepeatModeProto.PLAYBACK_REPEAT_MODE_ONE
      }
    )
    .build()

private fun PlaybackSnapshotProto.repeatModeFromWire(): PlaybackRepeatMode =
  when (repeatModeValue) {
    PlaybackRepeatModeProto.PLAYBACK_REPEAT_MODE_ALL.number -> PlaybackRepeatMode.ALL
    PlaybackRepeatModeProto.PLAYBACK_REPEAT_MODE_ONE.number -> PlaybackRepeatMode.ONE
    else -> PlaybackRepeatMode.OFF
  }

private data class IndexedTrackId(
  val originalIndex: Int,
  val trackId: TrackId,
)
```

- [ ] **Step 6: Implement the corruption-only serializer boundary (2–5 minutes)**

Create `PlaybackSnapshotSerializer.kt`:

```kotlin
package app.yinyuehe.core.data.playback

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import app.yinyuehe.core.data.playback.proto.PlaybackSnapshotProto
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

internal object PlaybackSnapshotSerializer : Serializer<PlaybackSnapshotProto> {
  override val defaultValue: PlaybackSnapshotProto = PlaybackSnapshotProto.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): PlaybackSnapshotProto {
    val bytes = input.readBytes()
    return try {
      PlaybackSnapshotProto.parseFrom(bytes)
    } catch (failure: InvalidProtocolBufferException) {
      throw CorruptionException("Unable to read playback snapshot proto", failure)
    }
  }

  override suspend fun writeTo(t: PlaybackSnapshotProto, output: OutputStream) {
    t.writeTo(output)
  }
}
```

- [ ] **Step 7: Run GREEN and the affected-module regression (2–5 minutes)**

Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*PlaybackSnapshotProtoMapperTest' \
  --tests '*PlaybackSnapshotSerializerTest' --stacktrace
./gradlew :core:common:test :core:data:compileDebugKotlin --stacktrace
```

Expected: both commands finish with `BUILD SUCCESSFUL`; normalization, round-trip, unknown repeat, corruption, and ordinary IO propagation cases pass.

- [ ] **Step 8: Commit the Proto boundary atomically (2–5 minutes)**

```bash
git add gradle/libs.versions.toml build.gradle.kts core/data/build.gradle.kts \
  core/data/src/main/proto/playback_snapshot.proto \
  core/data/src/main/kotlin/app/yinyuehe/core/data/playback/PlaybackSnapshotProtoMapper.kt \
  core/data/src/main/kotlin/app/yinyuehe/core/data/playback/PlaybackSnapshotSerializer.kt \
  core/data/src/test/kotlin/app/yinyuehe/core/data/playback/PlaybackSnapshotProtoMapperTest.kt \
  core/data/src/test/kotlin/app/yinyuehe/core/data/playback/PlaybackSnapshotSerializerTest.kt
git commit -m "feat: add playback snapshot proto mapping"
```

- [ ] **Step 9: Run the two-stage task review gate (2–5 minutes to dispatch)**

Dispatch a fresh specification-compliance reviewer with Task 2, approved design sections 5 and 13.1, the Task 2 commit, and focused test output. After approval, dispatch a different fresh code-quality reviewer. Resolve every Critical or Important finding, rerun both Step 7 commands, amend with `git commit --amend --no-edit`, and repeat both reviews before Task 3.

---

## Task 3: Provide one process-scoped DataStore and the atomic snapshot store

**Files:**

- Create: `core/data/src/main/kotlin/app/yinyuehe/core/data/playback/ProtoPlaybackSnapshotStore.kt`
- Create: `core/data/src/main/kotlin/app/yinyuehe/core/data/playback/PlaybackPersistenceModule.kt`
- Modify: `core/data/src/main/kotlin/app/yinyuehe/core/data/DataModule.kt`
- Test: `core/data/src/test/kotlin/app/yinyuehe/core/data/playback/PlaybackSnapshotDataStoreIntegrationTest.kt`

**Interfaces:**

- Consumes: `PlaybackSnapshotStore`, `PlaybackSnapshotReadResult`, `PlaybackSnapshot`, `PlaybackSnapshotProto.toReadResult()`, `PlaybackSnapshot.toProto()`, and `PlaybackSnapshotSerializer` from Tasks 1–2.
- Produces:

```kotlin
@Singleton
internal class ProtoPlaybackSnapshotStore @Inject constructor(
  private val dataStore: DataStore<PlaybackSnapshotProto>,
) : PlaybackSnapshotStore

internal fun createPlaybackSnapshotDataStore(
  produceFile: () -> File,
  scope: CoroutineScope,
): DataStore<PlaybackSnapshotProto>
```

- Produces one Hilt `@Singleton DataStore<PlaybackSnapshotProto>` for `playback_snapshot.pb` and one `@Singleton PlaybackSnapshotStore` binding. The application and `PlaybackService` currently share one process; no second DataStore instance may target this file concurrently.

- [ ] **Step 1: Write the failing real-file restart and future-version tests (2–5 minutes)**

Create `PlaybackSnapshotDataStoreIntegrationTest.kt`:

```kotlin
package app.yinyuehe.core.data.playback

import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackRepeatMode
import app.yinyuehe.core.common.playback.PlaybackSnapshot
import app.yinyuehe.core.common.playback.PlaybackSnapshotReadResult
import app.yinyuehe.core.data.playback.proto.PlaybackSnapshotProto
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackSnapshotDataStoreIntegrationTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun missingFirstInstallFile_readsAsUsableEmptySnapshot() = runTest {
    val file = newSnapshotFile()
    assertFalse(file.exists())
    val harness = newHarness(file)
    try {
      assertEquals(
        PlaybackSnapshotReadResult.Usable(PlaybackSnapshot.empty()),
        harness.store.read(),
      )
    } finally {
      harness.close()
    }
  }

  @Test
  fun writeThenRecreateDataStore_restoresTheExactSnapshot() = runTest {
    val file = newSnapshotFile()
    val snapshot =
      PlaybackSnapshot(
        mediaIds =
          listOf(
            TrackId("demo:morning-pulse"),
            TrackId("demo:morning-pulse"),
          ),
        currentIndex = 1,
        positionMs = 1_250,
        shuffleEnabled = true,
        repeatMode = PlaybackRepeatMode.ALL,
      )
    val first = newHarness(file)
    try {
      first.store.write(snapshot)
    } finally {
      first.close()
    }

    val second = newHarness(file)
    try {
      assertEquals(
        PlaybackSnapshotReadResult.Usable(snapshot),
        second.store.read(),
      )
    } finally {
      second.close()
    }
  }

  @Test
  fun corruptedBytes_areReplacedWithTheDefaultEmptySnapshot() = runTest {
    val file = newSnapshotFile()
    file.writeBytes(byteArrayOf(0x0A, 0x02, 0x01))
    val harness = newHarness(file)
    try {
      assertEquals(
        PlaybackSnapshotReadResult.Usable(PlaybackSnapshot.empty()),
        harness.store.read(),
      )
    } finally {
      harness.close()
    }

    assertEquals(
      PlaybackSnapshotProto.getDefaultInstance(),
      PlaybackSnapshotProto.parseFrom(file.readBytes()),
    )
  }

  @Test
  fun futureVersionRead_doesNotRewriteEvenOneByte() = runTest {
    val file = newSnapshotFile()
    val future =
      PlaybackSnapshotProto.newBuilder()
        .setSchemaVersion(99)
        .addMediaIds("local:v1:ZXh0ZXJuYWw:42")
        .setCurrentIndex(0)
        .setPositionMs(9_000)
        .build()
    file.writeBytes(future.toByteArray())
    val bytesBeforeRead = file.readBytes()
    val harness = newHarness(file)
    try {
      assertEquals(
        PlaybackSnapshotReadResult.IncompatibleVersion(99),
        harness.store.read(),
      )
    } finally {
      harness.close()
    }

    assertArrayEquals(bytesBeforeRead, file.readBytes())
  }

  @Test
  fun schemaZeroWithPayload_isIncompatibleAndRemainsByteIdentical() = runTest {
    val file = newSnapshotFile()
    val legacy = PlaybackSnapshotProto.newBuilder().setCurrentIndex(-1).build()
    file.writeBytes(legacy.toByteArray())
    val bytesBeforeRead = file.readBytes()
    val harness = newHarness(file)
    try {
      assertEquals(
        PlaybackSnapshotReadResult.IncompatibleVersion(0),
        harness.store.read(),
      )
    } finally {
      harness.close()
    }

    assertArrayEquals(bytesBeforeRead, file.readBytes())
  }

  @Test
  fun concurrentWholeSnapshotWrites_leaveOneCompleteValidSnapshot() = runTest {
    val file = newSnapshotFile()
    val harness = newHarness(file)
    val snapshots =
      (0 until 20).map { index ->
        PlaybackSnapshot(
          mediaIds = listOf(TrackId("local:v1:ZXh0ZXJuYWw:$index")),
          currentIndex = 0,
          positionMs = index.toLong(),
          shuffleEnabled = index % 2 == 0,
          repeatMode = PlaybackRepeatMode.entries[index % PlaybackRepeatMode.entries.size],
        )
      }
    try {
      coroutineScope {
        snapshots
          .map { snapshot ->
            async(Dispatchers.Default) { harness.store.write(snapshot) }
          }
          .awaitAll()
      }

      val result = harness.store.read() as PlaybackSnapshotReadResult.Usable
      assertTrue(result.snapshot in snapshots)
    } finally {
      harness.close()
    }
    PlaybackSnapshotProto.parseFrom(file.readBytes())
  }

  private fun newSnapshotFile(): File =
    File(temporaryFolder.newFolder(), "playback_snapshot.pb")

  private fun newHarness(file: File): StoreHarness {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dataStore = createPlaybackSnapshotDataStore(produceFile = { file }, scope = scope)
    return StoreHarness(scope, ProtoPlaybackSnapshotStore(dataStore))
  }
}

private data class StoreHarness(
  val scope: CoroutineScope,
  val store: ProtoPlaybackSnapshotStore,
) {
  suspend fun close() {
    scope.coroutineContext[Job]?.cancelAndJoin()
  }
}
```

- [ ] **Step 2: Run the integration test and verify RED (2–5 minutes)**

Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*PlaybackSnapshotDataStoreIntegrationTest' --stacktrace
```

Expected: Kotlin compilation fails with unresolved references to `createPlaybackSnapshotDataStore` and `ProtoPlaybackSnapshotStore`.

- [ ] **Step 3: Implement the atomic Store adapter (2–5 minutes)**

Create `ProtoPlaybackSnapshotStore.kt`:

```kotlin
package app.yinyuehe.core.data.playback

import androidx.datastore.core.DataStore
import app.yinyuehe.core.common.playback.PlaybackSnapshot
import app.yinyuehe.core.common.playback.PlaybackSnapshotReadResult
import app.yinyuehe.core.common.playback.PlaybackSnapshotStore
import app.yinyuehe.core.data.playback.proto.PlaybackSnapshotProto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
internal class ProtoPlaybackSnapshotStore @Inject constructor(
  private val dataStore: DataStore<PlaybackSnapshotProto>,
) : PlaybackSnapshotStore {
  override suspend fun read(): PlaybackSnapshotReadResult =
    dataStore.data.first().toReadResult()

  override suspend fun write(snapshot: PlaybackSnapshot) {
    dataStore.updateData { snapshot.toProto() }
  }
}
```

- [ ] **Step 4: Create the process-scoped DataStore factory and Hilt provider (2–5 minutes)**

Create `PlaybackPersistenceModule.kt`:

```kotlin
package app.yinyuehe.core.data.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.yinyuehe.core.data.playback.proto.PlaybackSnapshotProto
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object PlaybackPersistenceModule {
  @Provides
  @Singleton
  fun providePlaybackSnapshotDataStore(
    @ApplicationContext context: Context,
  ): DataStore<PlaybackSnapshotProto> =
    createPlaybackSnapshotDataStore(
      produceFile = { context.dataStoreFile(PLAYBACK_SNAPSHOT_FILE_NAME) },
      scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )
}

internal fun createPlaybackSnapshotDataStore(
  produceFile: () -> File,
  scope: CoroutineScope,
): DataStore<PlaybackSnapshotProto> =
  DataStoreFactory.create(
    serializer = PlaybackSnapshotSerializer,
    corruptionHandler =
      ReplaceFileCorruptionHandler {
        PlaybackSnapshotSerializer.defaultValue
      },
    scope = scope,
    produceFile = produceFile,
  )

internal const val PLAYBACK_SNAPSHOT_FILE_NAME: String = "playback_snapshot.pb"
```

- [ ] **Step 5: Bind the Store interface without adding a player-to-data dependency (2–5 minutes)**

Add these exact imports to `DataModule.kt`:

```kotlin
import app.yinyuehe.core.common.playback.PlaybackSnapshotStore
import app.yinyuehe.core.data.playback.ProtoPlaybackSnapshotStore
```

Add this binding inside the existing `DataModule` class:

```kotlin
  @Binds
  @Singleton
  internal abstract fun bindPlaybackSnapshotStore(
    store: ProtoPlaybackSnapshotStore,
  ): PlaybackSnapshotStore
```

Do not add `implementation(project(":core:data"))` to `core/player/build.gradle.kts`; `PlaybackService` will inject the `core:common` interface and Hilt will supply this implementation from the app dependency graph.

- [ ] **Step 6: Run GREEN, Hilt aggregation, and the data-module regression (2–5 minutes)**

Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*PlaybackSnapshotDataStoreIntegrationTest' --stacktrace
./gradlew :core:data:testDebugUnitTest :app:assembleDebug --stacktrace
```

Expected: both commands finish with `BUILD SUCCESSFUL`; the real file reopens, corrupted bytes recover, future-version bytes remain unchanged, concurrent writes leave a parseable complete message, and Hilt generates the singleton binding.

- [ ] **Step 7: Commit the DataStore boundary atomically (2–5 minutes)**

```bash
git add core/data/src/main/kotlin/app/yinyuehe/core/data/DataModule.kt \
  core/data/src/main/kotlin/app/yinyuehe/core/data/playback/ProtoPlaybackSnapshotStore.kt \
  core/data/src/main/kotlin/app/yinyuehe/core/data/playback/PlaybackPersistenceModule.kt \
  core/data/src/test/kotlin/app/yinyuehe/core/data/playback/PlaybackSnapshotDataStoreIntegrationTest.kt
git commit -m "feat: persist playback snapshots with DataStore"
```

- [ ] **Step 8: Run the two-stage task review gate (2–5 minutes to dispatch)**

Dispatch a fresh specification-compliance reviewer with Task 3, approved design sections 3, 4.2, 5, 7, and 13.2, the Task 3 commit, and both Step 6 outputs. After approval, dispatch a different fresh code-quality reviewer. Require explicit confirmation that one Hilt singleton owns the file and that future-version reads never call `updateData`. Resolve every Critical or Important finding, rerun Step 6, amend with `git commit --amend --no-edit`, and repeat both reviews before Task 4.

---

## Task 4: Resolve ordered Demo/local queues with permission blocking and bounded Room batches

**Files:**

- Modify: `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/dao/TrackDao.kt`
- Modify: `core/data/src/main/kotlin/app/yinyuehe/core/data/DataModule.kt`
- Create: `core/data/src/main/kotlin/app/yinyuehe/core/data/playback/AudioReadPermissionChecker.kt`
- Create: `core/data/src/main/kotlin/app/yinyuehe/core/data/playback/RoomPlaybackQueueResolver.kt`
- Test: `core/data/src/test/kotlin/app/yinyuehe/core/data/playback/AndroidAudioReadPermissionCheckerTest.kt`
- Test: `core/data/src/test/kotlin/app/yinyuehe/core/data/playback/RoomPlaybackQueueResolverTest.kt`
- Test: `core/data/src/test/kotlin/app/yinyuehe/core/data/playback/RoomPlaybackQueueResolverIntegrationTest.kt`

**Interfaces:**

- Consumes: Task 1 `PlaybackQueueResolver`, `PlaybackQueueResolution`, `PlaybackQueueItemResolution`, and `PlaybackQueueBlockReason`; existing `DemoTrackCatalog`, `YinYueHeDatabase`, `TrackDao`, `TrackEntity.toDomain()`, and `DEMO_VOLUME_NAME`.
- Produces:

```kotlin
internal fun interface AudioReadPermissionChecker {
  fun hasAudioReadPermission(): Boolean
}

internal fun requiredAudioReadPermission(sdkInt: Int): String

@Singleton
internal class AndroidAudioReadPermissionChecker @Inject constructor(
  @ApplicationContext private val context: Context,
) : AudioReadPermissionChecker

@Singleton
internal class RoomPlaybackQueueResolver @Inject constructor(
  private val database: YinYueHeDatabase,
  demoTrackCatalog: DemoTrackCatalog,
  private val permissionChecker: AudioReadPermissionChecker,
) : PlaybackQueueResolver

internal fun uniqueLocalIdBatches(mediaIds: List<TrackId>): List<List<String>>
internal const val PLAYBACK_QUEUE_QUERY_BATCH_SIZE: Int = 899
```

- Produces Hilt singleton bindings for `PlaybackQueueResolver` and `AudioReadPermissionChecker`. No Room entity, database version, migration, or schema JSON changes are permitted.

- [ ] **Step 1: Write the failing ordered-resolution and permission tests (2–5 minutes)**

Create `RoomPlaybackQueueResolverTest.kt`:

```kotlin
package app.yinyuehe.core.data.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackQueueBlockReason
import app.yinyuehe.core.common.playback.PlaybackQueueItemResolution
import app.yinyuehe.core.data.DemoTrackCatalog
import app.yinyuehe.core.data.local.db.RoomDatabaseRule
import app.yinyuehe.core.data.local.db.trackEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomPlaybackQueueResolverTest {
  @get:Rule val databaseRule = RoomDatabaseRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val demoCatalog
    get() = DemoTrackCatalog(context)

  @Test
  fun mixedQueue_preservesOrderDuplicatesAndPermanentMissingItems() = runTest {
    val localAvailable = TrackId("local:v1:ZXh0ZXJuYWw:1")
    val localUnavailable = TrackId("local:v1:ZXh0ZXJuYWw:2")
    val localMissing = TrackId("local:v1:ZXh0ZXJuYWw:3")
    databaseRule.database.trackDao().upsertTracks(
      listOf(
        trackEntity(
          mediaId = localAvailable.value,
          mediaStoreId = 1,
          isAvailable = true,
        ),
        trackEntity(
          mediaId = localUnavailable.value,
          mediaStoreId = 2,
          isAvailable = false,
        ),
      )
    )
    val demoId = demoCatalog.tracks().first().id
    val requested =
      listOf(
        demoId,
        localAvailable,
        demoId,
        localMissing,
        localAvailable,
        localUnavailable,
      )
    val resolver = resolver(permissionGranted = true)

    val result = resolver.resolve(requested)

    assertNull(result.temporaryBlockReason)
    assertEquals(requested, result.items.map { item -> item.trackId })
    assertEquals(requested.indices.toList(), result.items.map { item -> item.originalIndex })
    assertTrue(result.items[0] is PlaybackQueueItemResolution.Resolved)
    assertTrue(result.items[1] is PlaybackQueueItemResolution.Resolved)
    assertTrue(result.items[2] is PlaybackQueueItemResolution.Resolved)
    assertTrue(result.items[3] is PlaybackQueueItemResolution.PermanentlyMissing)
    assertTrue(result.items[4] is PlaybackQueueItemResolution.Resolved)
    assertTrue(result.items[5] is PlaybackQueueItemResolution.PermanentlyMissing)
  }

  @Test
  fun permissionDenied_resolvesDemoButBlocksEvenCachedLocalRows() = runTest {
    val localId = TrackId("local:v1:ZXh0ZXJuYWw:9")
    databaseRule.database.trackDao().upsertTracks(
      listOf(trackEntity(mediaId = localId.value, mediaStoreId = 9, isAvailable = true))
    )
    val demoId = demoCatalog.tracks().first().id

    val result = resolver(permissionGranted = false).resolve(listOf(demoId, localId))

    assertEquals(PlaybackQueueBlockReason.PERMISSION_DENIED, result.temporaryBlockReason)
    assertTrue(result.items[0] is PlaybackQueueItemResolution.Resolved)
    val blocked = result.items[1] as PlaybackQueueItemResolution.TemporarilyBlocked
    assertEquals(localId, blocked.trackId)
    assertEquals(PlaybackQueueBlockReason.PERMISSION_DENIED, blocked.reason)
  }

  @Test
  fun pureDemoQueue_doesNotConsultAudioPermission() = runTest {
    val resolver =
      RoomPlaybackQueueResolver(
        database = databaseRule.database,
        demoTrackCatalog = demoCatalog,
        permissionChecker =
          AudioReadPermissionChecker {
            error("Pure Demo restore must not check local audio permission")
          },
      )
    val demoIds = demoCatalog.tracks().take(2).map { track -> track.id }

    val result = resolver.resolve(demoIds)

    assertNull(result.temporaryBlockReason)
    assertTrue(result.items.all { item -> item is PlaybackQueueItemResolution.Resolved })
  }

  @Test
  fun moreThanOneSqlLimit_isDeduplicatedIntoExactSafeBatches() {
    val ids =
      (0 until 1_205).map { index -> TrackId("local:v1:dGVzdA:$index") }
    val batches = uniqueLocalIdBatches(ids + ids.first())

    assertEquals(listOf(899, 306), batches.map { batch -> batch.size })
    assertEquals(ids.map { id -> id.value }, batches.flatten())
  }

  @Test
  fun roomFailure_propagatesInsteadOfBecomingAllMissing() = runTest {
    val localId = TrackId("local:v1:ZXh0ZXJuYWw:7")
    val resolver = resolver(permissionGranted = true)
    databaseRule.database.close()

    val failure = runCatching { resolver.resolve(listOf(localId)) }.exceptionOrNull()

    assertNotNull(failure)
  }

  private fun resolver(permissionGranted: Boolean): RoomPlaybackQueueResolver =
    RoomPlaybackQueueResolver(
      database = databaseRule.database,
      demoTrackCatalog = demoCatalog,
      permissionChecker = AudioReadPermissionChecker { permissionGranted },
    )
}
```

- [ ] **Step 2: Write the failing 1,205-row real Room integration test (2–5 minutes)**

Create `RoomPlaybackQueueResolverIntegrationTest.kt`:

```kotlin
package app.yinyuehe.core.data.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackQueueItemResolution
import app.yinyuehe.core.data.DemoTrackCatalog
import app.yinyuehe.core.data.local.db.RoomDatabaseRule
import app.yinyuehe.core.data.local.db.trackEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomPlaybackQueueResolverIntegrationTest {
  @get:Rule val databaseRule = RoomDatabaseRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test
  fun realRoomQuery_rebuildsAtLeast1205UniqueIdsAndADuplicateInOriginalOrder() = runTest {
    val uniqueIds =
      (0 until 1_205).map { index -> TrackId("local:v1:dGVzdA:$index") }
    databaseRule.database.trackDao().upsertTracks(
      uniqueIds.mapIndexed { index, id ->
        trackEntity(
          mediaId = id.value,
          mediaStoreId = index.toLong(),
          isAvailable = true,
        )
      }
    )
    val requested = uniqueIds + uniqueIds[400]
    val resolver =
      RoomPlaybackQueueResolver(
        database = databaseRule.database,
        demoTrackCatalog = DemoTrackCatalog(context),
        permissionChecker = AudioReadPermissionChecker { true },
      )

    val result = resolver.resolve(requested)

    assertEquals(requested, result.items.map { item -> item.trackId })
    assertEquals(requested.indices.toList(), result.items.map { item -> item.originalIndex })
    assertTrue(result.items.all { item -> item is PlaybackQueueItemResolution.Resolved })
  }
}
```

- [ ] **Step 3: Write the failing platform permission-boundary tests (2–5 minutes)**

Create `AndroidAudioReadPermissionCheckerTest.kt`:

```kotlin
package app.yinyuehe.core.data.playback

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class AndroidAudioReadPermissionCheckerTest {
  @Test
  fun permissionName_switchesAtApi33() {
    assertEquals(
      Manifest.permission.READ_EXTERNAL_STORAGE,
      requiredAudioReadPermission(32),
    )
    assertEquals(
      Manifest.permission.READ_MEDIA_AUDIO,
      requiredAudioReadPermission(33),
    )
    assertEquals(
      Manifest.permission.READ_MEDIA_AUDIO,
      requiredAudioReadPermission(36),
    )
  }

  @Test
  @Config(sdk = [33])
  fun checkerReflectsDeniedThenGrantedRuntimePermission() {
    val application = ApplicationProvider.getApplicationContext<Application>()
    val checker = AndroidAudioReadPermissionChecker(application)

    assertFalse(checker.hasAudioReadPermission())
    shadowOf(application).grantPermissions(Manifest.permission.READ_MEDIA_AUDIO)
    assertTrue(checker.hasAudioReadPermission())
  }
}
```

- [ ] **Step 4: Run the focused tests and verify RED (2–5 minutes)**

Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*RoomPlaybackQueueResolverTest' \
  --tests '*RoomPlaybackQueueResolverIntegrationTest' \
  --tests '*AndroidAudioReadPermissionCheckerTest' --stacktrace
```

Expected: Kotlin compilation fails because the permission checker, resolver, batching function, and DAO lookup do not exist.

- [ ] **Step 5: Implement the API-aware permission checker (2–5 minutes)**

Create `AudioReadPermissionChecker.kt`:

```kotlin
package app.yinyuehe.core.data.playback

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

internal fun interface AudioReadPermissionChecker {
  fun hasAudioReadPermission(): Boolean
}

@Singleton
internal class AndroidAudioReadPermissionChecker @Inject constructor(
  @ApplicationContext private val context: Context,
) : AudioReadPermissionChecker {
  override fun hasAudioReadPermission(): Boolean =
    context.checkSelfPermission(requiredAudioReadPermission(Build.VERSION.SDK_INT)) ==
      PackageManager.PERMISSION_GRANTED
}

@SuppressLint("InlinedApi")
internal fun requiredAudioReadPermission(sdkInt: Int): String =
  if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
    Manifest.permission.READ_MEDIA_AUDIO
  } else {
    Manifest.permission.READ_EXTERNAL_STORAGE
  }
```

- [ ] **Step 6: Add the available-ID DAO lookup and implement ordered resolution (2–5 minutes)**

Add this exact method inside `TrackDao`:

```kotlin
  @Query(
    """
    SELECT * FROM tracks
    WHERE isAvailable = 1
      AND volumeName != :excludedVolumeName
      AND mediaId IN (:mediaIds)
    """
  )
  suspend fun findAvailableByMediaIds(
    mediaIds: List<String>,
    excludedVolumeName: String,
  ): List<TrackEntity>
```

Create `RoomPlaybackQueueResolver.kt`:

```kotlin
package app.yinyuehe.core.data.playback

import androidx.room.withTransaction
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackQueueBlockReason
import app.yinyuehe.core.common.playback.PlaybackQueueItemResolution
import app.yinyuehe.core.common.playback.PlaybackQueueResolution
import app.yinyuehe.core.common.playback.PlaybackQueueResolver
import app.yinyuehe.core.data.DemoTrackCatalog
import app.yinyuehe.core.data.local.db.DEMO_VOLUME_NAME
import app.yinyuehe.core.data.local.db.YinYueHeDatabase
import app.yinyuehe.core.data.local.db.toDomain
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class RoomPlaybackQueueResolver @Inject constructor(
  private val database: YinYueHeDatabase,
  demoTrackCatalog: DemoTrackCatalog,
  private val permissionChecker: AudioReadPermissionChecker,
) : PlaybackQueueResolver {
  private val demosById: Map<TrackId, Track> = demoTrackCatalog.tracks().associateBy(Track::id)

  override suspend fun resolve(mediaIds: List<TrackId>): PlaybackQueueResolution {
    val localCandidates =
      mediaIds.filter { id -> id !in demosById && !id.value.startsWith(DEMO_ID_PREFIX) }
    val hasLocalPermission =
      localCandidates.isEmpty() || permissionChecker.hasAudioReadPermission()
    val localTracksById =
      if (localCandidates.isNotEmpty() && hasLocalPermission) {
        database
          .withTransaction {
            uniqueLocalIdBatches(localCandidates).flatMap { batch ->
              database
                .trackDao()
                .findAvailableByMediaIds(
                  mediaIds = batch,
                  excludedVolumeName = DEMO_VOLUME_NAME,
                )
            }
          }
          .map { entity -> entity.toDomain() }
          .associateBy(Track::id)
      } else {
        emptyMap()
      }

    val items =
      mediaIds.mapIndexed { originalIndex, id ->
        val demo = demosById[id]
        when {
          demo != null ->
            PlaybackQueueItemResolution.Resolved(
              originalIndex = originalIndex,
              trackId = id,
              track = demo,
            )
          id.value.startsWith(DEMO_ID_PREFIX) ->
            PlaybackQueueItemResolution.PermanentlyMissing(originalIndex, id)
          !hasLocalPermission ->
            PlaybackQueueItemResolution.TemporarilyBlocked(
              originalIndex = originalIndex,
              trackId = id,
              reason = PlaybackQueueBlockReason.PERMISSION_DENIED,
            )
          else -> {
            val localTrack = localTracksById[id]
            if (localTrack == null) {
              PlaybackQueueItemResolution.PermanentlyMissing(originalIndex, id)
            } else {
              PlaybackQueueItemResolution.Resolved(originalIndex, id, localTrack)
            }
          }
        }
      }

    return PlaybackQueueResolution(
      items = items,
      temporaryBlockReason =
        if (localCandidates.isNotEmpty() && !hasLocalPermission) {
          PlaybackQueueBlockReason.PERMISSION_DENIED
        } else {
          null
        },
    )
  }
}

internal fun uniqueLocalIdBatches(mediaIds: List<TrackId>): List<List<String>> =
  mediaIds
    .asSequence()
    .map { id -> id.value }
    .distinct()
    .chunked(PLAYBACK_QUEUE_QUERY_BATCH_SIZE)
    .toList()

// Room also binds excludedVolumeName, so each query has at most 900 total bind arguments.
internal const val PLAYBACK_QUEUE_QUERY_BATCH_SIZE: Int = 899
private const val DEMO_ID_PREFIX: String = "demo:"
```

- [ ] **Step 7: Bind the permission and resolver interfaces in Hilt (2–5 minutes)**

Add these exact imports to `DataModule.kt`:

```kotlin
import app.yinyuehe.core.common.playback.PlaybackQueueResolver
import app.yinyuehe.core.data.playback.AndroidAudioReadPermissionChecker
import app.yinyuehe.core.data.playback.AudioReadPermissionChecker
import app.yinyuehe.core.data.playback.RoomPlaybackQueueResolver
```

Add these exact bindings inside the existing `DataModule` class:

```kotlin
  @Binds
  @Singleton
  internal abstract fun bindPlaybackQueueResolver(
    resolver: RoomPlaybackQueueResolver,
  ): PlaybackQueueResolver

  @Binds
  @Singleton
  internal abstract fun bindAudioReadPermissionChecker(
    checker: AndroidAudioReadPermissionChecker,
  ): AudioReadPermissionChecker
```

- [ ] **Step 8: Run GREEN, real-storage regression, lint, and Hilt aggregation (2–5 minutes)**

Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*RoomPlaybackQueueResolverTest' \
  --tests '*RoomPlaybackQueueResolverIntegrationTest' \
  --tests '*AndroidAudioReadPermissionCheckerTest' --stacktrace
./gradlew :core:common:test :core:data:testDebugUnitTest \
  :core:data:lintDebug :app:assembleDebug --stacktrace
```

Expected: both commands finish with `BUILD SUCCESSFUL`; the first command proves exact 899/306 media-ID batching (at most 900 total binds after `excludedVolumeName`), 1,205 real Room rows plus a duplicate, Demo restoration without permission, cached-local blocking, permanent missing behavior, API 32/33 permission selection, and Room exception propagation. The second command keeps all existing Room, scanner, repository, lint, and Hilt checks green.

- [ ] **Step 9: Confirm that the Room schema was not modified (2–5 minutes)**

Run:

```bash
git status --short core/data/schemas
git diff --exit-code -- core/data/schemas
```

Expected: both commands produce no output and exit 0; no schema JSON or migration is added.

- [ ] **Step 10: Commit the resolver task atomically (2–5 minutes)**

```bash
git add core/data/src/main/kotlin/app/yinyuehe/core/data/DataModule.kt \
  core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/dao/TrackDao.kt \
  core/data/src/main/kotlin/app/yinyuehe/core/data/playback/AudioReadPermissionChecker.kt \
  core/data/src/main/kotlin/app/yinyuehe/core/data/playback/RoomPlaybackQueueResolver.kt \
  core/data/src/test/kotlin/app/yinyuehe/core/data/playback/AndroidAudioReadPermissionCheckerTest.kt \
  core/data/src/test/kotlin/app/yinyuehe/core/data/playback/RoomPlaybackQueueResolverTest.kt \
  core/data/src/test/kotlin/app/yinyuehe/core/data/playback/RoomPlaybackQueueResolverIntegrationTest.kt
git commit -m "feat: resolve persisted playback queues"
```

- [ ] **Step 11: Run the two-stage task review gate (2–5 minutes to dispatch)**

Dispatch a fresh specification-compliance reviewer with Task 4, approved design sections 4.2, 6, 13.1, and 13.2, the Task 4 commit, both Step 8 outputs, and the clean schema checks. Require explicit review of duplicate occurrences, 899 media-ID keys plus one excluded-volume bind, pure-Demo permission bypass, permission-denied non-deletion, `isAvailable = 1`, and exception propagation. After approval, dispatch a different fresh code-quality reviewer. Resolve every Critical or Important finding, rerun Steps 8–9, amend with `git commit --amend --no-edit`, and repeat both reviews before any `core:player` restore task consumes these contracts.

## Task 5: Restore planner, persistence gate, and asynchronous coordinator

**Files:**
- Create: `core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackRestorePlan.kt`
- Create: `core/player/src/main/kotlin/app/yinyuehe/core/player/service/RestorePersistenceGate.kt`
- Create: `core/player/src/main/kotlin/app/yinyuehe/core/player/service/RestorablePlayer.kt`
- Create: `core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackRestoreCoordinator.kt`
- Test: `core/player/src/test/kotlin/app/yinyuehe/core/player/service/PlaybackRestorePlanTest.kt`
- Test: `core/player/src/test/kotlin/app/yinyuehe/core/player/service/RestorePersistenceGateTest.kt`
- Test: `core/player/src/test/kotlin/app/yinyuehe/core/player/service/PlaybackRestoreCoordinatorTest.kt`

**Interfaces:**
- Consumes: `PlaybackSnapshot`, `PlaybackSnapshotReadResult`, `PlaybackSnapshotStore`, `PlaybackQueueResolution`, `PlaybackQueueItemResolution`, `PlaybackQueueBlockReason`, and `PlaybackQueueResolver` with the exact signatures introduced by Tasks 1–4.
- Produces:
  ```kotlin
  internal data class PlaybackRestorePlan(
    val tracks: List<Track>,
    val currentIndex: Int,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: PlaybackRepeatMode,
    val normalizedSnapshot: PlaybackSnapshot,
  )

  internal data class ControllerIdentity(val packageName: String, val uid: Int)

  internal data class PlayerQueueFingerprint(
    val occurrenceKeys: List<String>,
    val mediaIds: List<String>,
    val currentIndex: Int,
  )

  internal interface RestorablePlayer {
    val isQueueEmpty: Boolean
    fun apply(plan: PlaybackRestorePlan)
    fun queueFingerprint(): PlayerQueueFingerprint
  }

  internal class PlaybackRestoreCoordinator {
    fun start(): Job
    fun cancel()
  }
  ```
- Task 6 consumes `RestorePersistenceGate.canPersist` and `PlaybackRestoreCoordinator`'s gate callbacks. Tasks 7–9 produce the Media3 occurrence keys and the real `RestorablePlayer` implementation.

- [ ] **Step 1: Add failing planner tests for duplicate preservation, current-item repair, and position rules**

Create `PlaybackRestorePlanTest.kt`:

```kotlin
package app.yinyuehe.core.player.service

import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackQueueItemResolution
import app.yinyuehe.core.common.playback.PlaybackQueueResolution
import app.yinyuehe.core.common.playback.PlaybackRepeatMode
import app.yinyuehe.core.common.playback.PlaybackSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRestorePlanTest {
  @Test
  fun currentOccurrenceSurvives_preservesDuplicatesAndClampsKnownDuration() {
    val one = track("local:one", 1_000)
    val two = track("local:two", 400)
    val snapshot =
      PlaybackSnapshot(
        mediaIds = listOf(one.id, two.id, one.id),
        currentIndex = 1,
        positionMs = 900,
        shuffleEnabled = true,
        repeatMode = PlaybackRepeatMode.ALL,
      )
    val resolution =
      PlaybackQueueResolution(
        items =
          listOf(
            PlaybackQueueItemResolution.Resolved(0, one.id, one),
            PlaybackQueueItemResolution.Resolved(1, two.id, two),
            PlaybackQueueItemResolution.Resolved(2, one.id, one),
          )
      )

    val plan = buildPlaybackRestorePlan(snapshot, resolution)

    assertEquals(listOf(one.id, two.id, one.id), plan.tracks.map(Track::id))
    assertEquals(1, plan.currentIndex)
    assertEquals(400L, plan.positionMs)
    assertEquals(true, plan.shuffleEnabled)
    assertEquals(PlaybackRepeatMode.ALL, plan.repeatMode)
    assertEquals(plan.tracks.map(Track::id), plan.normalizedSnapshot.mediaIds)
  }

  @Test
  fun currentOccurrenceMissing_prefersNextSurvivorAndResetsPosition() {
    val previous = track("local:previous", 1_000)
    val missing = TrackId("local:missing")
    val next = track("local:next", null)
    val snapshot =
      PlaybackSnapshot(
        mediaIds = listOf(previous.id, missing, next.id),
        currentIndex = 1,
        positionMs = 700,
      )
    val resolution =
      PlaybackQueueResolution(
        items =
          listOf(
            PlaybackQueueItemResolution.Resolved(0, previous.id, previous),
            PlaybackQueueItemResolution.PermanentlyMissing(1, missing),
            PlaybackQueueItemResolution.Resolved(2, next.id, next),
          )
      )

    val plan = buildPlaybackRestorePlan(snapshot, resolution)

    assertEquals(listOf(previous.id, next.id), plan.tracks.map(Track::id))
    assertEquals(1, plan.currentIndex)
    assertEquals(0L, plan.positionMs)
  }

  @Test
  fun everyOccurrenceMissing_producesCanonicalEmptySnapshot() {
    val id = TrackId("local:missing")
    val plan =
      buildPlaybackRestorePlan(
        PlaybackSnapshot(mediaIds = listOf(id), currentIndex = 0, positionMs = 500),
        PlaybackQueueResolution(
          items = listOf(PlaybackQueueItemResolution.PermanentlyMissing(0, id))
        ),
      )

    assertEquals(emptyList<Track>(), plan.tracks)
    assertEquals(-1, plan.currentIndex)
    assertEquals(0L, plan.positionMs)
    assertEquals(PlaybackSnapshot.empty(), plan.normalizedSnapshot)
  }
}

private fun track(id: String, durationMs: Long?) =
  Track(
    id = TrackId(id),
    title = id,
    artist = null,
    album = null,
    durationMs = durationMs,
    artworkUri = null,
    sourceUri = "content://media/$id",
    isDemo = false,
  )
```

- [ ] **Step 2: Add failing gate tests for initial callbacks, user supersession, and permission-limited replacement**

Create `RestorePersistenceGateTest.kt`:

```kotlin
package app.yinyuehe.core.player.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestorePersistenceGateTest {
  private val app = ControllerIdentity("app.yinyuehe", 10_001)

  @Test
  fun initialEmptyTimelineAndDestroyCannotOpenTheGate() {
    val gate = RestorePersistenceGate(app)

    assertFalse(gate.onConfirmedTimeline(PlayerQueueFingerprint.EMPTY))
    assertEquals(RestoreGateStatus.RESTORE_PENDING, gate.status)
    assertFalse(gate.canPersist)
    assertEquals(0L, gate.mutationGeneration)
  }

  @Test
  fun confirmedUserQueueDuringSlowRestore_supersedesTheReadGeneration() {
    val gate = RestorePersistenceGate(app)
    val userQueue = fingerprint(listOf("occurrence-1"), listOf("demo:one"), 0)

    assertTrue(gate.onConfirmedTimeline(userQueue))

    assertEquals(RestoreGateStatus.SUPERSEDED, gate.status)
    assertEquals(1L, gate.mutationGeneration)
    assertTrue(gate.canPersist)
    assertFalse(gate.tryBeginRestoreApply(expectedGeneration = 0, playerIsEmpty = false))
  }

  @Test
  fun timelineCallbacksInsideRestoreApplyRemainPendingUntilFinishApplied() {
    val gate = RestorePersistenceGate(app)
    val restored = fingerprint(listOf("restored-occurrence"), listOf("demo:stored"), 0)

    assertTrue(gate.tryBeginRestoreApply(expectedGeneration = 0, playerIsEmpty = true))
    assertFalse(gate.onConfirmedTimeline(restored))
    assertEquals(0L, gate.mutationGeneration)
    assertEquals(RestoreGateStatus.RESTORE_PENDING, gate.status)
    assertTrue(gate.isApplyingRestore)
    assertFalse(gate.canPersist)

    assertTrue(gate.finishApplied(expectedGeneration = 0, appliedFingerprint = restored))
    assertEquals(RestoreGateStatus.APPLIED, gate.status)
    assertFalse(gate.isApplyingRestore)
    assertTrue(gate.canPersist)
  }

  @Test
  fun permissionFailure_onlyMatchingAppSetMediaItemsCanOpenTheGate() {
    val gate = RestorePersistenceGate(app)
    assertTrue(gate.tryBeginRestoreApply(0, playerIsEmpty = true))
    gate.finishFailed(
      expectedGeneration = 0,
      reason = RestoreFailureReason.PERMISSION_DENIED,
      appliedFingerprint = fingerprint(listOf("demo-occurrence"), listOf("demo:one"), 0),
    )
    assertTrue(gate.queuePersistenceLimited)

    gate.onConfirmedTimeline(
      fingerprint(
        listOf("demo-occurrence", "added-occurrence"),
        listOf("demo:one", "demo:two"),
        0,
      )
    )
    assertFalse(gate.canPersist)

    gate.recordSetMediaItems(app, listOf("demo:stale"), startIndex = 0)
    gate.recordSetMediaItems(
      caller = ControllerIdentity("external.controller", 20_002),
      expectedMediaIds = listOf("demo:stale"),
      startIndex = 0,
    )
    gate.onConfirmedTimeline(
      fingerprint(listOf("external-occurrence"), listOf("demo:stale"), 0)
    )
    assertFalse(gate.canPersist)

    gate.recordSetMediaItems(app, listOf("demo:wrong-start"), startIndex = 1)
    gate.onConfirmedTimeline(
      fingerprint(listOf("wrong-start-occurrence"), listOf("demo:wrong-start"), 0)
    )
    assertFalse(gate.canPersist)

    gate.recordSetMediaItems(app, listOf("demo:stale-generation"), startIndex = 0)
    gate.onConfirmedTimeline(
      fingerprint(listOf("intervening-occurrence"), listOf("demo:intervening"), 0)
    )
    gate.onConfirmedTimeline(
      fingerprint(listOf("late-occurrence"), listOf("demo:stale-generation"), 0)
    )
    assertFalse(gate.canPersist)

    gate.recordSetMediaItems(app, listOf("demo:replacement"), startIndex = 0)
    assertTrue(
      gate.onConfirmedTimeline(
        fingerprint(listOf("app-occurrence"), listOf("demo:replacement"), 0)
      )
    )
    assertEquals(RestoreGateStatus.SUPERSEDED, gate.status)
    assertTrue(gate.canPersist)
    assertFalse(gate.queuePersistenceLimited)
  }

  @Test
  fun incompatibleAndTransientFailurePreserveWritesUntilAConfirmedUserQueue() {
    val incompatible = RestorePersistenceGate(app)
    assertTrue(incompatible.finishIncompatible(expectedGeneration = 0))
    assertFalse(incompatible.canPersist)
    assertTrue(
      incompatible.onConfirmedTimeline(
        fingerprint(listOf("new-occurrence"), listOf("demo:new"), 0)
      )
    )
    assertEquals(RestoreGateStatus.SUPERSEDED, incompatible.status)

    val transient = RestorePersistenceGate(app)
    assertTrue(transient.finishFailed(0, RestoreFailureReason.TRANSIENT))
    assertFalse(transient.canPersist)
    assertTrue(
      transient.onConfirmedTimeline(
        fingerprint(listOf("retry-occurrence"), listOf("demo:retry"), 0)
      )
    )
    assertEquals(RestoreGateStatus.SUPERSEDED, transient.status)
  }
}

private fun fingerprint(keys: List<String>, ids: List<String>, index: Int) =
  PlayerQueueFingerprint(keys, ids, index)
```

- [ ] **Step 3: Run the planner and gate tests to prove RED**

Run:

```bash
./gradlew :core:player:testDebugUnitTest \
  --tests 'app.yinyuehe.core.player.service.PlaybackRestorePlanTest' \
  --tests 'app.yinyuehe.core.player.service.RestorePersistenceGateTest'
```

Expected: FAIL during Kotlin test compilation because `PlaybackRestorePlan`, `buildPlaybackRestorePlan`, and `RestorePersistenceGate` do not exist.

- [ ] **Step 4: Implement the deterministic restore planner**

Create `PlaybackRestorePlan.kt`:

```kotlin
package app.yinyuehe.core.player.service

import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.playback.PlaybackQueueItemResolution
import app.yinyuehe.core.common.playback.PlaybackQueueResolution
import app.yinyuehe.core.common.playback.PlaybackRepeatMode
import app.yinyuehe.core.common.playback.PlaybackSnapshot

internal data class PlaybackRestorePlan(
  val tracks: List<Track>,
  val currentIndex: Int,
  val positionMs: Long,
  val shuffleEnabled: Boolean,
  val repeatMode: PlaybackRepeatMode,
  val normalizedSnapshot: PlaybackSnapshot,
)

internal fun buildPlaybackRestorePlan(
  snapshot: PlaybackSnapshot,
  resolution: PlaybackQueueResolution,
): PlaybackRestorePlan {
  val resolved =
    resolution.items
      .filterIsInstance<PlaybackQueueItemResolution.Resolved>()
      .sortedBy(PlaybackQueueItemResolution.Resolved::originalIndex)
  if (resolved.isEmpty()) {
    val empty = PlaybackSnapshot.empty()
    return PlaybackRestorePlan(
      tracks = emptyList(),
      currentIndex = -1,
      positionMs = 0,
      shuffleEnabled = false,
      repeatMode = PlaybackRepeatMode.OFF,
      normalizedSnapshot = empty,
    )
  }

  val originalIndex = snapshot.currentIndex.coerceIn(snapshot.mediaIds.indices)
  val survivingCurrentIndex = resolved.indexOfFirst { it.originalIndex == originalIndex }
  val selectedIndex =
    if (survivingCurrentIndex >= 0) {
      survivingCurrentIndex
    } else {
      val successor = resolved.indexOfFirst { it.originalIndex > originalIndex }
      if (successor >= 0) successor else resolved.indexOfLast { it.originalIndex < originalIndex }
    }.coerceIn(resolved.indices)
  val selected = resolved[selectedIndex]
  val positionMs =
    if (selected.originalIndex != originalIndex) {
      0
    } else {
      selected.track.durationMs
        ?.takeIf { it > 0 }
        ?.let { duration -> snapshot.positionMs.coerceIn(0, duration) }
        ?: snapshot.positionMs.coerceAtLeast(0)
    }
  val mediaIds = resolved.map(PlaybackQueueItemResolution.Resolved::trackId)
  val normalized =
    PlaybackSnapshot(
      mediaIds = mediaIds,
      currentIndex = selectedIndex,
      positionMs = positionMs,
      shuffleEnabled = snapshot.shuffleEnabled,
      repeatMode = snapshot.repeatMode,
    )
  return PlaybackRestorePlan(
    tracks = resolved.map(PlaybackQueueItemResolution.Resolved::track),
    currentIndex = selectedIndex,
    positionMs = positionMs,
    shuffleEnabled = snapshot.shuffleEnabled,
    repeatMode = snapshot.repeatMode,
    normalizedSnapshot = normalized,
  )
}
```

- [ ] **Step 5: Implement the restore persistence gate**

Create `RestorePersistenceGate.kt`:

```kotlin
package app.yinyuehe.core.player.service

internal enum class RestoreGateStatus {
  RESTORE_PENDING,
  APPLIED,
  SUPERSEDED,
  INCOMPATIBLE,
  FAILED,
}

internal enum class RestoreFailureReason { PERMISSION_DENIED, TRANSIENT }

internal data class ControllerIdentity(val packageName: String, val uid: Int)

internal data class PlayerQueueFingerprint(
  val occurrenceKeys: List<String>,
  val mediaIds: List<String>,
  val currentIndex: Int,
) {
  companion object {
    val EMPTY = PlayerQueueFingerprint(emptyList(), emptyList(), -1)
  }
}

internal data class PendingQueueReplacement(
  val caller: ControllerIdentity,
  val expectedMediaIds: List<String>,
  val startIndex: Int,
  val generation: Long,
)

internal class RestorePersistenceGate(
  private val applicationController: ControllerIdentity,
) {
  var status: RestoreGateStatus = RestoreGateStatus.RESTORE_PENDING
    private set
  var mutationGeneration: Long = 0
    private set
  var failureReason: RestoreFailureReason? = null
    private set
  var isApplyingRestore: Boolean = false
    private set

  private var lastFingerprint = PlayerQueueFingerprint.EMPTY
  private var pendingReplacement: PendingQueueReplacement? = null

  val canPersist: Boolean
    get() = status == RestoreGateStatus.APPLIED || status == RestoreGateStatus.SUPERSEDED

  val queuePersistenceLimited: Boolean
    get() = status == RestoreGateStatus.FAILED && failureReason == RestoreFailureReason.PERMISSION_DENIED

  fun recordSetMediaItems(
    caller: ControllerIdentity,
    expectedMediaIds: List<String>,
    startIndex: Int,
  ) {
    // Every later setMediaItems invalidates an older marker, including external calls.
    pendingReplacement = null
    if (caller != applicationController) return
    pendingReplacement =
      PendingQueueReplacement(caller, expectedMediaIds.toList(), startIndex, mutationGeneration)
  }

  fun onConfirmedTimeline(fingerprint: PlayerQueueFingerprint): Boolean {
    if (fingerprint.occurrenceKeys == lastFingerprint.occurrenceKeys) {
      lastFingerprint = fingerprint
      return false
    }
    if (isApplyingRestore) {
      lastFingerprint = fingerprint
      return false
    }

    val generationBeforeChange = mutationGeneration
    val replacement = pendingReplacement
    val matchingReplacement =
      replacement != null &&
        replacement.generation == generationBeforeChange &&
        replacement.expectedMediaIds == fingerprint.mediaIds &&
        replacement.startIndex == fingerprint.currentIndex
    mutationGeneration += 1
    lastFingerprint = fingerprint
    pendingReplacement = null

    val opened =
      when (status) {
        RestoreGateStatus.RESTORE_PENDING,
        RestoreGateStatus.INCOMPATIBLE -> true
        RestoreGateStatus.FAILED ->
          failureReason != RestoreFailureReason.PERMISSION_DENIED || matchingReplacement
        RestoreGateStatus.APPLIED,
        RestoreGateStatus.SUPERSEDED -> false
      }
    if (opened) {
      status = RestoreGateStatus.SUPERSEDED
      failureReason = null
    }
    return opened
  }

  fun tryBeginRestoreApply(expectedGeneration: Long, playerIsEmpty: Boolean): Boolean {
    if (
      status != RestoreGateStatus.RESTORE_PENDING ||
        mutationGeneration != expectedGeneration ||
        !playerIsEmpty ||
        isApplyingRestore
    ) {
      return false
    }
    isApplyingRestore = true
    return true
  }

  fun finishApplied(
    expectedGeneration: Long,
    appliedFingerprint: PlayerQueueFingerprint,
  ): Boolean {
    if (!isCurrentApply(expectedGeneration)) return false
    isApplyingRestore = false
    status = RestoreGateStatus.APPLIED
    failureReason = null
    lastFingerprint = appliedFingerprint
    return true
  }

  fun finishIncompatible(expectedGeneration: Long): Boolean {
    if (!isPendingGeneration(expectedGeneration)) return false
    status = RestoreGateStatus.INCOMPATIBLE
    return true
  }

  fun finishFailed(
    expectedGeneration: Long,
    reason: RestoreFailureReason,
    appliedFingerprint: PlayerQueueFingerprint = lastFingerprint,
  ): Boolean {
    if (!isPendingGeneration(expectedGeneration)) return false
    isApplyingRestore = false
    status = RestoreGateStatus.FAILED
    failureReason = reason
    lastFingerprint = appliedFingerprint
    return true
  }

  fun abortApply(expectedGeneration: Long): Boolean =
    finishFailed(expectedGeneration, RestoreFailureReason.TRANSIENT)

  private fun isPendingGeneration(expectedGeneration: Long): Boolean =
    status == RestoreGateStatus.RESTORE_PENDING && mutationGeneration == expectedGeneration

  private fun isCurrentApply(expectedGeneration: Long): Boolean =
    isApplyingRestore && isPendingGeneration(expectedGeneration)
}
```

- [ ] **Step 6: Add the narrow player boundary and asynchronous restore coordinator**

Create `RestorablePlayer.kt`:

```kotlin
package app.yinyuehe.core.player.service

internal interface RestorablePlayer {
  val isQueueEmpty: Boolean
  fun apply(plan: PlaybackRestorePlan)
  fun queueFingerprint(): PlayerQueueFingerprint
}
```

Create `PlaybackRestoreCoordinator.kt`:

```kotlin
package app.yinyuehe.core.player.service

import app.yinyuehe.core.common.playback.PlaybackQueueBlockReason
import app.yinyuehe.core.common.playback.PlaybackQueueResolver
import app.yinyuehe.core.common.playback.PlaybackSnapshot
import app.yinyuehe.core.common.playback.PlaybackSnapshotReadResult
import app.yinyuehe.core.common.playback.PlaybackSnapshotStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PlaybackRestoreCoordinator(
  private val snapshotStore: PlaybackSnapshotStore,
  private val queueResolver: PlaybackQueueResolver,
  private val gate: RestorePersistenceGate,
  private val player: RestorablePlayer,
  private val scope: CoroutineScope,
  private val ioDispatcher: CoroutineDispatcher,
  private val onNormalizedSnapshot: (PlaybackSnapshot) -> Unit,
  private val onGateChanged: () -> Unit,
  private val onFailure: (Exception) -> Unit,
) {
  private var restoreJob: Job? = null

  fun start(): Job {
    restoreJob?.let { return it }
    val expectedGeneration = gate.mutationGeneration
    return scope
      .launch {
        try {
          when (val read = withContext(ioDispatcher) { snapshotStore.read() }) {
            is PlaybackSnapshotReadResult.IncompatibleVersion -> {
              if (gate.finishIncompatible(expectedGeneration)) onGateChanged()
            }
            is PlaybackSnapshotReadResult.Usable -> restoreUsable(read.snapshot, expectedGeneration)
          }
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (error: Exception) {
          if (gate.finishFailed(expectedGeneration, RestoreFailureReason.TRANSIENT)) {
            onGateChanged()
          }
          onFailure(error)
        }
      }
      .also { restoreJob = it }
  }

  fun cancel() {
    restoreJob?.cancel()
    restoreJob = null
  }

  private suspend fun restoreUsable(snapshot: PlaybackSnapshot, expectedGeneration: Long) {
    val resolution = withContext(ioDispatcher) { queueResolver.resolve(snapshot.mediaIds) }
    val plan = buildPlaybackRestorePlan(snapshot, resolution)
    if (!gate.tryBeginRestoreApply(expectedGeneration, player.isQueueEmpty)) return
    try {
      player.apply(plan)
      val fingerprint = player.queueFingerprint()
      if (resolution.temporaryBlockReason == PlaybackQueueBlockReason.PERMISSION_DENIED) {
        gate.finishFailed(
          expectedGeneration,
          RestoreFailureReason.PERMISSION_DENIED,
          fingerprint,
        )
      } else if (gate.finishApplied(expectedGeneration, fingerprint)) {
        onNormalizedSnapshot(plan.normalizedSnapshot)
      }
      onGateChanged()
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (error: Exception) {
      gate.abortApply(expectedGeneration)
      onGateChanged()
      onFailure(error)
    }
  }
}
```

- [ ] **Step 7: Add coordinator race tests**

Create `PlaybackRestoreCoordinatorTest.kt`:

```kotlin
package app.yinyuehe.core.player.service

import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackQueueBlockReason
import app.yinyuehe.core.common.playback.PlaybackQueueItemResolution
import app.yinyuehe.core.common.playback.PlaybackQueueResolution
import app.yinyuehe.core.common.playback.PlaybackQueueResolver
import app.yinyuehe.core.common.playback.PlaybackSnapshot
import app.yinyuehe.core.common.playback.PlaybackSnapshotReadResult
import app.yinyuehe.core.common.playback.PlaybackSnapshotStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRestoreCoordinatorTest {
  @Test
  fun slowReadCannotOverwriteConfirmedUserQueue() = runTest {
    val readGate = CompletableDeferred<PlaybackSnapshotReadResult>()
    val store = SuspendingSnapshotStore(readGate)
    val gate = RestorePersistenceGate(ControllerIdentity("app.yinyuehe", 1))
    val player = RecordingRestorablePlayer()
    val coordinator =
      coordinator(
        store,
        FixedResolver(),
        gate,
        player,
        backgroundScope,
        StandardTestDispatcher(testScheduler),
      )
    coordinator.start()
    runCurrent()

    gate.onConfirmedTimeline(
      PlayerQueueFingerprint(listOf("user"), listOf("demo:user"), currentIndex = 0)
    )
    readGate.complete(
      PlaybackSnapshotReadResult.Usable(
        PlaybackSnapshot(mediaIds = listOf(TrackId("demo:stored")), currentIndex = 0)
      )
    )
    runCurrent()

    assertTrue(player.plans.isEmpty())
    assertEquals(RestoreGateStatus.SUPERSEDED, gate.status)
  }

  @Test
  fun permissionLimitedResolutionAppliesSafeSubsetWithoutOpeningWrites() = runTest {
    val stored = TrackId("local:hidden")
    val demo = demoTrack("demo:safe")
    val store = ImmediateSnapshotStore(PlaybackSnapshot(mediaIds = listOf(demo.id, stored), currentIndex = 0))
    val resolution =
      PlaybackQueueResolution(
        items =
          listOf(
            PlaybackQueueItemResolution.Resolved(0, demo.id, demo),
            PlaybackQueueItemResolution.TemporarilyBlocked(
              1,
              stored,
              PlaybackQueueBlockReason.PERMISSION_DENIED,
            ),
          ),
        temporaryBlockReason = PlaybackQueueBlockReason.PERMISSION_DENIED,
      )
    val gate = RestorePersistenceGate(ControllerIdentity("app.yinyuehe", 1))
    val player = RecordingRestorablePlayer()
    val normalized = mutableListOf<PlaybackSnapshot>()
    val coordinator =
      PlaybackRestoreCoordinator(
        store,
        FixedResolver(resolution),
        gate,
        player,
        backgroundScope,
        StandardTestDispatcher(testScheduler),
        normalized::add,
        {},
        { throw AssertionError(it) },
      )

    coordinator.start()
    testScheduler.advanceUntilIdle()

    assertEquals(listOf(demo.id), player.plans.single().tracks.map(Track::id))
    assertTrue(gate.queuePersistenceLimited)
    assertFalse(gate.canPersist)
    assertTrue(normalized.isEmpty())
  }

  @Test
  fun storeFailureEndsTransientWithoutApplyOrNormalization() = runTest {
    assertTransientFailureLeavesPlayerUntouched(ThrowingSnapshotStore(), FixedResolver())
  }

  @Test
  fun resolverFailureEndsTransientWithoutApplyOrNormalization() = runTest {
    val snapshot =
      PlaybackSnapshot(mediaIds = listOf(TrackId("demo:stored")), currentIndex = 0)
    assertTransientFailureLeavesPlayerUntouched(
      ImmediateSnapshotStore(snapshot),
      ThrowingResolver(),
    )
  }

  private suspend fun kotlinx.coroutines.test.TestScope.assertTransientFailureLeavesPlayerUntouched(
    store: PlaybackSnapshotStore,
    resolver: PlaybackQueueResolver,
  ) {
    val gate = RestorePersistenceGate(ControllerIdentity("app.yinyuehe", 1))
    val player = RecordingRestorablePlayer()
    val normalized = mutableListOf<PlaybackSnapshot>()
    val failures = mutableListOf<Exception>()
    PlaybackRestoreCoordinator(
        store,
        resolver,
        gate,
        player,
        backgroundScope,
        StandardTestDispatcher(testScheduler),
        normalized::add,
        {},
        failures::add,
      )
      .start()
    testScheduler.advanceUntilIdle()

    assertEquals(RestoreGateStatus.FAILED, gate.status)
    assertEquals(RestoreFailureReason.TRANSIENT, gate.failureReason)
    assertFalse(gate.canPersist)
    assertTrue(player.plans.isEmpty())
    assertTrue(normalized.isEmpty())
    assertEquals(1, failures.size)
  }

  private fun coordinator(
    store: PlaybackSnapshotStore,
    resolver: PlaybackQueueResolver,
    gate: RestorePersistenceGate,
    player: RestorablePlayer,
    scope: kotlinx.coroutines.CoroutineScope,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher,
  ) =
    PlaybackRestoreCoordinator(
      store,
      resolver,
      gate,
      player,
      scope,
      dispatcher,
      {},
      {},
      { throw AssertionError(it) },
    )
}

private class SuspendingSnapshotStore(
  private val result: CompletableDeferred<PlaybackSnapshotReadResult>
) : PlaybackSnapshotStore {
  override suspend fun read(): PlaybackSnapshotReadResult = result.await()
  override suspend fun write(snapshot: PlaybackSnapshot) = Unit
}

private class ImmediateSnapshotStore(private val snapshot: PlaybackSnapshot) : PlaybackSnapshotStore {
  override suspend fun read() = PlaybackSnapshotReadResult.Usable(snapshot)
  override suspend fun write(snapshot: PlaybackSnapshot) = Unit
}

private class FixedResolver(
  private val result: PlaybackQueueResolution = PlaybackQueueResolution(emptyList())
) : PlaybackQueueResolver {
  override suspend fun resolve(mediaIds: List<TrackId>) = result
}

private class ThrowingSnapshotStore : PlaybackSnapshotStore {
  override suspend fun read(): PlaybackSnapshotReadResult = error("synthetic read failure")
  override suspend fun write(snapshot: PlaybackSnapshot) = Unit
}

private class ThrowingResolver : PlaybackQueueResolver {
  override suspend fun resolve(mediaIds: List<TrackId>): PlaybackQueueResolution =
    error("synthetic resolver failure")
}

private class RecordingRestorablePlayer : RestorablePlayer {
  val plans = mutableListOf<PlaybackRestorePlan>()
  override val isQueueEmpty = true
  override fun apply(plan: PlaybackRestorePlan) {
    plans += plan
  }
  override fun queueFingerprint(): PlayerQueueFingerprint =
    plans.lastOrNull()?.let { plan ->
      PlayerQueueFingerprint(
        occurrenceKeys = plan.tracks.indices.map { "restored-$it" },
        mediaIds = plan.tracks.map { it.id.value },
        currentIndex = plan.currentIndex,
      )
    } ?: PlayerQueueFingerprint.EMPTY
}

private fun demoTrack(id: String) =
  Track(
    id = TrackId(id),
    title = id,
    artist = null,
    album = null,
    durationMs = 3_000,
    artworkUri = null,
    sourceUri = "android.resource://app/$id",
    isDemo = true,
  )
```

- [ ] **Step 8: Run the focused and module tests**

Run:

```bash
./gradlew :core:player:testDebugUnitTest \
  --tests 'app.yinyuehe.core.player.service.PlaybackRestorePlanTest' \
  --tests 'app.yinyuehe.core.player.service.RestorePersistenceGateTest' \
  --tests 'app.yinyuehe.core.player.service.PlaybackRestoreCoordinatorTest'
./gradlew :core:player:testDebugUnitTest
```

Expected: PASS; the focused report contains no failure, and the complete `:core:player` JVM suite remains green.

- [ ] **Step 9: Commit the restore kernel atomically**

```bash
git add \
  core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackRestorePlan.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/service/RestorePersistenceGate.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/service/RestorablePlayer.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackRestoreCoordinator.kt \
  core/player/src/test/kotlin/app/yinyuehe/core/player/service/PlaybackRestorePlanTest.kt \
  core/player/src/test/kotlin/app/yinyuehe/core/player/service/RestorePersistenceGateTest.kt \
  core/player/src/test/kotlin/app/yinyuehe/core/player/service/PlaybackRestoreCoordinatorTest.kt
git commit -m "feat: add guarded playback restore coordinator"
```

- [ ] **Step 10: Run the mandatory two-stage review before Task 6**

Dispatch a fresh specification-compliance reviewer, resolve every Critical/Important finding, then dispatch a fresh code-quality reviewer and resolve every Critical/Important finding. Re-run `./gradlew :core:player:testDebugUnitTest` after review fixes and amend or add a focused follow-up commit before proceeding.

---

## Task 6: Serialized snapshot writer and persistence scheduling

**Files:**
- Create: `core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackSnapshotWriter.kt`
- Create: `core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackPersistenceCoordinator.kt`
- Test: `core/player/src/test/kotlin/app/yinyuehe/core/player/service/PlaybackSnapshotWriterTest.kt`
- Test: `core/player/src/test/kotlin/app/yinyuehe/core/player/service/PlaybackPersistenceCoordinatorTest.kt`

**Interfaces:**
- Consumes: `PlaybackSnapshotStore.write(snapshot)`, `RestorePersistenceGate.canPersist`, and a main-looper-confined `capture: () -> PlaybackSnapshot` supplied by Task 8.
- Produces:
  ```kotlin
  internal enum class SnapshotWriteUrgency { COALESCED, IMMEDIATE }

  internal class PlaybackSnapshotWriter {
    fun submit(snapshot: PlaybackSnapshot, urgency: SnapshotWriteUrgency)
    fun close(finalSnapshot: PlaybackSnapshot?): Job
  }

  internal class PlaybackPersistenceCoordinator {
    fun onCoalescedChange()
    fun onImmediateChange()
    fun onIsPlayingChanged(isPlaying: Boolean)
    fun onGateOpened()
    fun close(): Job
  }
  ```
- Task 8 routes Player callbacks into these semantic methods and invokes `close()` before releasing ExoPlayer.

- [ ] **Step 1: Add failing writer timing, serialization, retry, and bounded-close tests**

Create `PlaybackSnapshotWriterTest.kt`:

```kotlin
package app.yinyuehe.core.player.service

import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackSnapshot
import app.yinyuehe.core.common.playback.PlaybackSnapshotReadResult
import app.yinyuehe.core.common.playback.PlaybackSnapshotStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSnapshotWriterTest {
  @Test
  fun coalescedWritesKeepOnlyLatestAndNeverDelayPast250MsFromTheFirstValue() = runTest {
    val store = RecordingStore()
    val writer = writer(store, StandardTestDispatcher(testScheduler))

    writer.submit(snapshot("one"), SnapshotWriteUrgency.COALESCED)
    advanceTimeBy(249)
    runCurrent()
    assertTrue(store.writes.isEmpty())
    writer.submit(snapshot("two"), SnapshotWriteUrgency.COALESCED)
    runCurrent()
    assertTrue(store.writes.isEmpty())
    advanceTimeBy(1)
    runCurrent()

    assertEquals(listOf(snapshot("two")), store.writes)
    writer.close(null).join()
  }

  @Test
  fun immediateValueDoesNotCancelInFlightWriteAndCommitsAfterItInOrder() = runTest {
    val firstWriteGate = CompletableDeferred<Unit>()
    val store = RecordingStore(firstWriteGate)
    val writer = writer(store, StandardTestDispatcher(testScheduler))
    writer.submit(snapshot("one"), SnapshotWriteUrgency.IMMEDIATE)
    runCurrent()
    writer.submit(snapshot("two"), SnapshotWriteUrgency.IMMEDIATE)
    runCurrent()

    assertEquals(listOf(snapshot("one")), store.started)
    firstWriteGate.complete(Unit)
    testScheduler.advanceUntilIdle()

    assertEquals(listOf(snapshot("one"), snapshot("two")), store.writes)
    writer.close(null).join()
  }

  @Test
  fun failedWriteDoesNotDisableTheNextWrite() = runTest {
    val store = RecordingStore().apply { failuresRemaining = 1 }
    val failures = mutableListOf<Exception>()
    val writer = writer(store, StandardTestDispatcher(testScheduler), failures::add)

    writer.submit(snapshot("one"), SnapshotWriteUrgency.IMMEDIATE)
    runCurrent()
    writer.submit(snapshot("two"), SnapshotWriteUrgency.IMMEDIATE)
    testScheduler.advanceUntilIdle()

    assertEquals(1, failures.size)
    assertEquals(listOf(snapshot("two")), store.writes)
    writer.close(null).join()
  }

  @Test
  fun closeReturnsImmediatelyAndCancelsBlockedDrainAt1000Ms() = runTest {
    val never = CompletableDeferred<Unit>()
    val store = RecordingStore(never)
    val writer = writer(store, StandardTestDispatcher(testScheduler))
    writer.submit(snapshot("final"), SnapshotWriteUrgency.IMMEDIATE)
    runCurrent()

    val closeJob = writer.close(snapshot("new-final"))
    assertFalse(closeJob.isCompleted)
    advanceTimeBy(999)
    runCurrent()
    assertFalse(closeJob.isCompleted)
    advanceTimeBy(1)
    runCurrent()

    assertTrue(closeJob.isCompleted)
  }

  private fun writer(
    store: PlaybackSnapshotStore,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    onFailure: (Exception) -> Unit = {},
  ) =
    PlaybackSnapshotWriter(
      snapshotStore = store,
      dispatcher = dispatcher,
      coalesceWindowMs = 250,
      closeDrainTimeoutMs = 1_000,
      onFailure = onFailure,
    )
}

private class RecordingStore(
  private val firstWriteGate: CompletableDeferred<Unit>? = null,
) : PlaybackSnapshotStore {
  val started = mutableListOf<PlaybackSnapshot>()
  val writes = mutableListOf<PlaybackSnapshot>()
  var failuresRemaining = 0

  override suspend fun read(): PlaybackSnapshotReadResult =
    PlaybackSnapshotReadResult.Usable(PlaybackSnapshot.empty())

  override suspend fun write(snapshot: PlaybackSnapshot) {
    started += snapshot
    if (started.size == 1) firstWriteGate?.await()
    if (failuresRemaining > 0) {
      failuresRemaining -= 1
      throw IllegalStateException("synthetic write failure")
    }
    writes += snapshot
  }
}

private fun snapshot(id: String) =
  PlaybackSnapshot(mediaIds = listOf(TrackId("demo:$id")), currentIndex = 0)
```

- [ ] **Step 2: Add failing 5-second sampling and gate-suppression tests**

Create `PlaybackPersistenceCoordinatorTest.kt`:

```kotlin
package app.yinyuehe.core.player.service

import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackSnapshot
import app.yinyuehe.core.common.playback.PlaybackSnapshotReadResult
import app.yinyuehe.core.common.playback.PlaybackSnapshotStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackPersistenceCoordinatorTest {
  @Test
  fun playingPositionIsSampledEvery5000MsAndPauseIsImmediate() = runTest {
    val gate = appliedGate()
    val store = SimpleRecordingStore()
    var position = 0L
    val coordinator =
      coordinator(
        gate,
        store,
        backgroundScope,
        StandardTestDispatcher(testScheduler),
      ) { snapshotAt(position) }

    coordinator.onIsPlayingChanged(true)
    position = 4_999
    advanceTimeBy(4_999)
    runCurrent()
    assertTrue(store.writes.isEmpty())
    position = 5_000
    advanceTimeBy(1)
    runCurrent()
    assertEquals(listOf(5_000L), store.writes.map(PlaybackSnapshot::positionMs))
    position = 5_100
    coordinator.onIsPlayingChanged(false)
    runCurrent()
    assertEquals(listOf(5_000L, 5_100L), store.writes.map(PlaybackSnapshot::positionMs))
  }

  @Test
  fun pendingGateSuppressesCallbacksCaptureAndFinalSnapshot() = runTest {
    val gate = RestorePersistenceGate(ControllerIdentity("app.yinyuehe", 1))
    val store = SimpleRecordingStore()
    var captureCount = 0
    val coordinator =
      coordinator(
        gate,
        store,
        backgroundScope,
        StandardTestDispatcher(testScheduler),
      ) {
        captureCount += 1
        snapshotAt(123)
      }

    coordinator.onCoalescedChange()
    coordinator.onImmediateChange()
    coordinator.onIsPlayingChanged(true)
    advanceTimeBy(5_000)
    coordinator.close()
    testScheduler.advanceUntilIdle()

    assertTrue(store.writes.isEmpty())
    assertEquals(0, captureCount)
  }

  @Test
  fun incompatibleAndBothFailureGatesSuppressCallbacksCaptureAndFinalSnapshot() = runTest {
    val closedGates =
      listOf(
        incompatibleGate(),
        failedGate(RestoreFailureReason.TRANSIENT),
        failedGate(RestoreFailureReason.PERMISSION_DENIED),
      )

    closedGates.forEach { gate ->
      val store = SimpleRecordingStore()
      var captureCount = 0
      val coordinator =
        coordinator(
          gate,
          store,
          backgroundScope,
          StandardTestDispatcher(testScheduler),
        ) {
          captureCount += 1
          snapshotAt(456)
        }
      coordinator.onCoalescedChange()
      coordinator.onImmediateChange()
      coordinator.onIsPlayingChanged(true)
      advanceTimeBy(5_000)
      coordinator.close()
      runCurrent()

      assertTrue(store.writes.isEmpty())
      assertEquals(0, captureCount)
    }
  }

  private fun coordinator(
    gate: RestorePersistenceGate,
    store: PlaybackSnapshotStore,
    scope: kotlinx.coroutines.CoroutineScope,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    capture: () -> PlaybackSnapshot,
  ): PlaybackPersistenceCoordinator {
    val writer =
      PlaybackSnapshotWriter(
        store,
        dispatcher,
        coalesceWindowMs = 250,
        closeDrainTimeoutMs = 1_000,
      )
    return PlaybackPersistenceCoordinator(
      gate,
      writer,
      scope,
      capture,
      positionSampleIntervalMs = 5_000,
    )
  }
}

private class SimpleRecordingStore : PlaybackSnapshotStore {
  val writes = mutableListOf<PlaybackSnapshot>()
  override suspend fun read() = PlaybackSnapshotReadResult.Usable(PlaybackSnapshot.empty())
  override suspend fun write(snapshot: PlaybackSnapshot) {
    writes += snapshot
  }
}

private fun snapshotAt(positionMs: Long) =
  PlaybackSnapshot(
    mediaIds = listOf(TrackId("demo:one")),
    currentIndex = 0,
    positionMs = positionMs,
  )

private fun appliedGate(): RestorePersistenceGate =
  RestorePersistenceGate(ControllerIdentity("app.yinyuehe", 1)).apply {
    check(tryBeginRestoreApply(0, playerIsEmpty = true))
    check(finishApplied(0, PlayerQueueFingerprint(listOf("one"), listOf("demo:one"), 0)))
  }

private fun incompatibleGate(): RestorePersistenceGate =
  RestorePersistenceGate(ControllerIdentity("app.yinyuehe", 1)).apply {
    check(finishIncompatible(0))
  }

private fun failedGate(reason: RestoreFailureReason): RestorePersistenceGate =
  RestorePersistenceGate(ControllerIdentity("app.yinyuehe", 1)).apply {
    check(finishFailed(0, reason))
  }
```

- [ ] **Step 3: Run the new tests to prove RED**

```bash
./gradlew :core:player:testDebugUnitTest \
  --tests 'app.yinyuehe.core.player.service.PlaybackSnapshotWriterTest' \
  --tests 'app.yinyuehe.core.player.service.PlaybackPersistenceCoordinatorTest'
```

Expected: FAIL during Kotlin test compilation because `PlaybackSnapshotWriter`, `SnapshotWriteUrgency`, and `PlaybackPersistenceCoordinator` do not exist.

- [ ] **Step 4: Implement the single-writer actor with interruptible coalescing and bounded asynchronous close**

Create `PlaybackSnapshotWriter.kt`:

```kotlin
package app.yinyuehe.core.player.service

import app.yinyuehe.core.common.playback.PlaybackSnapshot
import app.yinyuehe.core.common.playback.PlaybackSnapshotStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

internal enum class SnapshotWriteUrgency { COALESCED, IMMEDIATE }

internal class PlaybackSnapshotWriter(
  private val snapshotStore: PlaybackSnapshotStore,
  dispatcher: CoroutineDispatcher,
  private val coalesceWindowMs: Long = 250,
  private val closeDrainTimeoutMs: Long = 1_000,
  private val onFailure: (Exception) -> Unit = {},
) {
  private data class Pending(
    val snapshot: PlaybackSnapshot,
    val urgency: SnapshotWriteUrgency,
  )

  private val writerJob = SupervisorJob()
  private val writerScope = CoroutineScope(writerJob + dispatcher)
  private val signal = Channel<Unit>(Channel.CONFLATED)
  private val lock = Any()
  private var pending: Pending? = null
  private var closing = false
  private var closeJob: Job? = null
  private val actor = writerScope.launch { actorLoop() }

  fun submit(snapshot: PlaybackSnapshot, urgency: SnapshotWriteUrgency) {
    synchronized(lock) {
      if (closing) return
      pending = merge(pending, Pending(snapshot, urgency))
    }
    signal.trySend(Unit)
  }

  fun close(finalSnapshot: PlaybackSnapshot?): Job =
    synchronized(lock) {
      closeJob
        ?: run {
          closing = true
          if (finalSnapshot != null) {
            pending = merge(pending, Pending(finalSnapshot, SnapshotWriteUrgency.IMMEDIATE))
          }
          signal.trySend(Unit)
          writerScope
            .launch {
              try {
                withTimeoutOrNull(closeDrainTimeoutMs) { actor.join() }
              } finally {
                writerJob.cancel()
              }
            }
            .also { closeJob = it }
        }
    }

  private suspend fun actorLoop() {
    while (true) {
      signal.receive()
      var next = takePending()
      if (next == null) {
        if (isClosingAndEmpty()) return
        continue
      }
      next = awaitCoalescing(next)
      writeSafely(next.snapshot)
      if (isClosingAndEmpty()) return
    }
  }

  private suspend fun awaitCoalescing(initial: Pending): Pending = coroutineScope {
    var latest = initial
    if (latest.urgency == SnapshotWriteUrgency.IMMEDIATE) return@coroutineScope latest
    val window = async { delay(coalesceWindowMs) }
    try {
      while (latest.urgency == SnapshotWriteUrgency.COALESCED) {
        val replacement =
          select<Pending?> {
            window.onAwait { null }
            signal.onReceive { takePending() }
          }
        if (replacement == null) break
        latest = merge(latest, replacement)
      }
      latest
    } finally {
      window.cancel()
    }
  }

  private fun takePending(): Pending? = synchronized(lock) { pending.also { pending = null } }

  private fun isClosingAndEmpty(): Boolean = synchronized(lock) { closing && pending == null }

  private suspend fun writeSafely(snapshot: PlaybackSnapshot) {
    try {
      snapshotStore.write(snapshot)
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (error: Exception) {
      onFailure(error)
    }
  }

  private fun merge(current: Pending?, newer: Pending): Pending =
    Pending(
      snapshot = newer.snapshot,
      urgency =
        if (
          current?.urgency == SnapshotWriteUrgency.IMMEDIATE ||
            newer.urgency == SnapshotWriteUrgency.IMMEDIATE
        ) {
          SnapshotWriteUrgency.IMMEDIATE
        } else {
          SnapshotWriteUrgency.COALESCED
        },
    )
}
```

- [ ] **Step 5: Implement gate-aware semantic persistence scheduling**

Create `PlaybackPersistenceCoordinator.kt`:

```kotlin
package app.yinyuehe.core.player.service

import app.yinyuehe.core.common.playback.PlaybackSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class PlaybackPersistenceCoordinator(
  private val gate: RestorePersistenceGate,
  private val writer: PlaybackSnapshotWriter,
  private val scope: CoroutineScope,
  private val capture: () -> PlaybackSnapshot,
  private val positionSampleIntervalMs: Long = 5_000,
) {
  private var isPlaying = false
  private var tickerJob: Job? = null

  fun onCoalescedChange() {
    submit(SnapshotWriteUrgency.COALESCED)
  }

  fun onImmediateChange() {
    submit(SnapshotWriteUrgency.IMMEDIATE)
  }

  fun onIsPlayingChanged(isPlaying: Boolean) {
    this.isPlaying = isPlaying
    if (isPlaying && gate.canPersist) {
      startTicker()
    } else {
      stopTicker()
      if (!isPlaying) onImmediateChange()
    }
  }

  fun onGateOpened() {
    check(gate.canPersist)
    onImmediateChange()
    if (isPlaying) startTicker()
  }

  fun close(): Job {
    stopTicker()
    val finalSnapshot = if (gate.canPersist) capture() else null
    return writer.close(finalSnapshot)
  }

  private fun submit(urgency: SnapshotWriteUrgency) {
    if (!gate.canPersist) return
    writer.submit(capture(), urgency)
  }

  private fun startTicker() {
    if (tickerJob?.isActive == true) return
    tickerJob =
      scope.launch {
        while (isActive && isPlaying) {
          delay(positionSampleIntervalMs)
          if (isPlaying) onImmediateChange()
        }
      }
  }

  private fun stopTicker() {
    tickerJob?.cancel()
    tickerJob = null
  }
}
```

- [ ] **Step 6: Run focused tests and the complete player suite**

```bash
./gradlew :core:player:testDebugUnitTest \
  --tests 'app.yinyuehe.core.player.service.PlaybackSnapshotWriterTest' \
  --tests 'app.yinyuehe.core.player.service.PlaybackPersistenceCoordinatorTest'
./gradlew :core:player:testDebugUnitTest
```

Expected: PASS. The virtual-time assertions fix 250ms coalescing, 5,000ms sampling, and 1,000ms close; the existing player tests remain green.

- [ ] **Step 7: Commit writer and scheduler together**

```bash
git add core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackSnapshotWriter.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackPersistenceCoordinator.kt \
  core/player/src/test/kotlin/app/yinyuehe/core/player/service/PlaybackSnapshotWriterTest.kt \
  core/player/src/test/kotlin/app/yinyuehe/core/player/service/PlaybackPersistenceCoordinatorTest.kt
git commit -m "feat: persist serialized playback snapshots"
```

- [ ] **Step 8: Run the mandatory two-stage review before Task 7**

Dispatch a fresh specification-compliance reviewer, resolve every Critical/Important finding, then dispatch a fresh code-quality reviewer and resolve every Critical/Important finding. Re-run the focused virtual-time tests and `./gradlew :core:player:testDebugUnitTest` after any review fix.

---

## Task 7: Repeat and move commands, occurrence tokens, and callback-owned state

**Files:**
- Create: `core/player/src/main/kotlin/app/yinyuehe/core/player/PlaybackSessionProtocol.kt`
- Create: `core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackOccurrenceTokens.kt`
- Modify: `core/player/src/main/kotlin/app/yinyuehe/core/player/PlaybackController.kt`
- Modify: `core/player/src/main/kotlin/app/yinyuehe/core/player/PlaybackState.kt`
- Modify: `core/player/src/main/kotlin/app/yinyuehe/core/player/PlayerSnapshot.kt`
- Modify: `core/player/src/main/kotlin/app/yinyuehe/core/player/PlaybackCommandDispatcher.kt`
- Modify: `core/player/src/main/kotlin/app/yinyuehe/core/player/Media3PlaybackController.kt`
- Modify: `core/testing/src/main/kotlin/app/yinyuehe/core/testing/FakePlaybackController.kt`
- Test: `core/player/src/test/kotlin/app/yinyuehe/core/player/service/PlaybackOccurrenceTokensTest.kt`
- Modify test: `core/player/src/test/kotlin/app/yinyuehe/core/player/PlaybackCommandTest.kt`
- Modify test: `core/player/src/test/kotlin/app/yinyuehe/core/player/PlayerSnapshotTest.kt`

**Interfaces:**
- Consumes: `PlaybackRepeatMode` from Task 1 and the existing `Track.toMediaItem()` mapper.
- Produces:
  ```kotlin
  fun PlaybackController.setRepeatMode(mode: PlaybackRepeatMode)
  fun PlaybackController.moveQueueItem(fromIndex: Int, toIndex: Int)

  internal value class PlaybackOccurrenceToken(val value: Long)

  internal class PlaybackOccurrenceTokens {
    fun decorate(mediaItem: MediaItem): MediaItem
    fun read(mediaItem: MediaItem): PlaybackOccurrenceToken?
  }
  ```
- `PlaybackState` additionally produces `repeatMode`, `queuePersistenceLimited`, `canSetRepeatMode`, `canSetShuffle`, `canChangeQueue`, and `canSkipToQueueItem`. Tasks 9 and 10 populate its already-declared `playbackError` and `connectionError` fields.
- Task 8 consumes occurrence tokens when restoring and fingerprinting MediaItems; Task 9 consumes them as the unit of failure-loop protection.

- [ ] **Step 1: Add failing command and token tests**

Append these tests to `PlaybackCommandTest.kt`:

```kotlin
@Test
fun repeatAndMove_validateCommandsAndOccurrenceIndices() {
  val player = RecordingPlayer(mediaItemCount = 3)
  val dispatcher = PlaybackCommandDispatcher(player.instance)

  dispatcher.setRepeatMode(PlaybackRepeatMode.ONE)
  dispatcher.moveQueueItem(-1, 1)
  dispatcher.moveQueueItem(0, 3)
  dispatcher.moveQueueItem(1, 1)
  dispatcher.moveQueueItem(0, 2)

  assertEquals(
    listOf(
      Call("setRepeatMode", listOf(Player.REPEAT_MODE_ONE)),
      Call("moveMediaItem", listOf(0, 2)),
    ),
    player.calls,
  )
}

@Test
fun repeatAndMove_doNothingWhenCommandsAreUnavailable() {
  val player = RecordingPlayer(mediaItemCount = 2, availableCommands = emptySet())
  val dispatcher = PlaybackCommandDispatcher(player.instance)

  dispatcher.setRepeatMode(PlaybackRepeatMode.ALL)
  dispatcher.moveQueueItem(0, 1)

  assertTrue(player.calls.isEmpty())
}
```

Add `Player.COMMAND_SET_REPEAT_MODE` to `RecordingPlayer`'s default `availableCommands`.

Create `service/PlaybackOccurrenceTokensTest.kt`:

```kotlin
package app.yinyuehe.core.player.service

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackOccurrenceTokensTest {
  @Test
  fun duplicateMediaIdsReceiveDistinctTokensWithoutChangingMediaIdOrExistingExtras() {
    val next = AtomicLong(40)
    val tokens = PlaybackOccurrenceTokens(next::incrementAndGet)
    val source =
      MediaItem.Builder()
        .setMediaId("demo:duplicate")
        .setUri("android.resource://app/1")
        .setMediaMetadata(
          MediaMetadata.Builder()
            .setExtras(Bundle().apply { putString("existing", "kept") })
            .build()
        )
        .build()

    val first = tokens.decorate(source)
    val second = tokens.decorate(source)

    assertEquals("demo:duplicate", first.mediaId)
    assertEquals("kept", first.mediaMetadata.extras?.getString("existing"))
    assertEquals(PlaybackOccurrenceToken(41), tokens.read(first))
    assertEquals(PlaybackOccurrenceToken(42), tokens.read(second))
    assertNotEquals(tokens.read(first), tokens.read(second))
    assertNull(tokens.read(source))
  }
}
```

- [ ] **Step 2: Add failing callback-state assertions**

Add this test to `PlayerSnapshotTest.kt` and add the new constructor arguments shown here to the existing `PlayerSnapshot` fixtures:

```kotlin
@Test
fun snapshot_mapsRepeatPersistenceLimitAndExactCommandCapabilities() {
  val state =
    PlayerSnapshot(
        connection = PlaybackConnection.CONNECTED,
        currentMediaId = "demo:one",
        currentIndex = 0,
        isPlaying = false,
        playWhenReady = false,
        hasCurrentMediaItem = true,
        isIdle = false,
        isEnded = false,
        positionMs = 0,
        durationMs = 1_000,
        queueMediaIds = listOf("demo:one"),
        shuffleEnabled = true,
        repeatMode = PlaybackRepeatMode.ONE,
        queuePersistenceLimited = true,
        canPlayPause = true,
        canPrepare = true,
        canSeekToDefaultPosition = true,
        canSeek = true,
        canPrevious = false,
        canNext = false,
        canSetRepeatMode = true,
        canSetShuffle = false,
        canChangeQueue = false,
        canSkipToQueueItem = true,
      )
      .toPlaybackState()

  assertEquals(PlaybackRepeatMode.ONE, state.repeatMode)
  assertTrue(state.queuePersistenceLimited)
  assertTrue(state.canSetRepeatMode)
  assertFalse(state.canSetShuffle)
  assertFalse(state.canChangeQueue)
  assertTrue(state.canSkipToQueueItem)
}
```

- [ ] **Step 3: Run the focused tests to prove RED**

```bash
./gradlew :core:player:testDebugUnitTest \
  --tests 'app.yinyuehe.core.player.PlaybackCommandTest' \
  --tests 'app.yinyuehe.core.player.PlayerSnapshotTest' \
  --tests 'app.yinyuehe.core.player.service.PlaybackOccurrenceTokensTest'
```

Expected: FAIL because repeat/move APIs, occurrence-token classes, and the new snapshot fields do not exist.

- [ ] **Step 4: Implement the private Session extras key and occurrence-token codec**

Create `PlaybackSessionProtocol.kt`:

```kotlin
package app.yinyuehe.core.player

import android.os.Bundle

internal object PlaybackSessionProtocol {
  private const val EXTRA_QUEUE_PERSISTENCE_LIMITED =
    "app.yinyuehe.extra.QUEUE_PERSISTENCE_LIMITED"

  fun sessionExtras(
    queuePersistenceLimited: Boolean,
    base: Bundle = Bundle.EMPTY,
  ): Bundle =
    Bundle(base).apply { putBoolean(EXTRA_QUEUE_PERSISTENCE_LIMITED, queuePersistenceLimited) }

  fun queuePersistenceLimited(extras: Bundle): Boolean =
    extras.getBoolean(EXTRA_QUEUE_PERSISTENCE_LIMITED, false)
}
```

Create `PlaybackOccurrenceTokens.kt`:

```kotlin
package app.yinyuehe.core.player.service

import android.os.Bundle
import androidx.media3.common.MediaItem
import java.util.concurrent.atomic.AtomicLong

@JvmInline
internal value class PlaybackOccurrenceToken(val value: Long)

internal class PlaybackOccurrenceTokens(
  private val nextValue: () -> Long = AtomicLong()::incrementAndGet,
) {
  fun decorate(mediaItem: MediaItem): MediaItem {
    val extras = Bundle(mediaItem.mediaMetadata.extras ?: Bundle.EMPTY)
    extras.putLong(EXTRA_OCCURRENCE_TOKEN, nextValue())
    return mediaItem
      .buildUpon()
      .setMediaMetadata(mediaItem.mediaMetadata.buildUpon().setExtras(extras).build())
      .build()
  }

  fun read(mediaItem: MediaItem): PlaybackOccurrenceToken? {
    val extras = mediaItem.mediaMetadata.extras ?: return null
    if (!extras.containsKey(EXTRA_OCCURRENCE_TOKEN)) return null
    return PlaybackOccurrenceToken(extras.getLong(EXTRA_OCCURRENCE_TOKEN))
  }

  private companion object {
    const val EXTRA_OCCURRENCE_TOKEN = "app.yinyuehe.extra.OCCURRENCE_TOKEN"
  }
}
```

- [ ] **Step 5: Extend the public controller contract and state without adding UI-owned state**

Replace `PlaybackController.kt` with:

```kotlin
package app.yinyuehe.core.player

import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.playback.PlaybackRepeatMode
import kotlinx.coroutines.flow.StateFlow

interface PlaybackController {
  val state: StateFlow<PlaybackState>
  suspend fun play(tracks: List<Track>, startIndex: Int, shuffle: Boolean = false): Boolean
  fun togglePlayPause()
  fun seekTo(positionMs: Long)
  fun seekToPrevious()
  fun seekToNext()
  fun addToQueue(track: Track)
  fun removeQueueItem(index: Int)
  fun skipToQueueItem(index: Int)
  fun setShuffleEnabled(enabled: Boolean)
  fun setRepeatMode(mode: PlaybackRepeatMode)
  fun moveQueueItem(fromIndex: Int, toIndex: Int)
}
```

Keep the existing toggle-decision declarations and replace only the `PlaybackState` declaration in `PlaybackState.kt` with:

```kotlin
data class PlaybackState(
  val connection: PlaybackConnection = PlaybackConnection.CONNECTING,
  val currentTrackId: TrackId? = null,
  val currentIndex: Int = -1,
  val isPlaying: Boolean = false,
  val toggleAction: PlaybackToggleAction = PlaybackToggleAction.PLAY,
  val canTogglePlayPause: Boolean = false,
  val positionMs: Long = 0,
  val durationMs: Long = 0,
  val queueTrackIds: List<TrackId> = emptyList(),
  val shuffleEnabled: Boolean = false,
  val repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.OFF,
  val playbackError: PlaybackError? = null,
  val connectionError: PlaybackConnectionError? = null,
  val queuePersistenceLimited: Boolean = false,
  val canSeek: Boolean = false,
  val canPrevious: Boolean = false,
  val canNext: Boolean = false,
  val canSetRepeatMode: Boolean = false,
  val canSetShuffle: Boolean = false,
  val canChangeQueue: Boolean = false,
  val canSkipToQueueItem: Boolean = false,
)
```

Add these imports to `PlaybackState.kt`:

```kotlin
import app.yinyuehe.core.common.playback.PlaybackConnectionError
import app.yinyuehe.core.common.playback.PlaybackError
import app.yinyuehe.core.common.playback.PlaybackRepeatMode
```

- [ ] **Step 6: Implement repeat and occurrence-index move dispatch**

Add these methods to `PlaybackCommandDispatcher` and add `COMMAND_SET_REPEAT_MODE` only to the appropriate command checks:

```kotlin
fun setRepeatMode(mode: PlaybackRepeatMode) {
  if (!player.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE)) return
  player.repeatMode =
    when (mode) {
      PlaybackRepeatMode.OFF -> Player.REPEAT_MODE_OFF
      PlaybackRepeatMode.ALL -> Player.REPEAT_MODE_ALL
      PlaybackRepeatMode.ONE -> Player.REPEAT_MODE_ONE
    }
}

fun moveQueueItem(fromIndex: Int, toIndex: Int) {
  if (!player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return
  if (fromIndex !in 0 until player.mediaItemCount) return
  if (toIndex !in 0 until player.mediaItemCount) return
  if (fromIndex == toIndex) return
  player.moveMediaItem(fromIndex, toIndex)
}
```

Add:

```kotlin
import app.yinyuehe.core.common.playback.PlaybackRepeatMode
```

Add delegations to `Media3PlaybackController`:

```kotlin
override fun setRepeatMode(mode: PlaybackRepeatMode) {
  dispatch { setRepeatMode(mode) }
}

override fun moveQueueItem(fromIndex: Int, toIndex: Int) {
  dispatch { moveQueueItem(fromIndex, toIndex) }
}
```

- [ ] **Step 7: Extend PlayerSnapshot and snapshot capture with actual Media3 callbacks and capabilities**

Add these fields to `PlayerSnapshot` immediately after `shuffleEnabled` and after `canNext` respectively:

```kotlin
val repeatMode: PlaybackRepeatMode,
val queuePersistenceLimited: Boolean,
```

```kotlin
val canSetRepeatMode: Boolean,
val canSetShuffle: Boolean,
val canChangeQueue: Boolean,
val canSkipToQueueItem: Boolean,
```

Add the following exact named arguments to the `PlaybackState` constructor in `toPlaybackState()`:

```kotlin
repeatMode = repeatMode,
queuePersistenceLimited = queuePersistenceLimited,
canSetRepeatMode = canSetRepeatMode,
canSetShuffle = canSetShuffle,
canChangeQueue = canChangeQueue && !queuePersistenceLimited,
canSkipToQueueItem = canSkipToQueueItem,
```

Change the controller snapshot helper in `Media3PlaybackController.kt` to accept `MediaController` and use this complete tail when constructing `PlayerSnapshot`:

```kotlin
repeatMode =
  when (repeatMode) {
    Player.REPEAT_MODE_ALL -> PlaybackRepeatMode.ALL
    Player.REPEAT_MODE_ONE -> PlaybackRepeatMode.ONE
    else -> PlaybackRepeatMode.OFF
  },
queuePersistenceLimited =
  PlaybackSessionProtocol.queuePersistenceLimited(sessionExtras),
canPlayPause = isCommandAvailable(Player.COMMAND_PLAY_PAUSE),
canPrepare = isCommandAvailable(Player.COMMAND_PREPARE),
canSeekToDefaultPosition = isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION),
canSeek = isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
canPrevious =
  isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) && hasPreviousMediaItem(),
canNext = isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) && hasNextMediaItem(),
canSetRepeatMode = isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE),
canSetShuffle = isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE),
canChangeQueue = isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS),
canSkipToQueueItem = isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM),
```

Update every test fixture constructor with deterministic values for these fields. Do not infer capabilities in ViewModel or Compose.

- [ ] **Step 8: Update the reusable fake and all current private test fakes**

Add to `FakePlaybackController`:

```kotlin
val repeatUpdates = mutableListOf<PlaybackRepeatMode>()
val movedQueueItems = mutableListOf<Pair<Int, Int>>()

override fun setRepeatMode(mode: PlaybackRepeatMode) {
  repeatUpdates += mode
}

override fun moveQueueItem(fromIndex: Int, toIndex: Int) {
  movedQueueItems += fromIndex to toIndex
}
```

Add the same two no-op-compatible overrides to the private `RecordingPlaybackController` in `feature/library/src/test/kotlin/app/yinyuehe/feature/library/LibraryViewModelTest.kt`, recording the values in lists. This keeps the repository compiling before the UI task consumes them.

- [ ] **Step 9: Run focused tests, all JVM consumers, and lint**

```bash
./gradlew :core:player:testDebugUnitTest \
  --tests 'app.yinyuehe.core.player.PlaybackCommandTest' \
  --tests 'app.yinyuehe.core.player.PlayerSnapshotTest' \
  --tests 'app.yinyuehe.core.player.service.PlaybackOccurrenceTokensTest'
./gradlew :core:player:testDebugUnitTest :core:testing:assembleDebug \
  :feature:library:testDebugUnitTest :core:player:lintDebug
```

Expected: PASS. Repeat and move are dispatched only through available Media3 commands; duplicate media IDs receive distinct tokens; callback-derived state and every `PlaybackController` implementation compile.

- [ ] **Step 10: Commit the command and state contract atomically**

```bash
git add \
  core/player/src/main/kotlin/app/yinyuehe/core/player/PlaybackSessionProtocol.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackOccurrenceTokens.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/PlaybackController.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/PlaybackState.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/PlayerSnapshot.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/PlaybackCommandDispatcher.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/Media3PlaybackController.kt \
  core/player/src/test/kotlin/app/yinyuehe/core/player/service/PlaybackOccurrenceTokensTest.kt \
  core/player/src/test/kotlin/app/yinyuehe/core/player/PlaybackCommandTest.kt \
  core/player/src/test/kotlin/app/yinyuehe/core/player/PlayerSnapshotTest.kt \
  core/testing/src/main/kotlin/app/yinyuehe/core/testing/FakePlaybackController.kt \
  feature/library/src/test/kotlin/app/yinyuehe/feature/library/LibraryViewModelTest.kt
git commit -m "feat: add repeat and occurrence queue controls"
```

- [ ] **Step 11: Run the mandatory two-stage review before Task 8**

Dispatch a fresh specification-compliance reviewer, resolve every Critical/Important finding, then dispatch a fresh code-quality reviewer and resolve every Critical/Important finding. Require the reviewers to check that duplicate identity uses the opaque token, `mediaId` remains the stable TrackId, no token enters Proto, and state is updated only from Player callbacks.

---

## Task 8: Wire Service-owned restore and persistence to the real Media3 Player

**Files:**
- Create: `core/player/src/main/kotlin/app/yinyuehe/core/player/service/Media3PlaybackBridge.kt`
- Create: `core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackLibrarySessionCallback.kt`
- Create: `core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackPersistencePlayerListener.kt`
- Create: `core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackRestoreBarrier.kt`
- Modify: `core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackRestoreCoordinator.kt`
- Modify: `core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackService.kt`
- Test: `core/player/src/test/kotlin/app/yinyuehe/core/player/service/Media3PlaybackBridgeTest.kt`
- Test: `core/player/src/test/kotlin/app/yinyuehe/core/player/service/PlaybackPersistencePlayerListenerTest.kt`

**Interfaces:**
- Consumes: Tasks 3–7's `PlaybackSnapshotStore`, `PlaybackQueueResolver`, `PlaybackRestoreCoordinator`, `RestorePersistenceGate`, `PlaybackSnapshotWriter`, `PlaybackPersistenceCoordinator`, `PlaybackOccurrenceTokens`, and `PlaybackSessionProtocol`.
- Produces: the only production `RestorablePlayer`, `Player.capturePlaybackSnapshot()`, `Player.queueFingerprint(tokens)`, a Session callback that tokenizes every set/add queue path while registering the exact caller-bound full-replacement marker, and an empty release multibinding for deterministic debug-only restore barriers.
- Task 9 adds failure and notice callbacks to the same Service listener/session callback without changing restore ownership.

- [ ] **Step 1: Add a failing real-Player-adapter test proving paused restore and exact mode order**

Create `Media3PlaybackBridgeTest.kt`:

```kotlin
package app.yinyuehe.core.player.service

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackRepeatMode
import app.yinyuehe.core.common.playback.PlaybackSnapshot
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Media3PlaybackBridgeTest {
  @Test
  fun applyRestoredQueueAssignsTokensPreparesAndNeverStartsPlayback() {
    val recording = RecordingBridgePlayer()
    val bridge =
      Media3RestorablePlayer(
        recording.player,
        PlaybackOccurrenceTokens(AtomicLong(0)::incrementAndGet),
      )
    val track =
      Track(
        id = TrackId("demo:one"),
        title = "One",
        artist = null,
        album = null,
        durationMs = 3_000,
        artworkUri = null,
        sourceUri = "android.resource://app/1",
        isDemo = true,
      )
    val snapshot =
      PlaybackSnapshot(
        mediaIds = listOf(track.id),
        currentIndex = 0,
        positionMs = 1_200,
        shuffleEnabled = true,
        repeatMode = PlaybackRepeatMode.ONE,
      )

    bridge.apply(
      PlaybackRestorePlan(
        tracks = listOf(track),
        currentIndex = 0,
        positionMs = 1_200,
        shuffleEnabled = true,
        repeatMode = PlaybackRepeatMode.ONE,
        normalizedSnapshot = snapshot,
      )
    )

    assertEquals(
      listOf(
        "setPlayWhenReady",
        "setMediaItems",
        "setShuffleModeEnabled",
        "setRepeatMode",
        "prepare",
        "setPlayWhenReady",
      ),
      recording.calls.map(BridgeCall::name),
    )
    val items = recording.calls[1].arguments[0] as List<*>
    val mediaItem = items.single() as MediaItem
    assertEquals("demo:one", mediaItem.mediaId)
    assertEquals(PlaybackOccurrenceToken(1), bridge.tokens.read(mediaItem))
    assertFalse(recording.playWasCalled)
  }
}

private data class BridgeCall(val name: String, val arguments: List<Any?>)

private class RecordingBridgePlayer {
  val calls = mutableListOf<BridgeCall>()
  var playWasCalled = false
  val player =
    Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
      when (method.name) {
        "getMediaItemCount" -> 0
        "getPlayWhenReady" -> false
        "play" -> {
          playWasCalled = true
          calls += BridgeCall(method.name, args?.toList().orEmpty())
          null
        }
        "hashCode" -> System.identityHashCode(this)
        "equals" -> false
        "toString" -> "RecordingBridgePlayer"
        else -> {
          calls += BridgeCall(method.name, args?.toList().orEmpty())
          method.defaultBridgeValue()
        }
      }
    } as Player
}

private fun Method.defaultBridgeValue(): Any? =
  when (returnType) {
    Boolean::class.javaPrimitiveType -> false
    Int::class.javaPrimitiveType -> 0
    Long::class.javaPrimitiveType -> 0L
    Float::class.javaPrimitiveType -> 0f
    Double::class.javaPrimitiveType -> 0.0
    else -> null
  }
```

- [ ] **Step 2: Run the bridge test to prove RED**

```bash
./gradlew :core:player:testDebugUnitTest \
  --tests 'app.yinyuehe.core.player.service.Media3PlaybackBridgeTest'
```

Expected: FAIL because `Media3RestorablePlayer` and the Media3 snapshot/fingerprint bridge do not exist.

- [ ] **Step 3: Implement the main-looper-confined Media3 bridge**

Create `Media3PlaybackBridge.kt`:

```kotlin
package app.yinyuehe.core.player.service

import androidx.media3.common.C
import androidx.media3.common.Player
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackRepeatMode
import app.yinyuehe.core.common.playback.PlaybackSnapshot
import app.yinyuehe.core.player.toMediaItem

internal class Media3RestorablePlayer(
  private val player: Player,
  internal val tokens: PlaybackOccurrenceTokens,
) : RestorablePlayer {
  override val isQueueEmpty: Boolean
    get() = player.mediaItemCount == 0

  override fun apply(plan: PlaybackRestorePlan) {
    player.playWhenReady = false
    if (plan.tracks.isEmpty()) {
      player.clearMediaItems()
    } else {
      val items = plan.tracks.map { track -> tokens.decorate(track.toMediaItem()) }
      player.setMediaItems(items, plan.currentIndex, plan.positionMs)
    }
    player.shuffleModeEnabled = plan.shuffleEnabled
    player.repeatMode = plan.repeatMode.toMedia3RepeatMode()
    if (plan.tracks.isNotEmpty()) player.prepare()
    player.playWhenReady = false
  }

  override fun queueFingerprint(): PlayerQueueFingerprint = player.queueFingerprint(tokens)
}

internal fun Player.capturePlaybackSnapshot(): PlaybackSnapshot {
  val indexedIds =
    List(mediaItemCount) { index -> index to getMediaItemAt(index).mediaId }
      .filter { (_, mediaId) -> mediaId.isNotBlank() }
  val mappedIndex = indexedIds.indexOfFirst { (index) -> index == currentMediaItemIndex }
  if (indexedIds.isEmpty()) return PlaybackSnapshot.empty()
  return PlaybackSnapshot(
    mediaIds = indexedIds.map { (_, mediaId) -> TrackId(mediaId) },
    currentIndex = mappedIndex.coerceAtLeast(0),
    positionMs = currentPosition.coerceAtLeast(0),
    shuffleEnabled = shuffleModeEnabled,
    repeatMode = repeatMode.toDomainRepeatMode(),
  )
}

internal fun Player.queueFingerprint(tokens: PlaybackOccurrenceTokens): PlayerQueueFingerprint =
  PlayerQueueFingerprint(
    occurrenceKeys =
      List(mediaItemCount) { index ->
        val item = getMediaItemAt(index)
        tokens.read(item)?.value?.toString() ?: "missing-token:$index:${item.mediaId}"
      },
    mediaIds = List(mediaItemCount) { getMediaItemAt(it).mediaId },
    currentIndex = currentMediaItemIndex.takeUnless { it == C.INDEX_UNSET } ?: -1,
  )

internal fun PlaybackRepeatMode.toMedia3RepeatMode(): Int =
  when (this) {
    PlaybackRepeatMode.OFF -> Player.REPEAT_MODE_OFF
    PlaybackRepeatMode.ALL -> Player.REPEAT_MODE_ALL
    PlaybackRepeatMode.ONE -> Player.REPEAT_MODE_ONE
  }

internal fun Int.toDomainRepeatMode(): PlaybackRepeatMode =
  when (this) {
    Player.REPEAT_MODE_ALL -> PlaybackRepeatMode.ALL
    Player.REPEAT_MODE_ONE -> PlaybackRepeatMode.ONE
    else -> PlaybackRepeatMode.OFF
  }
```

- [ ] **Step 4: Extract the Session callback so every controller queue entry receives a fresh token**

Create `PlaybackLibrarySessionCallback.kt`:

```kotlin
package app.yinyuehe.core.player.service

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

internal class PlaybackLibrarySessionCallback(
  private val tokens: PlaybackOccurrenceTokens,
  private val gate: RestorePersistenceGate,
) : MediaLibrarySession.Callback {
  @UnstableApi
  override fun onConnect(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
  ): MediaSession.ConnectionResult =
    if (controller.isTrusted) super.onConnect(mediaSession, controller)
    else MediaSession.ConnectionResult.reject()

  override fun onAddMediaItems(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    mediaItems: List<MediaItem>,
  ): ListenableFuture<List<MediaItem>> =
    Futures.immediateFuture(mediaItems.map(tokens::decorate))

  override fun onSetMediaItems(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
    gate.recordSetMediaItems(
      caller = ControllerIdentity(controller.packageName, controller.uid),
      expectedMediaIds = mediaItems.map(MediaItem::mediaId),
      startIndex = startIndex,
    )
    return Futures.immediateFuture(
      MediaSession.MediaItemsWithStartPosition(
        mediaItems.map(tokens::decorate),
        startIndex,
        startPositionMs,
      )
    )
  }
}
```

Do not call the default `onSetMediaItems`: Media3 1.10.1's default implementation calls `onAddMediaItems`, which would assign a second token.

- [ ] **Step 5: Route Player callbacks into the gate and semantic persistence scheduler**

Create `PlaybackPersistencePlayerListener.kt`:

```kotlin
package app.yinyuehe.core.player.service

import androidx.media3.common.Player

internal class PlaybackPersistencePlayerListener(
  private val player: Player,
  private val tokens: PlaybackOccurrenceTokens,
  private val gate: RestorePersistenceGate,
  private val persistence: PlaybackPersistenceCoordinator,
  private val onGateChanged: () -> Unit,
) : Player.Listener {
  override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
    val opened = gate.onConfirmedTimeline(player.queueFingerprint(tokens))
    if (opened) {
      onGateChanged()
      persistence.onGateOpened()
    } else {
      persistence.onCoalescedChange()
    }
  }

  override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
    persistence.onCoalescedChange()
  }

  override fun onRepeatModeChanged(repeatMode: Int) = persistence.onCoalescedChange()

  override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) =
    persistence.onCoalescedChange()

  override fun onPositionDiscontinuity(
    oldPosition: Player.PositionInfo,
    newPosition: Player.PositionInfo,
    reason: Int,
  ) {
    if (
      reason == Player.DISCONTINUITY_REASON_SEEK ||
        reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
    ) {
      persistence.onImmediateChange()
    }
  }

  override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
    if (!playWhenReady) persistence.onImmediateChange()
  }

  override fun onIsPlayingChanged(isPlaying: Boolean) =
    persistence.onIsPlayingChanged(isPlaying)

  override fun onPlaybackStateChanged(playbackState: Int) {
    if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
      persistence.onImmediateChange()
    }
  }
}
```

- [ ] **Step 6: Add the empty release restore-barrier hook and compose the kernel inside PlaybackService**

Create `PlaybackRestoreBarrier.kt`; the release graph has an empty set, while Task 12 contributes the only debug implementation:

```kotlin
package app.yinyuehe.core.player.service

import dagger.Module
import dagger.multibindings.Multibinds
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

enum class PlaybackRestoreBarrierPhase { BEFORE_READ, BEFORE_APPLY }

fun interface PlaybackRestoreBarrier {
  suspend fun awaitPhase(phase: PlaybackRestoreBarrierPhase)
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PlaybackRestoreBarrierBindings {
  @Multibinds
  abstract fun playbackRestoreBarriers(): Set<PlaybackRestoreBarrier>
}
```

Add these final constructor parameters to `PlaybackRestoreCoordinator`. Invoke `beforeRead` before `snapshotStore.read()` so destruction can deterministically win a still-pending read; invoke `beforeApply` after Room resolution but before `tryBeginRestoreApply` so a full user replacement can win after resolution. Returning from either barrier must still pass cancellation, generation, and empty-player checks:

```kotlin
private val beforeRead: suspend () -> Unit = {},
private val beforeApply: suspend () -> Unit = {},
```

```kotlin
beforeRead()
val read = withContext(ioDispatcher) { snapshotStore.read() }
```

```kotlin
val plan = buildPlaybackRestorePlan(snapshot, resolution)
beforeApply()
if (!gate.tryBeginRestoreApply(expectedGeneration, player.isQueueEmpty)) return
```

Add these injected fields and owned objects to `PlaybackService`:

```kotlin
@Inject lateinit var playbackSnapshotStore: PlaybackSnapshotStore
@Inject lateinit var playbackQueueResolver: PlaybackQueueResolver
@Inject
lateinit var playbackRestoreBarriers: Set<@JvmSuppressWildcards PlaybackRestoreBarrier>

private val serviceJob = SupervisorJob()
private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
private var restoreCoordinator: PlaybackRestoreCoordinator? = null
private var persistenceCoordinator: PlaybackPersistenceCoordinator? = null
private var persistenceListener: Player.Listener? = null
```

Immediately after constructing ExoPlayer and before adding any new listener, create and connect the kernel with this code:

```kotlin
val applicationController = ControllerIdentity(packageName, applicationInfo.uid)
val gate = RestorePersistenceGate(applicationController)
val tokens = PlaybackOccurrenceTokens()
val writer =
  PlaybackSnapshotWriter(
    snapshotStore = playbackSnapshotStore,
    dispatcher = Dispatchers.IO,
    onFailure = { error ->
      Log.w(TAG, "Playback snapshot write failed: ${error::class.java.simpleName}")
    },
  )
val persistence =
  PlaybackPersistenceCoordinator(
    gate = gate,
    writer = writer,
    scope = serviceScope,
    capture = player::capturePlaybackSnapshot,
  )
val persistenceListener =
  PlaybackPersistencePlayerListener(player, tokens, gate, persistence) {
    session?.setSessionExtras(
      PlaybackSessionProtocol.sessionExtras(gate.queuePersistenceLimited)
    )
  }
player.addListener(persistenceListener)
this.persistenceListener = persistenceListener
this.persistenceCoordinator = persistence

val callback = PlaybackLibrarySessionCallback(tokens, gate)
session = MediaLibrarySession.Builder(this, player, callback).build()
session?.setSessionExtras(PlaybackSessionProtocol.sessionExtras(false))

restoreCoordinator =
  PlaybackRestoreCoordinator(
      snapshotStore = playbackSnapshotStore,
      queueResolver = playbackQueueResolver,
      gate = gate,
      player = Media3RestorablePlayer(player, tokens),
      scope = serviceScope,
      ioDispatcher = Dispatchers.IO,
      onNormalizedSnapshot = { snapshot ->
        writer.submit(snapshot, SnapshotWriteUrgency.IMMEDIATE)
      },
      onGateChanged = {
        session?.setSessionExtras(
          PlaybackSessionProtocol.sessionExtras(gate.queuePersistenceLimited)
        )
      },
      onFailure = { error ->
        Log.w(TAG, "Playback restore failed: ${error::class.java.simpleName}")
      },
      beforeRead = {
        playbackRestoreBarriers.forEach { barrier ->
          barrier.awaitPhase(PlaybackRestoreBarrierPhase.BEFORE_READ)
        }
      },
      beforeApply = {
        playbackRestoreBarriers.forEach { barrier ->
          barrier.awaitPhase(PlaybackRestoreBarrierPhase.BEFORE_APPLY)
        }
      },
    )
    .also(PlaybackRestoreCoordinator::start)
```

Replace the relevant beginning of `onDestroy()` with this non-blocking order, then retain the existing analytics/session/player cleanup:

```kotlin
restoreCoordinator?.cancel()
restoreCoordinator = null
persistenceListener?.let { listener -> player?.removeListener(listener) }
persistenceListener = null
persistenceCoordinator?.close()
persistenceCoordinator = null
serviceJob.cancel()
```

The close Job is intentionally not joined on the Service main thread. Force-stop durability comes from the 5-second samples, not from `onDestroy()`.

- [ ] **Step 7: Add a listener unit test and run Service compilation gates**

Create `PlaybackPersistencePlayerListenerTest.kt`:

```kotlin
package app.yinyuehe.core.player.service

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import app.yinyuehe.core.common.playback.PlaybackSnapshot
import app.yinyuehe.core.common.playback.PlaybackSnapshotReadResult
import app.yinyuehe.core.common.playback.PlaybackSnapshotStore
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaybackPersistencePlayerListenerTest {
  @Test
  fun initialEmptyIsSuppressed_userTimelineOpensGate_andSeekIsImmediate() = runTest {
    val tokens = PlaybackOccurrenceTokens { 1 }
    val mutablePlayer = MutableQueuePlayer()
    val gate = RestorePersistenceGate(ControllerIdentity("app.yinyuehe", 1))
    val store = ListenerRecordingStore()
    val writer =
      PlaybackSnapshotWriter(store, StandardTestDispatcher(testScheduler))
    val persistence =
      PlaybackPersistenceCoordinator(
        gate,
        writer,
        backgroundScope,
        mutablePlayer.player::capturePlaybackSnapshot,
      )
    var gateChangeCount = 0
    val listener =
      PlaybackPersistencePlayerListener(
        mutablePlayer.player,
        tokens,
        gate,
        persistence,
      ) { gateChangeCount += 1 }

    listener.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
    runCurrent()
    assertEquals(RestoreGateStatus.RESTORE_PENDING, gate.status)
    assertTrue(store.writes.isEmpty())

    mutablePlayer.items =
      listOf(
        tokens.decorate(
          MediaItem.Builder().setMediaId("demo:user").setUri("android.resource://app/1").build()
        )
      )
    listener.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
    runCurrent()
    assertEquals(RestoreGateStatus.SUPERSEDED, gate.status)
    assertEquals(1, gateChangeCount)
    assertEquals(1, store.writes.size)

    listener.onPositionDiscontinuity(
      positionInfo(),
      positionInfo(),
      Player.DISCONTINUITY_REASON_SEEK,
    )
    runCurrent()
    assertEquals(2, store.writes.size)
    listener.onRepeatModeChanged(Player.REPEAT_MODE_ALL)
    advanceTimeBy(249)
    assertEquals(2, store.writes.size)
    advanceTimeBy(1)
    runCurrent()
    assertEquals(3, store.writes.size)
    persistence.close()
  }
}

private class MutableQueuePlayer {
  var items: List<MediaItem> = emptyList()
  val player =
    Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
      when (method.name) {
        "getMediaItemCount" -> items.size
        "getMediaItemAt" -> items[args?.single() as Int]
        "getCurrentMediaItemIndex" -> if (items.isEmpty()) C.INDEX_UNSET else 0
        "getCurrentPosition" -> 100L
        "getShuffleModeEnabled" -> false
        "getRepeatMode" -> Player.REPEAT_MODE_OFF
        "hashCode" -> 1
        "equals" -> false
        "toString" -> "MutableQueuePlayer"
        else -> method.listenerDefaultValue()
      }
    } as Player
}

private class ListenerRecordingStore : PlaybackSnapshotStore {
  val writes = mutableListOf<PlaybackSnapshot>()
  override suspend fun read() = PlaybackSnapshotReadResult.Usable(PlaybackSnapshot.empty())
  override suspend fun write(snapshot: PlaybackSnapshot) {
    writes += snapshot
  }
}

private fun positionInfo() = Player.PositionInfo(null, 0, null, 0, 0, 0, C.INDEX_UNSET, C.INDEX_UNSET)

private fun Method.listenerDefaultValue(): Any? =
  when (returnType) {
    Boolean::class.javaPrimitiveType -> false
    Int::class.javaPrimitiveType -> 0
    Long::class.javaPrimitiveType -> 0L
    Float::class.javaPrimitiveType -> 0f
    Double::class.javaPrimitiveType -> 0.0
    else -> null
  }
```

Run:

```bash
./gradlew :core:player:testDebugUnitTest \
  --tests 'app.yinyuehe.core.player.service.Media3PlaybackBridgeTest' \
  --tests 'app.yinyuehe.core.player.service.PlaybackPersistencePlayerListenerTest'
./gradlew :core:player:testDebugUnitTest :core:player:lintDebug :core:player:assembleDebug
./gradlew :app:assembleDebug
```

Expected: PASS. The app-level compile proves Hilt can satisfy `PlaybackSnapshotStore` and `PlaybackQueueResolver` through Task 3–4 bindings while `:core:player` still has no `:core:data` dependency.

- [ ] **Step 8: Commit Service ownership wiring atomically**

```bash
git add \
  core/player/src/main/kotlin/app/yinyuehe/core/player/service/Media3PlaybackBridge.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackLibrarySessionCallback.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackPersistencePlayerListener.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackRestoreBarrier.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackRestoreCoordinator.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackService.kt \
  core/player/src/test/kotlin/app/yinyuehe/core/player/service/Media3PlaybackBridgeTest.kt \
  core/player/src/test/kotlin/app/yinyuehe/core/player/service/PlaybackPersistencePlayerListenerTest.kt
git commit -m "feat: restore playback state in media service"
```

- [ ] **Step 9: Run the mandatory two-stage review before Task 9**

Dispatch a fresh specification-compliance reviewer, then a fresh code-quality reviewer. Require explicit checks for: gate creation before callbacks, no autoplay, generation plus empty-timeline guard after the optional barrier, an empty release barrier set with only a debug implementation in Task 12, permission-limited Proto preservation, no `runBlocking`, no `:core:player` to `:core:data` dependency, and final capture before Player release. Resolve every Critical/Important finding and rerun the three GREEN commands.

---

## Task 9: Typed playback failures, bounded occurrence skipping, and one-shot notices

**Files:**
- Create: `core/player/src/main/kotlin/app/yinyuehe/core/player/PlaybackErrorMapper.kt`
- Modify: `core/player/src/main/kotlin/app/yinyuehe/core/player/PlaybackSessionProtocol.kt`
- Create: `core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackFailurePolicy.kt`
- Create: `core/player/src/main/kotlin/app/yinyuehe/core/player/service/Media3FailureNavigator.kt`
- Create: `core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackFailureCoordinator.kt`
- Create: `core/player/src/main/kotlin/app/yinyuehe/core/player/service/StablePlaybackProgress.kt`
- Create: `core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackFailurePlayerListener.kt`
- Modify: `core/player/src/main/kotlin/app/yinyuehe/core/player/PlaybackController.kt`
- Modify: `core/player/src/main/kotlin/app/yinyuehe/core/player/PlayerSnapshot.kt`
- Modify: `core/player/src/main/kotlin/app/yinyuehe/core/player/Media3PlaybackController.kt`
- Modify: `core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackLibrarySessionCallback.kt`
- Modify: `core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackService.kt`
- Test: `core/player/src/test/kotlin/app/yinyuehe/core/player/PlaybackErrorMapperTest.kt`
- Test: `core/player/src/test/kotlin/app/yinyuehe/core/player/PlaybackSessionProtocolTest.kt`
- Test: `core/player/src/test/kotlin/app/yinyuehe/core/player/service/PlaybackFailurePolicyTest.kt`
- Test: `core/player/src/test/kotlin/app/yinyuehe/core/player/service/Media3FailureNavigatorTest.kt`
- Test: `core/player/src/test/kotlin/app/yinyuehe/core/player/service/PlaybackFailureCoordinatorTest.kt`
- Test: `core/player/src/test/kotlin/app/yinyuehe/core/player/service/StablePlaybackProgressTest.kt`

**Interfaces:**
- Consumes from Task 1: `PlaybackErrorType`, `PlaybackError`, and `PlaybackNotice.TrackSkipped`; consumes Task 7 occurrence tokens.
- Produces `PlaybackController.notices: Flow<PlaybackNotice>`, explicit Media3 error classification, a candidate order that respects Media3 shuffle while ignoring repeat-one for recovery, and a per-round attempted-token set.
- Task 10 preserves the notice listener while replacing connection lifecycle management.

- [ ] **Step 1: Add failing policy and protocol tests**

Create `PlaybackFailurePolicyTest.kt`:

```kotlin
package app.yinyuehe.core.player.service

import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackError
import app.yinyuehe.core.common.playback.PlaybackErrorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFailurePolicyTest {
  private val error =
    PlaybackError(PlaybackErrorType.SOURCE_UNAVAILABLE, 2005, TrackId("local:duplicate"))

  @Test
  fun duplicateTrackIdsAreAttemptedByOccurrenceTokenAndThenStopBoundedly() {
    val first = occurrence(0, 1, "local:duplicate")
    val second = occurrence(1, 2, "local:duplicate")
    val policy = PlaybackFailurePolicy()

    assertEquals(
      FailureDecision.Skip(
        error,
        targetIndex = 1,
        targetToken = second.token,
        resumePlayback = true,
      ),
      policy.onFailure(error, first, listOf(second), playIntent = true),
    )
    assertEquals(
      FailureDecision.Stop(error),
      policy.onFailure(error, second, listOf(first), playIntent = true),
    )
  }

  @Test
  fun stablePlaybackOnTheSkippedTargetStartsANewFailureRound() {
    val first = occurrence(0, 1, "local:one")
    val second = occurrence(1, 2, "local:two")
    val policy = PlaybackFailurePolicy()

    assertEquals(
      FailureDecision.Skip(
        error,
        targetIndex = 1,
        targetToken = second.token,
        resumePlayback = true,
      ),
      policy.onFailure(error, first, listOf(second), playIntent = true),
    )
    policy.onStablePlayback(second.token)
    assertEquals(
      FailureDecision.Skip(
        error,
        targetIndex = 0,
        targetToken = first.token,
        resumePlayback = true,
      ),
      policy.onFailure(error, second, listOf(first), playIntent = true),
    )
  }

  @Test
  fun naturalEndAndExplicitRetryEachClearTheAttemptedOccurrences() {
    val first = occurrence(0, 1, "local:one")
    val second = occurrence(1, 2, "local:two")
    val policy = PlaybackFailurePolicy()

    policy.onFailure(error, first, listOf(second), playIntent = true)
    policy.onPlaybackEnded()
    assertTrue(policy.onFailure(error, second, listOf(first), true) is FailureDecision.Skip)

    policy.onFailure(error, first, listOf(second), playIntent = true)
    policy.onUserRetry()
    assertTrue(policy.onFailure(error, second, listOf(first), true) is FailureDecision.Skip)
  }
}

private fun occurrence(index: Int, token: Long, id: String) =
  QueueOccurrence(index, PlaybackOccurrenceToken(token), TrackId(id))
```

Create `PlaybackSessionProtocolTest.kt`:

```kotlin
package app.yinyuehe.core.player

import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackError
import app.yinyuehe.core.common.playback.PlaybackErrorType
import app.yinyuehe.core.common.playback.PlaybackNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackSessionProtocolTest {
  @Test
  fun trackSkippedRoundTripContainsOnlyTypeCodeAndTrackId() {
    val notice =
      PlaybackNotice.TrackSkipped(
        PlaybackError(PlaybackErrorType.DECODER, 4003, TrackId("local:one"))
      )
    val encoded = PlaybackSessionProtocol.encode(notice)

    assertEquals(notice, PlaybackSessionProtocol.decode(encoded.command, encoded.extras))
    assertEquals(3, encoded.extras.keySet().size)
  }

  @Test
  fun unknownActionOrEnumIsRejectedWithoutThrowing() {
    assertNull(
      PlaybackSessionProtocol.decode(
        androidx.media3.session.SessionCommand("unknown", android.os.Bundle.EMPTY),
        android.os.Bundle.EMPTY,
      )
    )
  }

  @Test
  fun queuePersistenceExtraPreservesUnrelatedSessionExtras() {
    val base = android.os.Bundle().apply { putString("existing", "kept") }

    val extras = PlaybackSessionProtocol.sessionExtras(true, base)

    assertEquals("kept", extras.getString("existing"))
    assertEquals(true, PlaybackSessionProtocol.queuePersistenceLimited(extras))
  }
}
```

- [ ] **Step 2: Run policy, mapping, and protocol tests to prove RED**

```bash
./gradlew :core:player:testDebugUnitTest \
  --tests 'app.yinyuehe.core.player.service.PlaybackFailurePolicyTest' \
  --tests 'app.yinyuehe.core.player.PlaybackSessionProtocolTest' \
  --tests 'app.yinyuehe.core.player.PlaybackErrorMapperTest'
```

Expected: FAIL because the failure policy, Session notice codec, and Media3 error mapper are absent.

- [ ] **Step 3: Implement explicit error-code mapping without numeric ranges or exception text**

Create `PlaybackErrorMapper.kt`:

```kotlin
package app.yinyuehe.core.player

import androidx.media3.common.PlaybackException
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackError
import app.yinyuehe.core.common.playback.PlaybackErrorType

internal fun playbackError(errorCode: Int, trackId: TrackId?): PlaybackError =
  PlaybackError(
    type =
      when (errorCode) {
        PlaybackException.ERROR_CODE_PERMISSION_DENIED,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ->
          PlaybackErrorType.SOURCE_UNAVAILABLE
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
          PlaybackErrorType.UNSUPPORTED_FORMAT
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED ->
          PlaybackErrorType.DECODER
        else -> PlaybackErrorType.UNKNOWN
      },
    media3ErrorCode = errorCode,
    trackId = trackId,
  )
```

Create `PlaybackErrorMapperTest.kt`:

```kotlin
package app.yinyuehe.core.player

import androidx.media3.common.PlaybackException
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackErrorType
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackErrorMapperTest {
  @Test
  fun representativeMedia3CodesMapToStableDomainTypes() {
    val cases =
      listOf(
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND to PlaybackErrorType.SOURCE_UNAVAILABLE,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED to
          PlaybackErrorType.UNSUPPORTED_FORMAT,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED to PlaybackErrorType.DECODER,
        PlaybackException.ERROR_CODE_UNSPECIFIED to PlaybackErrorType.UNKNOWN,
      )
    val trackId = TrackId("local:one")

    cases.forEach { (code, expectedType) ->
      val mapped = playbackError(code, trackId)
      assertEquals(expectedType, mapped.type)
      assertEquals(code, mapped.media3ErrorCode)
      assertEquals(trackId, mapped.trackId)
    }
  }
}
```

- [ ] **Step 4: Implement the one-shot Session protocol and controller Flow**

Replace `PlaybackSessionProtocol` with this superset:

```kotlin
internal object PlaybackSessionProtocol {
  private const val ACTION_TRACK_SKIPPED = "app.yinyuehe.action.TRACK_SKIPPED"
  private const val EXTRA_ERROR_TYPE = "app.yinyuehe.extra.ERROR_TYPE"
  private const val EXTRA_ERROR_CODE = "app.yinyuehe.extra.ERROR_CODE"
  private const val EXTRA_TRACK_ID = "app.yinyuehe.extra.TRACK_ID"
  private const val EXTRA_QUEUE_PERSISTENCE_LIMITED =
    "app.yinyuehe.extra.QUEUE_PERSISTENCE_LIMITED"

  data class EncodedNotice(val command: SessionCommand, val extras: Bundle)

  fun encode(notice: PlaybackNotice.TrackSkipped): EncodedNotice {
    val extras =
      Bundle().apply {
        putString(EXTRA_ERROR_TYPE, notice.error.type.name)
        putInt(EXTRA_ERROR_CODE, notice.error.media3ErrorCode)
        putString(EXTRA_TRACK_ID, notice.error.trackId?.value)
      }
    return EncodedNotice(SessionCommand(ACTION_TRACK_SKIPPED, Bundle.EMPTY), extras)
  }

  fun decode(command: SessionCommand, extras: Bundle): PlaybackNotice.TrackSkipped? {
    if (command.customAction != ACTION_TRACK_SKIPPED) return null
    if (!extras.containsKey(EXTRA_ERROR_CODE)) return null
    val type =
      runCatching {
          PlaybackErrorType.valueOf(extras.getString(EXTRA_ERROR_TYPE).orEmpty())
        }
        .getOrNull() ?: return null
    val trackId = extras.getString(EXTRA_TRACK_ID)?.takeIf(String::isNotBlank)?.let(::TrackId)
    return PlaybackNotice.TrackSkipped(
      PlaybackError(type, extras.getInt(EXTRA_ERROR_CODE), trackId)
    )
  }

  fun sessionExtras(
    queuePersistenceLimited: Boolean,
    base: Bundle = Bundle.EMPTY,
  ): Bundle =
    Bundle(base).apply { putBoolean(EXTRA_QUEUE_PERSISTENCE_LIMITED, queuePersistenceLimited) }

  fun queuePersistenceLimited(extras: Bundle): Boolean =
    extras.getBoolean(EXTRA_QUEUE_PERSISTENCE_LIMITED, false)
}
```

Add the required imports for `SessionCommand`, `PlaybackNotice`, `PlaybackError`, `PlaybackErrorType`, and `TrackId`.

Add to `PlaybackController`:

```kotlin
val notices: Flow<PlaybackNotice>
```

Add to `Media3PlaybackController`:

```kotlin
private val _notices = MutableSharedFlow<PlaybackNotice>(
  extraBufferCapacity = 16,
  onBufferOverflow = BufferOverflow.DROP_OLDEST,
)
override val notices: Flow<PlaybackNotice> = _notices.asSharedFlow()
```

In its `MediaController.Listener`, add:

```kotlin
override fun onCustomCommand(
  controller: MediaController,
  command: SessionCommand,
  args: Bundle,
): ListenableFuture<SessionResult> {
  val notice = PlaybackSessionProtocol.decode(command, args)
  return if (notice != null && this@Media3PlaybackController.controller === controller) {
    _notices.tryEmit(notice)
    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
  } else {
    Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
  }
}
```

Update every fake `PlaybackController` with `MutableSharedFlow<PlaybackNotice>(extraBufferCapacity = 8)` exposed as `Flow<PlaybackNotice>`.

- [ ] **Step 5: Implement bounded per-occurrence policy and Media3 shuffle traversal**

Create `PlaybackFailurePolicy.kt`:

```kotlin
package app.yinyuehe.core.player.service

import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackError

internal data class QueueOccurrence(
  val index: Int,
  val token: PlaybackOccurrenceToken,
  val trackId: TrackId?,
)

internal sealed interface FailureDecision {
  data class Skip(
    val error: PlaybackError,
    val targetIndex: Int,
    val targetToken: PlaybackOccurrenceToken,
    val resumePlayback: Boolean,
  ) : FailureDecision
  data class Stop(val error: PlaybackError) : FailureDecision
}

internal class PlaybackFailurePolicy {
  private val attempted = linkedSetOf<PlaybackOccurrenceToken>()

  fun onFailure(
    error: PlaybackError,
    failed: QueueOccurrence,
    candidatesInPlaybackOrder: List<QueueOccurrence>,
    playIntent: Boolean,
  ): FailureDecision {
    attempted += failed.token
    val target = candidatesInPlaybackOrder.firstOrNull { it.token !in attempted }
    return target?.let { FailureDecision.Skip(error, it.index, it.token, playIntent) }
      ?: FailureDecision.Stop(error)
  }

  fun onStablePlayback(token: PlaybackOccurrenceToken) {
    if (token !in attempted) attempted.clear()
  }

  fun onPlaybackEnded() = attempted.clear()

  fun onUserRetry() = attempted.clear()
}
```

Create `Media3FailureNavigator.kt`:

```kotlin
package app.yinyuehe.core.player.service

import androidx.media3.common.C
import androidx.media3.common.Player
import app.yinyuehe.core.common.model.TrackId

internal fun Player.currentOccurrence(tokens: PlaybackOccurrenceTokens): QueueOccurrence? {
  val index = currentMediaItemIndex
  if (index !in 0 until mediaItemCount) return null
  val item = getMediaItemAt(index)
  val token = tokens.read(item) ?: return null
  return QueueOccurrence(index, token, item.mediaId.toTrackIdOrNull())
}

internal fun Player.failureCandidates(tokens: PlaybackOccurrenceTokens): List<QueueOccurrence> {
  if (mediaItemCount <= 1 || currentTimeline.isEmpty) return emptyList()
  val start = currentMediaItemIndex
  if (start !in 0 until mediaItemCount) return emptyList()
  val recoveryRepeatMode =
    if (repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
  return boundedCandidateIndices(start, mediaItemCount) { index ->
      currentTimeline.getNextWindowIndex(index, recoveryRepeatMode, shuffleModeEnabled)
    }
    .mapNotNull { index ->
      val item = getMediaItemAt(index)
      tokens.read(item)?.let { token ->
        QueueOccurrence(index, token, item.mediaId.toTrackIdOrNull())
      }
    }
}

internal fun boundedCandidateIndices(
  startIndex: Int,
  itemCount: Int,
  nextIndex: (Int) -> Int,
): List<Int> {
  val result = mutableListOf<Int>()
  var index = startIndex
  repeat((itemCount - 1).coerceAtLeast(0)) {
    index = nextIndex(index)
    if (index == C.INDEX_UNSET || index == startIndex || index in result) return result
    result += index
  }
  return result
}

private fun String.toTrackIdOrNull(): TrackId? = takeIf(String::isNotBlank)?.let(::TrackId)
```

Create `Media3FailureNavigatorTest.kt`:

```kotlin
package app.yinyuehe.core.player.service

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class Media3FailureNavigatorTest {
  @Test
  fun candidateTraversalKeepsShuffleOrderAndStopsBeforeASecondWrap() {
    val next = mapOf(0 to 2, 2 to 1, 1 to 0)

    assertEquals(
      listOf(2, 1),
      boundedCandidateIndices(startIndex = 0, itemCount = 3) { index ->
        next[index] ?: C.INDEX_UNSET
      },
    )
  }

  @Test
  fun noSuccessorStopsWithoutReturningTheFailedOccurrence() {
    assertEquals(
      emptyList<Int>(),
      boundedCandidateIndices(startIndex = 0, itemCount = 3) { C.INDEX_UNSET },
    )
  }
}
```

The production call passes `REPEAT_MODE_OFF` for both OFF and ONE, and passes `REPEAT_MODE_ALL` only for ALL. Therefore repeat-one cannot return the failed item, while repeat-all can wrap once and `boundedCandidateIndices` still caps traversal at `itemCount - 1` candidates.

- [ ] **Step 6: Coordinate skip, pause, notice, and one-second stable reset**

Before creating either production file in this step, create `StablePlaybackProgressTest.kt` and `PlaybackFailureCoordinatorTest.kt` exactly from the two test blocks below, then run:

```bash
./gradlew :core:player:testDebugUnitTest \
  --tests 'app.yinyuehe.core.player.service.StablePlaybackProgressTest' \
  --tests 'app.yinyuehe.core.player.service.PlaybackFailureCoordinatorTest'
```

Expected RED: Kotlin test compilation fails because `StablePlaybackProgress`, `StableProgressResult`, and `PlaybackFailureCoordinator` do not exist. Do not create the production files until this failure is captured.

After RED, create `StablePlaybackProgress.kt` so a seek or a late callback cannot be mistaken for one second of actual playback:

```kotlin
package app.yinyuehe.core.player.service

import kotlin.math.min

internal enum class StableProgressResult { TRACKING, STABLE, TARGET_CHANGED }

internal class StablePlaybackProgress(
  private val targetToken: PlaybackOccurrenceToken,
  private val stableThresholdMs: Long = 1_000,
  private val maxForwardStepMs: Long = 250,
) {
  private var previousPositionMs: Long? = null
  private var accumulatedMs: Long = 0

  fun sample(
    occurrence: QueueOccurrence?,
    positionMs: Long,
    isPlaying: Boolean,
  ): StableProgressResult {
    if (occurrence?.token != targetToken) return StableProgressResult.TARGET_CHANGED
    val previous = previousPositionMs
    previousPositionMs = positionMs
    if (isPlaying && previous != null) {
      accumulatedMs += min((positionMs - previous).coerceAtLeast(0), maxForwardStepMs)
    }
    return if (accumulatedMs >= stableThresholdMs) {
      StableProgressResult.STABLE
    } else {
      StableProgressResult.TRACKING
    }
  }
}
```

The `StablePlaybackProgressTest.kt` file already created for RED is:

```kotlin
package app.yinyuehe.core.player.service

import org.junit.Assert.assertEquals
import org.junit.Test

class StablePlaybackProgressTest {
  private val target = stableOccurrence(1, 2, "local:target")

  @Test
  fun requiresOneThousandMillisecondsOfBoundedForwardProgressOnTheTarget() {
    val progress = StablePlaybackProgress(target.token)

    assertEquals(StableProgressResult.TRACKING, progress.sample(target, 0, true))
    assertEquals(StableProgressResult.TRACKING, progress.sample(target, 250, true))
    assertEquals(StableProgressResult.TRACKING, progress.sample(target, 500, true))
    assertEquals(StableProgressResult.TRACKING, progress.sample(target, 750, true))
    assertEquals(StableProgressResult.TRACKING, progress.sample(target, 999, true))
    assertEquals(StableProgressResult.STABLE, progress.sample(target, 1_000, true))
  }

  @Test
  fun pauseSeekAndDifferentOccurrenceCannotCreateAFalseStableReset() {
    val progress = StablePlaybackProgress(target.token)
    assertEquals(StableProgressResult.TRACKING, progress.sample(target, 0, true))
    assertEquals(StableProgressResult.TRACKING, progress.sample(target, 10_000, false))
    assertEquals(
      StableProgressResult.TARGET_CHANGED,
      progress.sample(stableOccurrence(0, 1, "local:old"), 10_250, true),
    )
  }
}

private fun stableOccurrence(index: Int, token: Long, id: String) =
  QueueOccurrence(
    index,
    PlaybackOccurrenceToken(token),
    app.yinyuehe.core.common.model.TrackId(id),
  )
```

Create `PlaybackFailureCoordinator.kt` with this narrow adapter and implementation:

```kotlin
package app.yinyuehe.core.player.service

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import app.yinyuehe.core.common.playback.PlaybackNotice
import app.yinyuehe.core.player.playbackError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class PlaybackFailureCoordinator(
  private val player: Player,
  private val tokens: PlaybackOccurrenceTokens,
  private val policy: PlaybackFailurePolicy,
  private val scope: CoroutineScope,
  private val onNotice: (PlaybackNotice.TrackSkipped) -> Unit,
  private val sampleIntervalMs: Long = 250,
  private val onStablePlayback: (PlaybackOccurrenceToken) -> Unit = policy::onStablePlayback,
) {
  private var stabilityJob: Job? = null

  fun onPlayerError(exception: PlaybackException) {
    stabilityJob?.cancel()
    val failed = player.currentOccurrence(tokens) ?: return
    val error = playbackError(exception.errorCode, failed.trackId)
    when (
      val decision =
        policy.onFailure(error, failed, player.failureCandidates(tokens), player.playWhenReady)
    ) {
      is FailureDecision.Skip -> {
        player.seekToDefaultPosition(decision.targetIndex)
        player.prepare()
        if (decision.resumePlayback) player.play() else player.pause()
        onNotice(PlaybackNotice.TrackSkipped(decision.error))
        trackStableTarget(decision.targetToken)
      }
      is FailureDecision.Stop -> player.pause()
    }
  }

  fun onPlaybackEnded() {
    stabilityJob?.cancel()
    policy.onPlaybackEnded()
  }

  fun onUserRetry() {
    stabilityJob?.cancel()
    policy.onUserRetry()
  }

  fun close() {
    stabilityJob?.cancel()
    stabilityJob = null
  }

  internal fun trackStableTarget(targetToken: PlaybackOccurrenceToken) {
    val progress =
      StablePlaybackProgress(
        targetToken = targetToken,
        stableThresholdMs = STABLE_PLAYBACK_MS,
        maxForwardStepMs = sampleIntervalMs,
      )
    stabilityJob =
      scope.launch {
        while (isActive) {
          delay(sampleIntervalMs)
          when (
            progress.sample(
              occurrence = player.currentOccurrence(tokens),
              positionMs = player.currentPosition,
              isPlaying = player.isPlaying,
            )
          ) {
            StableProgressResult.TRACKING -> Unit
            StableProgressResult.TARGET_CHANGED -> return@launch
            StableProgressResult.STABLE -> {
              onStablePlayback(targetToken)
              return@launch
            }
          }
        }
      }
  }

  private companion object {
    const val STABLE_PLAYBACK_MS = 1_000L
  }
}
```

The `PlaybackFailureCoordinatorTest.kt` file already created for RED uses a one-occurrence proxy to verify terminal behavior without faking ExoPlayer internals:

```kotlin
package app.yinyuehe.core.player.service

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackFailureCoordinatorTest {
  @Test
  fun exhaustedSingleOccurrencePausesWithoutRemovingTheQueueOrSendingNotice() {
    val tokens = PlaybackOccurrenceTokens { 7 }
    val item =
      tokens.decorate(
        MediaItem.Builder().setMediaId("local:broken").setUri("content://media/broken").build()
      )
    val calls = mutableListOf<String>()
    val player =
      Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
        when (method.name) {
          "getMediaItemCount" -> 1
          "getCurrentMediaItemIndex" -> 0
          "getMediaItemAt" -> item
          "getPlayWhenReady" -> true
          "pause" -> {
            calls += "pause"
            null
          }
          "hashCode" -> 1
          "equals" -> false
          "toString" -> "FailurePlayer"
          else -> method.failureDefaultValue()
        }
      } as Player
    val notices = mutableListOf<app.yinyuehe.core.common.playback.PlaybackNotice.TrackSkipped>()
    val coordinator =
      PlaybackFailureCoordinator(
        player,
        tokens,
        PlaybackFailurePolicy(),
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        notices::add,
      )

    coordinator.onPlayerError(
      PlaybackException("not exposed", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
    )

    assertEquals(listOf("pause"), calls)
    assertEquals(1, player.mediaItemCount)
    assertEquals(emptyList<Any>(), notices)
    coordinator.close()
  }

  @Test
  fun oneSecondOfTargetProgressInvokesTheCoordinatorStableCallback() = runTest {
    val tokens = PlaybackOccurrenceTokens { 9 }
    val item =
      tokens.decorate(
        MediaItem.Builder().setMediaId("local:target").setUri("content://media/target").build()
      )
    val targetToken = checkNotNull(tokens.read(item))
    var positionMs = 0L
    val player =
      Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
        when (method.name) {
          "getMediaItemCount" -> 1
          "getCurrentMediaItemIndex" -> 0
          "getMediaItemAt" -> item
          "getCurrentPosition" -> positionMs
          "isPlaying" -> true
          "hashCode" -> 1
          "equals" -> false
          "toString" -> "StableTargetPlayer"
          else -> method.failureDefaultValue()
        }
      } as Player
    val stableTokens = mutableListOf<PlaybackOccurrenceToken>()
    val coordinator =
      PlaybackFailureCoordinator(
        player = player,
        tokens = tokens,
        policy = PlaybackFailurePolicy(),
        scope = backgroundScope,
        onNotice = {},
        sampleIntervalMs = 250,
        onStablePlayback = stableTokens::add,
      )

    coordinator.trackStableTarget(targetToken)
    advanceTimeBy(250)
    runCurrent()
    listOf(250L, 500L, 750L, 999L).forEach { sampledPosition ->
      positionMs = sampledPosition
      advanceTimeBy(250)
      runCurrent()
    }
    assertEquals(emptyList<PlaybackOccurrenceToken>(), stableTokens)
    positionMs = 1_000
    advanceTimeBy(250)
    runCurrent()

    assertEquals(listOf(targetToken), stableTokens)
    coordinator.close()
  }
}

private fun Method.failureDefaultValue(): Any? =
  when (returnType) {
    Boolean::class.javaPrimitiveType -> false
    Int::class.javaPrimitiveType -> 0
    Long::class.javaPrimitiveType -> 0L
    Float::class.javaPrimitiveType -> 0f
    Double::class.javaPrimitiveType -> 0.0
    else -> null
  }
```

Create `PlaybackFailurePlayerListener.kt`:

```kotlin
package app.yinyuehe.core.player.service

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player

internal class PlaybackFailurePlayerListener(
  private val coordinator: PlaybackFailureCoordinator,
) : Player.Listener {
  override fun onPlayerError(error: PlaybackException) = coordinator.onPlayerError(error)

  override fun onPlaybackStateChanged(playbackState: Int) {
    if (playbackState == Player.STATE_ENDED) coordinator.onPlaybackEnded()
  }
}
```

Add `private val onUserRetry: () -> Unit = {}` to the `PlaybackLibrarySessionCallback` constructor and add:

```kotlin
override fun onPlayerInteractionFinished(
  mediaSession: MediaSession,
  controller: MediaSession.ControllerInfo,
  playerCommands: Player.Commands,
) {
  val explicitRetry =
    playerCommands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS) ||
      playerCommands.contains(Player.COMMAND_SEEK_TO_MEDIA_ITEM) ||
      playerCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) ||
      playerCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) ||
      playerCommands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) ||
      playerCommands.contains(Player.COMMAND_PREPARE) ||
      (playerCommands.contains(Player.COMMAND_PLAY_PAUSE) &&
        mediaSession.player.playWhenReady)
  if (explicitRetry) onUserRetry()
}
```

- [ ] **Step 7: Wire notice delivery and typed terminal state**

Add these owned fields to `PlaybackService`:

```kotlin
private var failureCoordinator: PlaybackFailureCoordinator? = null
private var failureListener: Player.Listener? = null
```

Construct Task 8's Session callback with `onUserRetry = { failureCoordinator?.onUserRetry() }`. Immediately after Session construction, add:

```kotlin
val failureCoordinator =
  PlaybackFailureCoordinator(
    player = player,
    tokens = tokens,
    policy = PlaybackFailurePolicy(),
    scope = serviceScope,
    onNotice = { notice ->
      val encoded = PlaybackSessionProtocol.encode(notice)
      session
        ?.connectedControllers
        ?.filter { controller ->
          controller.packageName == packageName && controller.uid == applicationInfo.uid
        }
        ?.forEach { controller ->
          session?.sendCustomCommand(controller, encoded.command, encoded.extras)
        }
    },
  )
val failureListener = PlaybackFailurePlayerListener(failureCoordinator)
player.addListener(failureListener)
this.failureCoordinator = failureCoordinator
this.failureListener = failureListener
```

Before Session and Player release in `onDestroy()`, add:

```kotlin
failureListener?.let { listener -> player?.removeListener(listener) }
failureListener = null
failureCoordinator?.close()
failureCoordinator = null
```

Add `playerErrorCode: Int?` to `PlayerSnapshot` and capture `player.playerError?.errorCode`. In `toPlaybackState()`, compute the TrackId once before constructing state and use the same value for both fields:

```kotlin
val mappedCurrentTrackId = currentMediaId?.takeIf(String::isNotBlank)?.let(::TrackId)

return PlaybackState(
  connection = connection,
  currentTrackId = mappedCurrentTrackId,
  currentIndex = mappedCurrentIndex,
  isPlaying = isPlaying,
  toggleAction = toggleDecision.action,
  canTogglePlayPause = toggleDecision.canDispatch,
  positionMs = positionMs.coerceAtLeast(0),
  durationMs = durationMs.coerceAtLeast(0),
  queueTrackIds = indexedTrackIds.map { (_, trackId) -> trackId },
  shuffleEnabled = shuffleEnabled,
  repeatMode = repeatMode,
  playbackError = playerErrorCode?.let { code -> playbackError(code, mappedCurrentTrackId) },
  queuePersistenceLimited = queuePersistenceLimited,
  canSeek = canSeek,
  canPrevious = canPrevious,
  canNext = canNext,
  canSetRepeatMode = canSetRepeatMode,
  canSetShuffle = canSetShuffle,
  canChangeQueue = canChangeQueue && !queuePersistenceLimited,
  canSkipToQueueItem = canSkipToQueueItem,
)
```

Skipped errors are delivered only through `notices`; an exhausted queue keeps Media3's current `playerError`, so the typed terminal error remains in `PlaybackState`.

- [ ] **Step 8: Run all failure tests and the player/app gates**

```bash
./gradlew :core:player:testDebugUnitTest \
  --tests 'app.yinyuehe.core.player.PlaybackErrorMapperTest' \
  --tests 'app.yinyuehe.core.player.PlaybackSessionProtocolTest' \
  --tests 'app.yinyuehe.core.player.service.PlaybackFailurePolicyTest' \
  --tests 'app.yinyuehe.core.player.service.Media3FailureNavigatorTest' \
  --tests 'app.yinyuehe.core.player.service.PlaybackFailureCoordinatorTest' \
  --tests 'app.yinyuehe.core.player.service.StablePlaybackProgressTest'
./gradlew :core:player:testDebugUnitTest :core:player:lintDebug :core:player:assembleDebug
./gradlew :app:assembleDebug
```

Expected: PASS. Tests prove distinct duplicate occurrences, bounded exhaustion under repeat modes, shuffle order, stable-reset timing, explicit code mapping, sanitized Bundle payload, and non-replay notice delivery.

- [ ] **Step 9: Commit failure recovery atomically**

```bash
git add \
  core/player/src/main/kotlin/app/yinyuehe/core/player/PlaybackErrorMapper.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/PlaybackSessionProtocol.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackFailurePolicy.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/service/Media3FailureNavigator.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackFailureCoordinator.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/service/StablePlaybackProgress.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackFailurePlayerListener.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/PlaybackController.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/PlayerSnapshot.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/Media3PlaybackController.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackLibrarySessionCallback.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackService.kt \
  core/player/src/test/kotlin/app/yinyuehe/core/player/PlaybackErrorMapperTest.kt \
  core/player/src/test/kotlin/app/yinyuehe/core/player/PlaybackSessionProtocolTest.kt \
  core/player/src/test/kotlin/app/yinyuehe/core/player/service/PlaybackFailurePolicyTest.kt \
  core/player/src/test/kotlin/app/yinyuehe/core/player/service/Media3FailureNavigatorTest.kt \
  core/player/src/test/kotlin/app/yinyuehe/core/player/service/PlaybackFailureCoordinatorTest.kt \
  core/player/src/test/kotlin/app/yinyuehe/core/player/service/StablePlaybackProgressTest.kt \
  core/testing/src/main/kotlin/app/yinyuehe/core/testing/FakePlaybackController.kt \
  feature/library/src/test/kotlin/app/yinyuehe/feature/library/LibraryViewModelTest.kt
git commit -m "feat: recover from local playback failures"
```

- [ ] **Step 10: Run the mandatory two-stage review before Task 10**

Dispatch a fresh specification-compliance reviewer, then a fresh code-quality reviewer. Require explicit checks that no exception message/path/URI leaves Service, repeat-one cannot loop, repeat-all wraps at most once, failures are tracked by token, failed items remain in the queue, custom notices are app-only and non-replay, and every listener/Job has a symmetric close path. Resolve all Critical/Important findings and rerun the GREEN commands.

---

## Task 10: Single-flight MediaController connection with bounded retry and stale-callback isolation

**Files:**
- Create: `core/player/src/main/kotlin/app/yinyuehe/core/player/ControllerConnectionCoordinator.kt`
- Modify: `core/player/src/main/kotlin/app/yinyuehe/core/player/Media3PlaybackController.kt`
- Delete: `core/player/src/main/kotlin/app/yinyuehe/core/player/ListenableFutureFailure.kt`
- Delete test: `core/player/src/test/kotlin/app/yinyuehe/core/player/ListenableFutureFailureTest.kt`
- Test: `core/player/src/test/kotlin/app/yinyuehe/core/player/ControllerConnectionCoordinatorTest.kt`
- Modify test: `core/player/src/test/kotlin/app/yinyuehe/core/player/PlayerSnapshotTest.kt`

**Interfaces:**
- Consumes: Media3 1.10.1 `MediaController.Builder.buildAsync()`, `MediaController.releaseFuture(Future)`, Task 9's per-build custom-command listener, `PlaybackConnectionError.RETRIES_EXHAUSTED`, and existing request analytics.
- Produces:
  ```kotlin
  internal fun interface ControllerConnector<T : Any> {
    fun connect(onDisconnected: (T) -> Unit): ListenableFuture<T>
  }

  internal sealed interface ControllerConnectionUpdate<out T> {
    data object Connecting : ControllerConnectionUpdate<Nothing>
    data class Connected<T : Any>(val controller: T) : ControllerConnectionUpdate<T>
    data object Exhausted : ControllerConnectionUpdate<Nothing>
  }

  internal class ControllerConnectionCoordinator<T : Any> {
    fun start()
    suspend fun awaitConnected(startNewRoundIfExhausted: Boolean): T?
    fun close()
  }
  ```
- `Media3PlaybackController.play()` waits for the current whole round; all other transport commands use only the currently connected controller and are never queued.

- [ ] **Step 1: Add failing virtual-time tests for the exact attempt budget and stale callbacks**

Create `ControllerConnectionCoordinatorTest.kt`:

```kotlin
package app.yinyuehe.core.player

import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ControllerConnectionCoordinatorTest {
  @Test
  fun oneImmediateBuildAndFourExactDelaysThenExhausted() = runTest {
    val connector = RecordingConnector<String>()
    val updates = mutableListOf<ControllerConnectionUpdate<String>>()
    val coordinator = coordinator(connector, updates, backgroundScope)

    coordinator.start()
    assertEquals(1, connector.futures.size)
    connector.futures[0].setException(IllegalStateException("attempt-1"))
    runCurrent()
    advanceTimeBy(249)
    assertEquals(1, connector.futures.size)
    advanceTimeBy(1)
    runCurrent()
    assertEquals(2, connector.futures.size)

    connector.futures[1].setException(IllegalStateException("attempt-2"))
    runCurrent()
    advanceTimeBy(500)
    runCurrent()
    assertEquals(3, connector.futures.size)
    connector.futures[2].setException(IllegalStateException("attempt-3"))
    runCurrent()
    advanceTimeBy(1_000)
    runCurrent()
    assertEquals(4, connector.futures.size)
    connector.futures[3].setException(IllegalStateException("attempt-4"))
    runCurrent()
    advanceTimeBy(2_000)
    runCurrent()
    assertEquals(5, connector.futures.size)
    connector.futures[4].setException(IllegalStateException("attempt-5"))
    runCurrent()

    assertEquals(1, updates.count { it is ControllerConnectionUpdate.Connecting })
    assertEquals(ControllerConnectionUpdate.Exhausted, updates.last())
    assertNull(coordinator.awaitConnected(startNewRoundIfExhausted = false))

    val restarted = async { coordinator.awaitConnected(startNewRoundIfExhausted = true) }
    runCurrent()
    assertEquals(6, connector.futures.size)
    connector.futures[5].set("recovered")
    runCurrent()
    assertEquals("recovered", restarted.await())
    assertEquals(2, updates.count { it is ControllerConnectionUpdate.Connecting })
  }

  @Test
  fun successResetsRound_disconnectBuildsImmediately_andStaleDisconnectIsIgnored() = runTest {
    val connector = RecordingConnector<Any>()
    val released = mutableListOf<Any>()
    val coordinator =
      ControllerConnectionCoordinator(
        scope = backgroundScope,
        callbackExecutor = MoreExecutors.directExecutor(),
        connector = connector,
        releaseConnected = released::add,
        releasePending = {},
        onUpdate = {},
      )
    coordinator.start()
    val first = Any()
    connector.futures.single().set(first)
    runCurrent()
    assertSame(first, coordinator.awaitConnected(false))

    connector.disconnectCallbacks.single().invoke(first)
    runCurrent()
    assertEquals(2, connector.futures.size)
    val second = Any()
    connector.futures[1].set(second)
    runCurrent()
    connector.disconnectCallbacks[0].invoke(first)
    runCurrent()

    assertSame(second, coordinator.awaitConnected(false))
    assertEquals(listOf(first), released)
    coordinator.close()
    assertNull(coordinator.awaitConnected(false))
    assertEquals(listOf(first, second), released)
  }

  @Test
  fun closeReleasesPendingFutureAndLateSuccessCannotPublish() = runTest {
    val connector = RecordingConnector<String>()
    val releasedFutures = mutableListOf<ListenableFuture<String>>()
    val updates = mutableListOf<ControllerConnectionUpdate<String>>()
    val coordinator =
      ControllerConnectionCoordinator(
        scope = backgroundScope,
        callbackExecutor = MoreExecutors.directExecutor(),
        connector = connector,
        releaseConnected = {},
        releasePending = releasedFutures::add,
        onUpdate = updates::add,
      )
    coordinator.start()
    val pending = connector.futures.single()

    coordinator.close()
    pending.set("late")
    runCurrent()

    assertEquals(listOf(pending), releasedFutures)
    assertFalse(updates.any { it is ControllerConnectionUpdate.Connected })
  }

  private fun coordinator(
    connector: RecordingConnector<String>,
    updates: MutableList<ControllerConnectionUpdate<String>>,
    scope: kotlinx.coroutines.CoroutineScope,
  ) =
    ControllerConnectionCoordinator(
      scope = scope,
      callbackExecutor = MoreExecutors.directExecutor(),
      connector = connector,
      releaseConnected = {},
      releasePending = {},
      retryDelaysMs = listOf(250, 500, 1_000, 2_000),
      onUpdate = updates::add,
    )
}

private class RecordingConnector<T : Any> : ControllerConnector<T> {
  val futures = mutableListOf<SettableFuture<T>>()
  val disconnectCallbacks = mutableListOf<(T) -> Unit>()

  override fun connect(onDisconnected: (T) -> Unit): ListenableFuture<T> =
    SettableFuture.create<T>().also {
      futures += it
      disconnectCallbacks += onDisconnected
    }
}
```

- [ ] **Step 2: Run the coordinator test to prove RED**

```bash
./gradlew :core:player:testDebugUnitTest \
  --tests 'app.yinyuehe.core.player.ControllerConnectionCoordinatorTest'
```

Expected: FAIL during Kotlin test compilation because the coordinator, connector, and update types do not exist.

- [ ] **Step 3: Implement the generic single-flight coordinator**

Create `ControllerConnectionCoordinator.kt`:

```kotlin
package app.yinyuehe.core.player

import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun interface ControllerConnector<T : Any> {
  fun connect(onDisconnected: (T) -> Unit): ListenableFuture<T>
}

internal sealed interface ControllerConnectionUpdate<out T> {
  data object Connecting : ControllerConnectionUpdate<Nothing>
  data class Connected<T : Any>(val controller: T) : ControllerConnectionUpdate<T>
  data object Exhausted : ControllerConnectionUpdate<Nothing>
}

internal class ControllerConnectionCoordinator<T : Any>(
  private val scope: CoroutineScope,
  private val callbackExecutor: Executor,
  private val connector: ControllerConnector<T>,
  private val releaseConnected: (T) -> Unit,
  private val releasePending: (ListenableFuture<T>) -> Unit,
  private val retryDelaysMs: List<Long> = listOf(250, 500, 1_000, 2_000),
  private val onUpdate: (ControllerConnectionUpdate<T>) -> Unit,
) {
  private val releasedFutures =
    Collections.newSetFromMap(IdentityHashMap<ListenableFuture<T>, Boolean>())
  private var generation = 0L
  private var connectedGeneration = -1L
  private var currentFuture: ListenableFuture<T>? = null
  private var currentController: T? = null
  private var retryJob: Job? = null
  private var retryIndex = 0
  private var started = false
  private var exhausted = false
  private var closed = false
  private var roundResult = completedNullResult<T>()

  fun start() {
    if (started || closed) return
    started = true
    beginRound()
  }

  suspend fun awaitConnected(startNewRoundIfExhausted: Boolean): T? {
    currentController?.let { return it }
    if (!started) start()
    if (exhausted && startNewRoundIfExhausted && !closed) beginRound()
    return roundResult.await()
  }

  fun close() {
    if (closed) return
    closed = true
    generation += 1
    retryJob?.cancel()
    retryJob = null
    currentFuture?.let(::releaseFutureOnce)
    currentFuture = null
    currentController?.let(releaseConnected)
    currentController = null
    roundResult.complete(null)
    roundResult = completedNullResult()
  }

  private fun beginRound() {
    retryJob?.cancel()
    retryIndex = 0
    exhausted = false
    roundResult = CompletableDeferred()
    onUpdate(ControllerConnectionUpdate.Connecting)
    buildAttempt()
  }

  private fun buildAttempt() {
    if (closed || currentFuture != null || currentController != null) return
    val attemptGeneration = ++generation
    val future =
      try {
        connector.connect { controller ->
          scope.launch { handleDisconnected(attemptGeneration, controller) }
        }
      } catch (error: Exception) {
        Futures.immediateFailedFuture(error)
      }
    currentFuture = future
    future.addListener(
      { scope.launch { handleCompletion(attemptGeneration, future) } },
      callbackExecutor,
    )
  }

  private fun handleCompletion(attemptGeneration: Long, future: ListenableFuture<T>) {
    if (closed || attemptGeneration != generation || currentFuture !== future) {
      releaseFutureOnce(future)
      return
    }
    currentFuture = null
    val controller = runCatching { future.get() }.getOrNull()
    if (controller != null) {
      currentController = controller
      connectedGeneration = attemptGeneration
      retryIndex = 0
      roundResult.complete(controller)
      onUpdate(ControllerConnectionUpdate.Connected(controller))
      return
    }
    if (retryIndex >= retryDelaysMs.size) {
      exhausted = true
      roundResult.complete(null)
      onUpdate(ControllerConnectionUpdate.Exhausted)
      return
    }
    val retryDelayMs = retryDelaysMs[retryIndex++]
    retryJob =
      scope.launch {
        delay(retryDelayMs)
        retryJob = null
        if (!closed && currentFuture == null && currentController == null) buildAttempt()
      }
  }

  private fun handleDisconnected(attemptGeneration: Long, controller: T) {
    if (
      closed ||
        attemptGeneration != connectedGeneration ||
        currentController !== controller
    ) {
      return
    }
    currentController = null
    releaseConnected(controller)
    beginRound()
  }

  private fun releaseFutureOnce(future: ListenableFuture<T>) {
    if (releasedFutures.add(future)) releasePending(future)
  }
}

private fun <T : Any> completedNullResult(): CompletableDeferred<T?> =
  CompletableDeferred<T?>().apply { complete(null) }
```

- [ ] **Step 4: Replace ad-hoc controller rebuilds with the coordinator**

In `Media3PlaybackController`, delete `controllerFuture`, `observeConnection`, `rebuildController`, `selectControllerFuture`, `handleConnectionFailure`, and the shared `controllerListener`. Add this coordinator:

```kotlin
private val connectionCoordinator =
  ControllerConnectionCoordinator(
    scope = applicationScope,
    callbackExecutor = mainExecutor,
    connector = ControllerConnector { onDisconnected -> buildController(onDisconnected) },
    releaseConnected = { mediaController ->
      mediaController.removeListener(playerListener)
      if (controller === mediaController) controller = null
      stopPositionTicker()
      mediaController.release()
    },
    releasePending = { future -> MediaController.releaseFuture(future) },
    onUpdate = ::onConnectionUpdate,
  )

init {
  connectionCoordinator.start()
}
```

Replace `buildController()` with a per-attempt listener so every callback carries that attempt's identity:

```kotlin
private fun buildController(
  onDisconnected: (MediaController) -> Unit,
): ListenableFuture<MediaController> =
  MediaController.Builder(
      context,
      SessionToken(context, ComponentName(context, PlaybackService::class.java)),
    )
    .setApplicationLooper(Looper.getMainLooper())
    .setListener(
      object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) = onDisconnected(controller)

        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
          if (this@Media3PlaybackController.controller === controller) publishSnapshot(controller)
        }

        override fun onCustomCommand(
          controller: MediaController,
          command: SessionCommand,
          args: Bundle,
        ): ListenableFuture<SessionResult> {
          val notice = PlaybackSessionProtocol.decode(command, args)
          return if (
            notice != null && this@Media3PlaybackController.controller === controller
          ) {
            _notices.tryEmit(notice)
            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
          } else {
            Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
          }
        }
      }
    )
    .buildAsync()
```

Add the exact state transition handler:

```kotlin
private fun onConnectionUpdate(update: ControllerConnectionUpdate<MediaController>) {
  when (update) {
    ControllerConnectionUpdate.Connecting -> {
      controller = null
      stopPositionTicker()
      _state.value = PlaybackState(connection = PlaybackConnection.CONNECTING)
    }
    is ControllerConnectionUpdate.Connected -> {
      controller = update.controller
      update.controller.addListener(playerListener)
      publishSnapshot(update.controller)
    }
    ControllerConnectionUpdate.Exhausted -> {
      requestAnalytics.onPlaybackFailure()
      controller = null
      stopPositionTicker()
      _state.value =
        PlaybackState(
          connection = PlaybackConnection.DISCONNECTED,
          connectionError = PlaybackConnectionError.RETRIES_EXHAUSTED,
        )
    }
  }
}
```

Change `play()` to wait for the current whole round without canceling shared connection work:

```kotlin
val mediaController =
  connectionCoordinator.awaitConnected(startNewRoundIfExhausted = true)
    ?: return@withContext false
```

Keep all existing input validation, analytics, and `PlaybackCommandDispatcher.playQueue()` handling around that replacement. `dispatch` must continue to read only `controller`; it must not wait or enqueue clicks.

- [ ] **Step 5: Add state assertions for exhausted and reconnected rounds**

Add these internal factories to `PlaybackState.kt` and use them in the `Connecting` and `Exhausted` branches above:

```kotlin
internal fun connectingPlaybackState(): PlaybackState =
  PlaybackState(connection = PlaybackConnection.CONNECTING)

internal fun exhaustedPlaybackState(): PlaybackState =
  PlaybackState(
    connection = PlaybackConnection.DISCONNECTED,
    connectionError = PlaybackConnectionError.RETRIES_EXHAUSTED,
  )
```

Add to `PlayerSnapshotTest.kt`:

```kotlin
@Test
fun exhaustedConnectionDisablesTransportAndConnectedSnapshotClearsConnectionError() {
  val exhausted = exhaustedPlaybackState()
  assertEquals(PlaybackConnection.DISCONNECTED, exhausted.connection)
  assertEquals(PlaybackConnectionError.RETRIES_EXHAUSTED, exhausted.connectionError)
  assertFalse(exhausted.canTogglePlayPause)
  assertFalse(exhausted.canChangeQueue)

  val connected = snapshot(
    playWhenReady = false,
    isEnded = false,
    canPlayPause = true,
    canSeekToDefaultPosition = true,
  ).toPlaybackState()
  assertEquals(PlaybackConnection.CONNECTED, connected.connection)
  assertNull(connected.connectionError)
}
```

The connected assertion must use a new PlayerSnapshot; never copy the cached pre-disconnect state.

- [ ] **Step 6: Remove obsolete future probing and run focused tests**

```bash
git rm core/player/src/main/kotlin/app/yinyuehe/core/player/ListenableFutureFailure.kt \
  core/player/src/test/kotlin/app/yinyuehe/core/player/ListenableFutureFailureTest.kt
./gradlew :core:player:testDebugUnitTest \
  --tests 'app.yinyuehe.core.player.ControllerConnectionCoordinatorTest' \
  --tests 'app.yinyuehe.core.player.PlayerSnapshotTest'
```

Expected: PASS. The test scheduler observes exactly five builds per failed round, a single active future and retry Job, immediate reconnect after a live disconnection, stale callback isolation, and pending-future release.

- [ ] **Step 7: Run module and app regressions**

```bash
./gradlew :core:player:testDebugUnitTest :feature:library:testDebugUnitTest
./gradlew :core:player:lintDebug :core:player:assembleDebug :app:assembleDebug
```

Expected: PASS. Task 9 custom notices and Session extras still work with the per-attempt listener, and no disconnected transport command is replayed after reconnect.

- [ ] **Step 8: Commit bounded reconnect atomically**

```bash
git add \
  core/player/src/main/kotlin/app/yinyuehe/core/player/ControllerConnectionCoordinator.kt \
  core/player/src/main/kotlin/app/yinyuehe/core/player/Media3PlaybackController.kt \
  core/player/src/test/kotlin/app/yinyuehe/core/player/ControllerConnectionCoordinatorTest.kt \
  core/player/src/test/kotlin/app/yinyuehe/core/player/PlayerSnapshotTest.kt
git commit -m "feat: add bounded media controller reconnect"
```

- [ ] **Step 9: Run the mandatory two-stage review before UI/device tasks**

Dispatch a fresh specification-compliance reviewer, then a fresh code-quality reviewer. Require explicit checks for the exact `immediate + 250 + 500 + 1000 + 2000ms` attempt schedule, one in-flight Future, one retry Job, generation checks on success/failure/disconnect, `MediaController.releaseFuture` for pending or stale futures, listener removal before connected-controller release, no unbounded retry, new-round triggering only from a disconnected live controller or a new `play()` after exhaustion, and clearing `connectionError` only from a real connected snapshot. Resolve all Critical/Important findings and rerun Steps 6–7.

---

## Task 11: Wire repeat, shuffle, queue movement, typed errors, and one-shot notices through UDF

**Files:**

- Create: `feature/library/src/main/kotlin/app/yinyuehe/feature/library/MusicBoxEffect.kt`
- Modify: `feature/library/src/main/kotlin/app/yinyuehe/feature/library/MusicBoxAction.kt`
- Modify: `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryViewModel.kt`
- Modify: `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryScreen.kt`
- Modify: `feature/library/src/main/kotlin/app/yinyuehe/feature/library/HomeScreen.kt`
- Modify: `feature/library/src/main/kotlin/app/yinyuehe/feature/library/PlayerScreen.kt`
- Modify: `feature/library/src/main/res/values/strings.xml`
- Modify: `core/testing/src/main/kotlin/app/yinyuehe/core/testing/FakePlaybackController.kt`
- Modify: `feature/library/src/test/kotlin/app/yinyuehe/feature/library/LibraryViewModelTest.kt`
- Modify: `feature/library/src/test/kotlin/app/yinyuehe/feature/library/PlayerInteractionStateTest.kt`
- Modify: `feature/library/src/androidTest/kotlin/app/yinyuehe/feature/library/LibraryScreenTest.kt`

**Consumes:**

```kotlin
interface PlaybackController {
  val state: StateFlow<PlaybackState>
  val notices: Flow<PlaybackNotice>
  fun setRepeatMode(mode: PlaybackRepeatMode)
  fun setShuffleEnabled(enabled: Boolean)
  fun moveQueueItem(fromIndex: Int, toIndex: Int)
}
```

**Produces:**

```kotlin
enum class QueueMoveDirection { UP, DOWN }

sealed interface MusicBoxEffect {
  data class TrackSkipped(val errorType: PlaybackErrorType) : MusicBoxEffect
}

sealed interface MusicBoxAction {
  data object CycleRepeatMode : MusicBoxAction
  data object ToggleShuffle : MusicBoxAction
  data class MoveQueueItem(
    val index: Int,
    val direction: QueueMoveDirection,
  ) : MusicBoxAction
}
```

- [ ] Add ViewModel tests that prove repeat cycles `OFF -> ALL -> ONE -> OFF`, shuffle is derived from the latest callback state, move uses occurrence indices, limited mode rejects add/remove/move, and a skipped notice becomes exactly one effect.

```kotlin
@Test
fun playbackModeAndMoveActions_delegateWithoutOptimisticState() = runTest {
  val controller = RecordingPlaybackController()
  val viewModel = viewModel(FakeTrackRepository(listOf(track("one"))), controller)
  controller.emit(
    PlaybackState(
      queueTrackIds = listOf(TrackId("one"), TrackId("two"), TrackId("three")),
      repeatMode = PlaybackRepeatMode.OFF,
      shuffleEnabled = false,
      canSetRepeatMode = true,
      canSetShuffle = true,
      canChangeQueue = true,
    )
  )
  advanceUntilIdle()

  viewModel.onAction(MusicBoxAction.CycleRepeatMode)
  viewModel.onAction(MusicBoxAction.ToggleShuffle)
  viewModel.onAction(MusicBoxAction.MoveQueueItem(2, QueueMoveDirection.UP))

  assertEquals(listOf(PlaybackRepeatMode.ALL), controller.repeatUpdates)
  assertEquals(listOf(true), controller.shuffleUpdates)
  assertEquals(listOf(2 to 1), controller.moveRequests)
  assertEquals(PlaybackRepeatMode.OFF, viewModel.uiState.value.playback.repeatMode)

  controller.emit(viewModel.uiState.value.playback.copy(repeatMode = PlaybackRepeatMode.ALL))
  advanceUntilIdle()
  viewModel.onAction(MusicBoxAction.CycleRepeatMode)
  controller.emit(viewModel.uiState.value.playback.copy(repeatMode = PlaybackRepeatMode.ONE))
  advanceUntilIdle()
  viewModel.onAction(MusicBoxAction.CycleRepeatMode)

  assertEquals(
    listOf(PlaybackRepeatMode.ALL, PlaybackRepeatMode.ONE, PlaybackRepeatMode.OFF),
    controller.repeatUpdates,
  )
}

@Test
fun unavailableModeCapabilitiesRejectDirectViewModelActions() = runTest {
  val controller = RecordingPlaybackController()
  val viewModel = viewModel(FakeTrackRepository(listOf(track("one"))), controller)
  controller.emit(
    PlaybackState(
      repeatMode = PlaybackRepeatMode.OFF,
      shuffleEnabled = false,
      canSetRepeatMode = false,
      canSetShuffle = false,
    )
  )
  advanceUntilIdle()

  viewModel.onAction(MusicBoxAction.CycleRepeatMode)
  viewModel.onAction(MusicBoxAction.ToggleShuffle)

  assertTrue(controller.repeatUpdates.isEmpty())
  assertTrue(controller.shuffleUpdates.isEmpty())
}

@Test
fun limitedQueueRejectsIncrementalEditsButAllowsFullPlayReplacement() = runTest {
  val one = track("one")
  val controller = RecordingPlaybackController()
  val viewModel = viewModel(FakeTrackRepository(listOf(one)), controller)
  controller.emit(
    PlaybackState(
      queueTrackIds = listOf(one.id),
      queuePersistenceLimited = true,
      canChangeQueue = true,
    )
  )
  advanceUntilIdle()

  viewModel.onAction(MusicBoxAction.AddToQueue(one.id))
  viewModel.onAction(MusicBoxAction.RemoveQueueItem(0))
  viewModel.onAction(MusicBoxAction.MoveQueueItem(0, QueueMoveDirection.DOWN))
  viewModel.onAction(MusicBoxAction.PlayAll(TrackCollection.LIBRARY))
  advanceUntilIdle()

  assertTrue(controller.queuedTracks.isEmpty())
  assertTrue(controller.removedQueueIndices.isEmpty())
  assertTrue(controller.moveRequests.isEmpty())
  assertEquals(1, controller.playRequests.size)
}

@Test
fun trackSkippedNotice_isForwardedOnceAsNonStateEffect() = runTest {
  val controller = RecordingPlaybackController()
  val viewModel = viewModel(FakeTrackRepository(listOf(track("one"))), controller)
  advanceUntilIdle()
  val received = async(start = CoroutineStart.UNDISPATCHED) { viewModel.effects.first() }

  controller.emitNotice(
    PlaybackNotice.TrackSkipped(
      PlaybackError(PlaybackErrorType.DECODER, 4003, TrackId("one"))
    )
  )

  assertEquals(MusicBoxEffect.TrackSkipped(PlaybackErrorType.DECODER), received.await())
  assertFalse(viewModel.uiState.value.toString().contains("4003"))
}
```

- [ ] Run the new ViewModel tests and confirm RED because the actions, effects, controller methods, and limited gate are absent.

```bash
./gradlew :feature:library:testDebugUnitTest \
  --tests '*LibraryViewModelTest.playbackModeAndMoveActions_delegateWithoutOptimisticState' \
  --tests '*LibraryViewModelTest.unavailableModeCapabilitiesRejectDirectViewModelActions' \
  --tests '*LibraryViewModelTest.trackSkippedNotice_isForwardedOnceAsNonStateEffect' \
  --stacktrace
```

Expected: compilation fails on the new UDF surface, or the focused assertions fail before the implementation exists.

- [ ] Add the actions/effect and route them through `LibraryViewModel`; use a buffered channel for one-consumer, non-state effects and never write a second copy of playback state.

```kotlin
private val effectChannel = Channel<MusicBoxEffect>(Channel.BUFFERED)
val effects: Flow<MusicBoxEffect> = effectChannel.receiveAsFlow()

init {
  viewModelScope.launch {
    playbackController.notices.collect { notice ->
      when (notice) {
        is PlaybackNotice.TrackSkipped ->
          effectChannel.send(MusicBoxEffect.TrackSkipped(notice.error.type))
      }
    }
  }
}

private fun cycleRepeatMode() {
  val playback = uiState.value.playback
  if (!playback.canSetRepeatMode) return
  val next =
    when (playback.repeatMode) {
      PlaybackRepeatMode.OFF -> PlaybackRepeatMode.ALL
      PlaybackRepeatMode.ALL -> PlaybackRepeatMode.ONE
      PlaybackRepeatMode.ONE -> PlaybackRepeatMode.OFF
    }
  playbackController.setRepeatMode(next)
}

private fun toggleShuffle() {
  val playback = uiState.value.playback
  if (!playback.canSetShuffle) return
  playbackController.setShuffleEnabled(!playback.shuffleEnabled)
}

private fun moveQueueItem(index: Int, direction: QueueMoveDirection) {
  val playback = uiState.value.playback
  if (playback.queuePersistenceLimited || !playback.canChangeQueue) return
  val target = if (direction == QueueMoveDirection.UP) index - 1 else index + 1
  if (index in playback.queueTrackIds.indices && target in playback.queueTrackIds.indices) {
    playbackController.moveQueueItem(index, target)
  }
}

private fun canEditQueue(): Boolean =
  uiState.value.playback.let { playback ->
    playback.canChangeQueue && !playback.queuePersistenceLimited
  }

private fun addToQueue(trackId: TrackId) {
  if (!canEditQueue()) return
  uiState.value.trackCatalog[trackId]?.let(playbackController::addToQueue)
}

private fun removeQueueItem(index: Int) {
  if (canEditQueue()) playbackController.removeQueueItem(index)
}
```

Route `MusicBoxAction.ToggleShuffle` only through `toggleShuffle()`; do not call the Controller directly from the action `when` branch.

- [ ] Add Compose tests for actual callback state, all capability gates, first/last movement bounds, 48 dp targets, typed terminal errors, partial-restore guidance, and snackbar delivery.

```kotlin
@Test
fun limitedQueue_disablesEditsButKeepsTransportAndFullReplacementAvailable() {
  val one = track("one")
  val playback =
    PlaybackState(
      connection = PlaybackConnection.CONNECTED,
      currentTrackId = one.id,
      currentIndex = 0,
      queueTrackIds = listOf(one.id, one.id),
      queuePersistenceLimited = true,
      canTogglePlayPause = true,
      canChangeQueue = true,
      canSkipToQueueItem = true,
    )
  composeRule.setContent {
    YinYueHeTheme {
      LibraryScreen(
        screenState(one).copy(
          activeDestination = MusicBoxDestination.PLAYER,
          playback = playback,
        ),
        onAction = {},
      )
    }
  }

  composeRule.onNodeWithTag("player-toggle").assertIsEnabled()
  composeRule.onNodeWithTag("player-queue-remove-0").assertIsNotEnabled()
  composeRule.onNodeWithTag("player-queue-move-down-0").assertIsNotEnabled()
  composeRule.onNodeWithText("重新播放全部可替代受保护的旧队列").assertIsDisplayed()
}

@Test
fun movementControls_haveAccessibleLabelsAndMinimumTouchTargets() {
  val one = track("one")
  composeRule.setContent {
    YinYueHeTheme {
      LibraryScreen(
        screenState(one).copy(
          activeDestination = MusicBoxDestination.PLAYER,
          playback =
            PlaybackState(
              queueTrackIds = listOf(one.id, one.id),
              canChangeQueue = true,
            ),
        ),
        onAction = {},
      )
    }
  }

  composeRule.onAllNodesWithContentDescription("下移one").assertCountEquals(2)
  composeRule.onNodeWithTag("player-queue-move-down-0").assertHeightIsAtLeast(48.dp)
  composeRule.onNodeWithTag("player-queue-move-down-0").assertWidthIsAtLeast(48.dp)
  composeRule.onNodeWithTag("player-queue-move-up-0").assertIsNotEnabled()
  composeRule.onNodeWithTag("player-queue-move-down-1").assertIsNotEnabled()
}
```

- [ ] Run the new Compose tests and confirm RED before changing the composables.

```bash
ANDROID_SERIAL="${ANDROID_SERIAL:?set an API 36 device serial}" \
  ./gradlew :feature:library:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.yinyuehe.feature.library.LibraryScreenTest \
  --stacktrace
```

Expected: the new test class compiles, installs, runs on API 36, and fails its new assertions on missing tags, semantics, labels, or capability behavior. `assembleDebugAndroidTest` alone is not acceptable RED evidence because it cannot execute Compose assertions.

- [ ] Implement the snackbar host, mode controls, terminal error text, limited guidance, and index-based movement controls. Keep every click target at least 48 dp and do not render Media3 messages, URI, or path.

```kotlin
val queueEditable = playback.canChangeQueue && !playback.queuePersistenceLimited
TextButton(
  onClick = { onAction(MusicBoxAction.CycleRepeatMode) },
  enabled = playback.canSetRepeatMode,
  modifier =
    Modifier.sizeIn(minWidth = MinimumTouchTarget, minHeight = MinimumTouchTarget)
      .testTag("player-repeat"),
) {
  Text("重复：${playback.repeatMode.name}")
}
TextButton(
  onClick = { onAction(MusicBoxAction.ToggleShuffle) },
  enabled = playback.canSetShuffle,
  modifier =
    Modifier.sizeIn(minWidth = MinimumTouchTarget, minHeight = MinimumTouchTarget)
      .testTag("player-shuffle"),
) {
  Text(if (playback.shuffleEnabled) "随机：开" else "随机：关")
}
TextButton(
  onClick = {
    onAction(MusicBoxAction.MoveQueueItem(index, QueueMoveDirection.UP))
  },
  enabled = queueEditable && index > 0,
  modifier =
    Modifier.sizeIn(minWidth = MinimumTouchTarget, minHeight = MinimumTouchTarget)
      .semantics { contentDescription = "上移$title" }
      .testTag("player-queue-move-up-$index"),
) {
  Text("上移")
}

playback.playbackError?.let { error ->
  Text(
    text = stringResource(error.type.terminalMessageResource()),
    color = MaterialTheme.colorScheme.error,
  )
}
playback.connectionError?.let {
  Text(stringResource(R.string.playback_connection_retries_exhausted))
}
if (playback.queuePersistenceLimited) {
  Text(stringResource(R.string.playback_queue_partial_restore_guidance))
}
```

Set Home's add button to `enabled = state.playback.canChangeQueue && !state.playback.queuePersistenceLimited`; set Player remove/up/down buttons to `queueEditable`, and set jump buttons to `playback.canSkipToQueueItem`. Full `PlayTrack`/`PlayAll`/`PlayRandom` actions remain enabled because they are the explicit full replacement path.

```kotlin
val snackbarHostState = remember { SnackbarHostState() }
val context = LocalContext.current
LaunchedEffect(viewModel) {
  viewModel.effects.collect { effect ->
    val message =
      when (effect) {
        is MusicBoxEffect.TrackSkipped ->
          context.getString(effect.errorType.skippedMessageResource())
      }
    snackbarHostState.showSnackbar(message)
  }
}
LibraryScreenContent(
  state = state,
  snackbarHostState = snackbarHostState,
  onAction = { action ->
    viewModel.onAction(action)
    if (action == MusicBoxAction.RequestAudioPermission) onRequestAudioPermission()
  },
)
```

- [ ] Run feature JVM, instrumentation compilation, then API 36 Compose instrumentation.

```bash
./gradlew :feature:library:testDebugUnitTest \
  :feature:library:assembleDebugAndroidTest --stacktrace
ANDROID_SERIAL="${ANDROID_SERIAL:?set an API 36 device serial}" \
  ./gradlew :feature:library:connectedDebugAndroidTest --stacktrace
```

Expected: focused tests pass, the instrumentation APK compiles, and `LibraryScreenTest` passes on API 36 with no skipped UI assertions.

- [ ] Commit only the UDF/UI slice.

```bash
git add \
  feature/library/src/main/kotlin/app/yinyuehe/feature/library/MusicBoxEffect.kt \
  feature/library/src/main/kotlin/app/yinyuehe/feature/library/MusicBoxAction.kt \
  feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryViewModel.kt \
  feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryScreen.kt \
  feature/library/src/main/kotlin/app/yinyuehe/feature/library/HomeScreen.kt \
  feature/library/src/main/kotlin/app/yinyuehe/feature/library/PlayerScreen.kt \
  feature/library/src/main/res/values/strings.xml \
  core/testing/src/main/kotlin/app/yinyuehe/core/testing/FakePlaybackController.kt \
  feature/library/src/test/kotlin/app/yinyuehe/feature/library/LibraryViewModelTest.kt \
  feature/library/src/test/kotlin/app/yinyuehe/feature/library/PlayerInteractionStateTest.kt \
  feature/library/src/androidTest/kotlin/app/yinyuehe/feature/library/LibraryScreenTest.kt
git commit -m "feat: expose recoverable playback controls in Compose"
```

- [ ] Give the commit to a fresh specification-compliance reviewer, fix every Critical/Important mismatch, then give the corrected commit to a fresh code-quality reviewer before Task 12.

## Task 12: Prove process recovery and safety boundaries on an API 36 device

**Files:**

- Create: `app/src/debug/kotlin/app/yinyuehe/M3ADeviceEntryPoint.kt`
- Create: `app/src/debug/kotlin/app/yinyuehe/M3ARestoreBarrierModule.kt`
- Create: `app/src/debug/kotlin/app/yinyuehe/M3AControllerProbeActivity.kt`
- Create: `app/src/debug/AndroidManifest.xml`
- Create: `app/src/androidTest/kotlin/app/yinyuehe/M3ADeviceProtocol.kt`
- Create: `app/src/androidTest/kotlin/app/yinyuehe/M3ADeviceAudioFixture.kt`
- Create: `app/src/androidTest/kotlin/app/yinyuehe/M3AMediaControllerHarness.kt`
- Create: `app/src/androidTest/kotlin/app/yinyuehe/M3APositionRecoveryDeviceTest.kt`
- Create: `app/src/androidTest/kotlin/app/yinyuehe/M3AQueueRecoveryDeviceTest.kt`
- Create: `app/src/androidTest/kotlin/app/yinyuehe/M3APermissionRecoveryDeviceTest.kt`
- Create: `app/src/androidTest/kotlin/app/yinyuehe/M3ASnapshotSafetyDeviceTest.kt`
- Create: `app/src/androidTest/kotlin/app/yinyuehe/M3AReconnectDeviceTest.kt`
- Create: `scripts/m3a-device/lib.sh`
- Create: `scripts/run-m3a-device-acceptance.sh`
- Modify: `app/build.gradle.kts`
- Modify: `.github/workflows/ci.yml`

**Consumes:** `PlaybackService`, its debug-only `Set<PlaybackRestoreBarrier>`, the production `MediaController` session contract, and `files/datastore/playback_snapshot.pb`.

**Produces:** host-driven, independently asserted evidence cases named `position`, `queue`, `permission`, `snapshot-safety`, and `reconnect`. The normal `connectedDebugAndroidTest` invocation must skip host-handshake methods unless `m3aHostDriven=true`; the dedicated script treats any skip as failure.

- [ ] Add the Media3/test dependencies to `app` androidTest, compile instrumentation in CI, and keep the barrier implementation in the debug source set only.

```kotlin
androidTestImplementation(libs.androidx.test.core)
androidTestImplementation(libs.androidx.test.runner)
androidTestImplementation(libs.kotlinx.coroutines.guava)
androidTestImplementation(libs.media3.common)
androidTestImplementation(libs.media3.session)
```

```yaml
- name: Build instrumentation APKs
  run: ./gradlew :app:assembleDebugAndroidTest :feature:library:assembleDebugAndroidTest :core:data:assembleDebugAndroidTest --stacktrace
```

- [ ] Write the runtime WAV fixture test first. It must create a 35-second, mono, 8 kHz, 16-bit PCM WAV through MediaStore, scan it through the production scanner, assert reported duration is at least 30 seconds, and delete it in cleanup.

```kotlin
private const val SAMPLE_RATE = 8_000
private const val DURATION_SECONDS = 35
private const val PCM_BYTES = SAMPLE_RATE * DURATION_SECONDS * 2

private fun OutputStream.writeWavHeader() {
  write("RIFF".encodeToByteArray())
  writeLeInt(36 + PCM_BYTES)
  write("WAVEfmt ".encodeToByteArray())
  writeLeInt(16)
  writeLeShort(1)
  writeLeShort(1)
  writeLeInt(SAMPLE_RATE)
  writeLeInt(SAMPLE_RATE * 2)
  writeLeShort(2)
  writeLeShort(16)
  write("data".encodeToByteArray())
  writeLeInt(PCM_BYTES)
}

private fun OutputStream.writeLeInt(value: Int) {
  write(byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte()))
}

private fun OutputStream.writeLeShort(value: Int) {
  write(byteArrayOf(value.toByte(), (value ushr 8).toByte()))
}
```

- [ ] Run the fixture test before implementation and confirm RED, then implement MediaStore insertion with `IS_PENDING`, fixed display name `yinyuehe_m3a_35s.wav`, bounded duration polling, and production scan. The standalone fixture test deletes in `finally`. Host-driven multi-phase cases intentionally retain the WAV across phase boundaries and transfer cleanup ownership to the host trap, which invokes a dedicated cleanup instrumentation method by fixed display name; phase one must not delete the file before phase two restores it.

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
ANDROID_SERIAL="${ANDROID_SERIAL:?set an API 36 device serial}" \
  ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.yinyuehe.M3APositionRecoveryDeviceTest#fixture_isAtLeastThirtySeconds \
  --stacktrace
```

Expected RED: the focused class or fixture is absent. Expected GREEN after the minimal implementation: one passed test and a MediaStore duration of at least 30,000 ms.

- [ ] Write phase-one and phase-two position tests. Phase one seeks beyond 18 seconds, plays long enough to cross a 5-second persistence tick, writes the Controller's actual position every 100 ms to `files/m3a-device/actual_position_ms`, and exits without destroying the queue. Phase two accepts the host-decoded position and asserts an independently restored paused state.

```kotlin
@Test
fun phaseTwo_restoresCapturedPositionPaused() = runTest {
  requireHostDriven()
  val expected = instrumentation.arguments.getString("expectedPersistedPositionMs")!!.toLong()
  val controller = harness.connect()
  val restored = controller.awaitState { it.mediaItemCount == 1 && it.currentPosition >= 15_000 }

  assertTrue(abs(restored.currentPosition - expected) <= 1_000)
  assertFalse(restored.playWhenReady)
  assertFalse(restored.isPlaying)
  assertFalse(harness.sessionReportsActivePlayback())
  assertTrue(harness.mediaNotificationIsAbsentOrOffersPlayNotPause())
  M3ADeviceProtocol.writeResult(
    "position-phase-two",
    mapOf(
      "persistedPositionMs" to expected,
      "restoredPositionMs" to restored.currentPosition,
      "restoreDeltaMs" to abs(restored.currentPosition - expected),
      "playWhenReady" to restored.playWhenReady,
      "isPlaying" to restored.isPlaying,
      "sessionReportsActivePlayback" to harness.sessionReportsActivePlayback(),
      "notificationIsAbsentOrOffersPlayNotPause" to
        harness.mediaNotificationIsAbsentOrOffersPlayNotPause(),
    ),
  )
}
```

Before calling it, assert `POST_NOTIFICATIONS` is granted. Implement `mediaNotificationIsAbsentOrOffersPlayNotPause()` with `NotificationManager.activeNotifications`: select this application's MediaStyle notification; absence is valid for a paused restored session, while a present notification must contain `Notification.Action.SEMANTIC_ACTION_PLAY` and no `SEMANTIC_ACTION_PAUSE`. This avoids locale-dependent title parsing while independently checking the notification surface.

- [ ] Implement the host position case so it reads the real private Proto with `run-as`, decodes it against the repository `.proto` using a result-directory copy of the Gradle-downloaded protoc 4.32.1 binary, then captures the independent actual position, performs the 6-second assertion, and immediately force-stops the process. Pass the decoded persisted position into a separate phase-two process. Phase two must also inspect the MediaSession/notification state and prove it is not actively playing.

```bash
run_position_case() {
  instrument 'app.yinyuehe.M3APositionRecoveryDeviceTest#phaseOne_persistsLongTrackPosition'
  "$ADB" exec-out run-as app.yinyuehe cat files/datastore/playback_snapshot.pb \
    > "$RESULT_DIR/playback_snapshot.pb"
  "$PROTOC" \
    --proto_path=core/data/src/main/proto \
    --decode=app.yinyuehe.core.data.playback.proto.PlaybackSnapshotProto \
    core/data/src/main/proto/playback_snapshot.proto \
    < "$RESULT_DIR/playback_snapshot.pb" \
    > "$RESULT_DIR/playback_snapshot.txt"
  persisted_position_ms="$(awk '$1 == "position_ms:" { print $2 }' "$RESULT_DIR/playback_snapshot.txt")"
  actual_position_ms="$($ADB exec-out run-as app.yinyuehe cat files/m3a-device/actual_position_ms | tr -d '\r\n')"
  position_delta_ms="$((actual_position_ms > persisted_position_ms ? actual_position_ms - persisted_position_ms : persisted_position_ms - actual_position_ms))"
  test "$persisted_position_ms" -ge 15000
  test "$position_delta_ms" -le 6000
  printf 'M3A_POSITION_CAPTURE actual=%s persisted=%s delta=%s\n' \
    "$actual_position_ms" "$persisted_position_ms" "$position_delta_ms"
  "$ADB" shell am force-stop app.yinyuehe
  instrument_with_arg \
    'app.yinyuehe.M3APositionRecoveryDeviceTest#phaseTwo_restoresCapturedPositionPaused' \
    expectedPersistedPositionMs "$persisted_position_ms"
}
```

- [ ] Add the duplicate-Demo queue case: persist `[morning, morning, night]`, current index 1, repeat `ALL`, shuffle enabled; force-stop; then assert exact order, duplicate occurrences, index, repeat, shuffle, and paused state.

- [ ] Add the permission case with three independent branches. Each branch explicitly grants and verifies `READ_MEDIA_AUDIO` before setup; the preserve branch then revokes and verifies it before cold restore, byte-compares the original Proto after add/remove/move attempts, re-grants it in `finally`, and proves the local occurrence returns. The replacement branch proves only an own-app full `setMediaItems` replacement changes those protected bytes. The permanent-missing branch first persists `Demo A / generated local WAV / Demo B`, deletes the MediaStore WAV while permission remains granted, then invokes the production `LibraryScanner.scan()` through `M3ADeviceEntryPoint` (never the DAO). Wait for scan success, assert its unavailable count is at least one, and assert the production library no longer contains that TrackId before force-stop/restart. Finally prove only the missing occurrence is removed, the surviving order/index are repaired, and the normalized Proto contains exactly the two Demo IDs. Use a generated local WAV and never use a Demo URI for either local-file claim. The host trap restores both audio and notification permissions even after failure so no branch contaminates the next case.

- [ ] Add snapshot-safety cases using the two-phase debug barrier file protocol. `BEFORE_READ` blocks before DataStore/Room work and proves an initial empty callback plus early Service destruction cannot overwrite v1 bytes. `BEFORE_APPLY` blocks only after read+resolve and proves a new full queue supersedes the now-stale plan. Each phase has distinct hold/blocked/release files and the test must observe its blocked marker before issuing the competing action. Also inject corrupt bytes and a schema-99 Proto with the Service stopped: corruption must recover empty and then accept/persist a new full queue, while schema 99 remains byte-identical through cold start and destruction.

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object M3ARestoreBarrierModule {
  @Provides
  @IntoSet
  fun provideFileBarrier(@ApplicationContext context: Context): PlaybackRestoreBarrier =
    PlaybackRestoreBarrier { phase ->
      withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "m3a-device")
        val key =
          when (phase) {
            PlaybackRestoreBarrierPhase.BEFORE_READ -> "before-read"
            PlaybackRestoreBarrierPhase.BEFORE_APPLY -> "before-apply"
          }
        if (!File(directory, "hold-$key").exists()) return@withContext
        File(directory, "$key-blocked").writeText("blocked")
        withTimeout(10_000) {
          while (!File(directory, "release-$key").exists()) delay(25)
        }
      }
    }
}
```

- [ ] Add the Service restart/reconnect case in `M3AReconnectDeviceTest.kt` using a debug-only remote-process client. Declare `M3AControllerProbeActivity` as exported only in the debug manifest with `android:process=":m3a_controller"`; inject the production `PlaybackController`, collect its state, and atomically write connection-edge markers under `files/m3a-device/`. Increment `connectedGeneration` only on a `non-CONNECTED -> CONNECTED` edge, record at least one intervening `CONNECTED -> non-CONNECTED` edge, and include the Controller object's process-local identity in both connected records. Ordinary Player-state callbacks while already connected must not increment the generation. The host starts that Activity, records both PIDs, and kills only the main `app.yinyuehe` PID with `run-as app.yinyuehe kill -9`; the remote probe PID must remain unchanged. Assert the same production Controller instance observes disconnect/reconnect and reaches a new real `CONNECTED` snapshot within one bounded round. Mutate the new queue after reconnect and prove the pre-kill snapshot does not overwrite it. The remote debug component must not inject `PlaybackSnapshotStore` or start another DataStore. Generation-specific stale callbacks remain covered by Task 10's deterministic JVM tests.

```xml
<activity
  android:name=".M3AControllerProbeActivity"
  android:excludeFromRecents="true"
  android:exported="true"
  android:process=":m3a_controller"
  android:theme="@android:style/Theme.Material.NoActionBar" />
```

The host-side kill boundary is exact and must fail if either PID is missing or identical:

```bash
"$ADB" shell am start -W -n app.yinyuehe/.M3AControllerProbeActivity
main_pid="$($ADB shell pidof app.yinyuehe | tr -d '\r\n')"
probe_pid="$($ADB shell pidof app.yinyuehe:m3a_controller | tr -d '\r\n')"
test -n "$main_pid" && test -n "$probe_pid" && test "$main_pid" != "$probe_pid"
wait_for_probe_marker 'connectedGeneration=1'
"$ADB" shell run-as app.yinyuehe kill -9 "$main_pid"
wait_for_probe_marker 'connectedGeneration=2'
test "$($ADB shell pidof app.yinyuehe:m3a_controller | tr -d '\r\n')" = "$probe_pid"
instrument_no_restart 'app.yinyuehe.M3AReconnectDeviceTest#probeReconnectedWithoutStaleState'
test "$($ADB shell pidof app.yinyuehe:m3a_controller | tr -d '\r\n')" = "$probe_pid"
```

- [ ] Make the host script enforce API 36, one device serial, JDK 17, exact Maven artifact `com.google.protobuf:protoc:4.32.1` (whose compiler banner is `libprotoc 32.1`), instrumentation `OK` status, no skipped host-driven test, result-directory hashes, and cleanup traps.

```bash
test -n "${ANDROID_SERIAL:-}"
adb_serial() { "${ANDROID_HOME:?}/platform-tools/adb" -s "$ANDROID_SERIAL" "$@"; }
ADB=adb_serial
test "$($ADB get-state)" = "device"
test "$("${ANDROID_HOME}/platform-tools/adb" devices | awk -v serial="$ANDROID_SERIAL" '$1 == serial && $2 == "device" { count++ } END { print count + 0 }')" = "1"
DEVICE_USER_ID="$($ADB shell am get-current-user | tr -d '\r\n')"
test -n "$DEVICE_USER_ID"

grant_and_verify_runtime_permissions() {
  $ADB shell pm grant --user "$DEVICE_USER_ID" app.yinyuehe android.permission.READ_MEDIA_AUDIO
  $ADB shell pm grant --user "$DEVICE_USER_ID" app.yinyuehe android.permission.POST_NOTIFICATIONS
  for permission in \
    android.permission.READ_MEDIA_AUDIO \
    android.permission.POST_NOTIFICATIONS; do
    $ADB shell dumpsys package app.yinyuehe \
      | tr -d '\r' \
      | awk -v expected="$permission:" \
          '$1 == expected && /granted=true/ { found=1 } END { exit !found }'
  done
}

instrument() {
  test_name="$1"
  shift
  output_file="$RESULT_DIR/$(printf '%s' "$test_name" | tr '#.' '__').instrumentation.txt"
  set +e
  output="$($ADB shell am instrument -w -r \
    -e m3aHostDriven true -e class "$test_name" "$@" \
    app.yinyuehe.test/androidx.test.runner.AndroidJUnitRunner 2>&1)"
  status=$?
  set -e
  printf '%s\n' "$output" | tee "$output_file"
  test "$status" -eq 0
  printf '%s\n' "$output" | grep -Eq '^OK \([1-9][0-9]* tests?\)$'
  ! printf '%s\n' "$output" | grep -Eiq 'skipped|assumption|FAILURES|INSTRUMENTATION_FAILED'
}

instrument_no_restart() {
  test_name="$1"
  shift
  output_file="$RESULT_DIR/$(printf '%s' "$test_name" | tr '#.' '__').no-restart.txt"
  set +e
  output="$($ADB shell am instrument --no-restart -w -r \
    -e m3aHostDriven true -e class "$test_name" "$@" \
    app.yinyuehe.test/androidx.test.runner.AndroidJUnitRunner 2>&1)"
  status=$?
  set -e
  printf '%s\n' "$output" | tee "$output_file"
  test "$status" -eq 0
  printf '%s\n' "$output" | grep -Eq '^OK \([1-9][0-9]* tests?\)$'
  ! printf '%s\n' "$output" | grep -Eiq 'skipped|assumption|FAILURES|INSTRUMENTATION_FAILED'
}

api_level="$($ADB shell getprop ro.build.version.sdk | tr -d '\r')"
test "$api_level" = "36"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
install_debug_apks
grant_and_verify_runtime_permissions
PROTOC_SOURCE="$(find "$HOME/.gradle/caches/modules-2/files-2.1/com.google.protobuf/protoc/4.32.1" -type f -name 'protoc-4.32.1-*' | head -n 1)"
test -f "$PROTOC_SOURCE"
cp "$PROTOC_SOURCE" "$RESULT_DIR/protoc-4.32.1"
chmod 700 "$RESULT_DIR/protoc-4.32.1"
PROTOC="$RESULT_DIR/protoc-4.32.1"
test "$($PROTOC --version)" = "libprotoc 32.1"
trap 'grant_and_verify_runtime_permissions || true; cleanup_m3a_fixture' EXIT INT TERM
run_position_case
run_queue_case
run_permission_case
run_snapshot_safety_case
run_reconnect_case
```

- [ ] Run every device case on API 36. Do not continue to documentation if either position tolerance, byte-preservation check, instrumentation result, or cleanup fails.

```bash
ANDROID_SERIAL="${ANDROID_SERIAL:?set an API 36 device serial}" \
  ./scripts/run-m3a-device-acceptance.sh
```

Expected: all five case markers pass; `M3A_POSITION_CAPTURE` reports delta at most 6000 ms, `position-phase-two` reports delta at most 1000 ms and both playback booleans false, permission/high-version SHA-256 pairs match where required, and the script exits 0.

- [ ] Commit the reproducible device suite and CI compile gate. Raw private Proto/WAV/device files stay under ignored `build/m3a-device/` and are not committed.

```bash
git add \
  app/src/debug/kotlin/app/yinyuehe/M3ADeviceEntryPoint.kt \
  app/src/debug/kotlin/app/yinyuehe/M3ARestoreBarrierModule.kt \
  app/src/debug/kotlin/app/yinyuehe/M3AControllerProbeActivity.kt \
  app/src/debug/AndroidManifest.xml \
  app/src/androidTest/kotlin/app/yinyuehe/M3ADeviceProtocol.kt \
  app/src/androidTest/kotlin/app/yinyuehe/M3ADeviceAudioFixture.kt \
  app/src/androidTest/kotlin/app/yinyuehe/M3AMediaControllerHarness.kt \
  app/src/androidTest/kotlin/app/yinyuehe/M3APositionRecoveryDeviceTest.kt \
  app/src/androidTest/kotlin/app/yinyuehe/M3AQueueRecoveryDeviceTest.kt \
  app/src/androidTest/kotlin/app/yinyuehe/M3APermissionRecoveryDeviceTest.kt \
  app/src/androidTest/kotlin/app/yinyuehe/M3ASnapshotSafetyDeviceTest.kt \
  app/src/androidTest/kotlin/app/yinyuehe/M3AReconnectDeviceTest.kt \
  scripts/m3a-device/lib.sh scripts/run-m3a-device-acceptance.sh \
  app/build.gradle.kts .github/workflows/ci.yml
git commit -m "test: add API 36 playback recovery acceptance"
```

- [ ] Give the commit and captured output to a fresh specification-compliance reviewer, fix every Critical/Important gap, then obtain a fresh code-quality review before Task 13.

## Task 13: Run the release gate and publish evidence-backed M3-A documentation

**Files:**

- Create: `verification/m3a-acceptance-scenarios.md`
- Create: `verification/result-2026-07-15-m3a.md`
- Modify: `README.md`
- Verify unchanged: `verification/acceptance-scenarios.md`
- Verify unchanged: `verification/result-2026-07-14.md`

**Consumes:** the exact committed implementation SHA, Gradle/JUnit/lint output, five host-driven API 36 result bundles, decoded Proto text, hashes, and device metadata.

**Produces:** a separate M3-A matrix with ten executed automated rows plus the two honest physical-device pending rows; a reproducible result record; README claims that are no broader than those artifacts.

- [ ] Run every focused module gate from a clean worktree and capture the exact exit status before the full build.

```bash
git status --short
./gradlew clean --stacktrace
./gradlew :core:common:test \
  :core:data:testDebugUnitTest :core:data:lintDebug \
  :core:player:testDebugUnitTest :core:player:lintDebug \
  :feature:library:testDebugUnitTest \
  :app:assembleDebugAndroidTest \
  :feature:library:assembleDebugAndroidTest \
  :core:data:assembleDebugAndroidTest \
  --stacktrace
```

Expected: exit 0. `clean` ensures later JUnit/lint counts cannot include filtered or stale reports from task development. Treat skipped unit tests, compilation warnings introduced by this branch, or a non-empty unexpected diff as a failure to investigate.

- [ ] Run the complete repository gate exactly as CI does.

```bash
./gradlew test testDebugUnitTest lintDebug assembleDebug --stacktrace
```

Expected: `BUILD SUCCESSFUL`, every JUnit XML suite has zero failures/errors, and each generated lint text report says `No issues found.`

- [ ] Re-run the complete API 36 host suite against the exact pre-documentation SHA and preserve its sanitized result directory under `build/m3a-device/` for writing the record.

```bash
PRE_EVIDENCE_SHA="$(git rev-parse HEAD)"
ANDROID_SERIAL="${ANDROID_SERIAL:?set an API 36 device serial}" \
  ./scripts/run-m3a-device-acceptance.sh
git diff --exit-code "$PRE_EVIDENCE_SHA"
```

Expected: exit 0 and no source mutation. Do not substitute the 3.2–4.0 second Demo tracks for the 35-second WAV position case.

- [ ] Count actual test executions and lint reports from generated artifacts; record the distinction between test executions and unique test methods.

```bash
python3 - <<'PY'
import pathlib
import xml.etree.ElementTree as ET

reports = list(pathlib.Path('.').glob('**/build/test-results/**/TEST-*.xml'))
tests = failures = errors = skipped = 0
unique_methods = set()
for report in reports:
    root = ET.parse(report).getroot()
    tests += int(root.attrib.get('tests', 0))
    failures += int(root.attrib.get('failures', 0))
    errors += int(root.attrib.get('errors', 0))
    skipped += int(root.attrib.get('skipped', 0))
    for case in root.findall('.//testcase'):
        unique_methods.add((case.attrib.get('classname', ''), case.attrib.get('name', '')))
assert failures == 0 and errors == 0 and skipped == 0, (failures, errors, skipped)
print(
    f'junit_xml={len(reports)} executions={tests} '
    f'unique_testcase_names={len(unique_methods)} skipped={skipped}'
)

lint_reports = list(pathlib.Path('.').glob('**/build/reports/lint-results-debug.txt'))
assert lint_reports
for report in lint_reports:
    assert 'No issues found.' in report.read_text(), report
print(f'lint_reports={len(lint_reports)} all_no_issues=true')
PY
```

- [ ] Create `m3a-acceptance-scenarios.md` with these fixed IDs and only evidence-backed statuses: `M3A01` Proto/DataStore, `M3A02` resolver, `M3A03` restore/gate, `M3A04` long-track paused position, `M3A05` restore races, `M3A06` permission preservation/replacement, `M3A07` repeat/shuffle/move, `M3A08` typed bounded failure recovery, `M3A09` reconnect, `M3A10` full build/device gate, `M3A11` physical AudioFocus, and `M3A12` physical noisy-route removal. The first ten must be `AUTOMATED_PASS` only after their commands pass; the last two remain `PENDING_DEVICE` unless separately executed on physical hardware.

- [ ] Create `result-2026-07-15-m3a.md` with the exact pre-evidence SHA, host/JDK/Gradle/SDK/device/ABI, command lines, wall-clock window, JUnit/lint counts, protoc artifact version `4.32.1` plus compiler banner `libprotoc 32.1`, 35-second fixture duration, both independent position triples, queue values, permission and schema-99 hashes, reconnect attempt/result markers, limitations, and links to the new matrix. Never include a local content URI, filesystem path from the device, or exception message.

- [ ] Update README architecture/capability/testing sections only with statements proven in the new record: Service ownership, Proto snapshot fields, no-autoplay restoration, repeat/shuffle/occurrence movement, bounded error skipping, bounded reconnect, and the two position tolerances. Keep AudioFocus/noisy physical validation explicitly pending.

- [ ] Validate the new matrix shape and prove the old evidence files were not rewritten.

```bash
python3 - <<'PY'
import pathlib
import re

path = pathlib.Path('verification/m3a-acceptance-scenarios.md')
rows = re.findall(
    r'^\| (M3A\d{2}) \|.*\| (AUTOMATED_PASS|MANUAL_PASS|PENDING_DEVICE|FAIL) \|',
    path.read_text(),
    re.MULTILINE,
)
assert [row[0] for row in rows] == [f'M3A{i:02d}' for i in range(1, 13)], rows
assert [row[1] for row in rows[:10]] == ['AUTOMATED_PASS'] * 10, rows[:10]
assert [row[1] for row in rows[10:]] == ['PENDING_DEVICE'] * 2, rows[10:]
assert all(row[1] != 'FAIL' for row in rows)
print('M3-A matrix: 10 automated pass, 2 physical-device pending, 0 fail')
PY
git diff --exit-code origin/main -- \
  verification/acceptance-scenarios.md \
  verification/result-2026-07-14.md
rg -n 'PENDING_DEVICE|AudioFocus|noisy|6000|1000|35' \
  README.md verification/m3a-acceptance-scenarios.md verification/result-2026-07-15-m3a.md
git diff --check
```

Expected: matrix validation prints the exact count, the old evidence diff is empty, required boundary/tolerance terms are present, and `git diff --check` exits 0.

- [ ] Commit the evidence-backed documentation.

```bash
git add README.md verification/m3a-acceptance-scenarios.md verification/result-2026-07-15-m3a.md
git commit -m "docs: record M3-A playback recovery verification"
```

- [ ] Run the complete Gradle gate once more after the documentation commit, run `git diff --check`, and confirm the worktree is clean.

```bash
./gradlew test testDebugUnitTest lintDebug assembleDebug \
  :app:assembleDebugAndroidTest \
  :feature:library:assembleDebugAndroidTest \
  :core:data:assembleDebugAndroidTest \
  --stacktrace
git diff --check
git status --short --branch
```

Expected: all commands exit 0 and status is clean on `feature/m3a-playback-recovery`.

- [ ] Give the complete branch to a fresh specification-compliance reviewer and then a fresh code-quality reviewer. Resolve every Critical/Important finding and rerun affected tests plus the full gate. If any review fix changes production code, device-test code, the host script, Proto/schema behavior, or an evidence assertion, the old API 36 bundle and `PRE_EVIDENCE_SHA` are invalid: rerun the complete five-case device suite, update the SHA/hashes/results/matrix/README, commit those updates, and repeat both final reviews. Only then invoke `superpowers:finishing-a-development-branch` for the PR/merge decision.
