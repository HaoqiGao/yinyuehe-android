# M2-B MediaStore Scanner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Import real device music into the M2-A Room cache with stable identities, cross-version MediaStore queries, per-volume atomic commits, safe incremental scanning, and single-flight orchestration.

**Architecture:** `local.mediastore` owns Android queries and converts complete Cursor reads into platform-independent snapshots before any database transaction begins. `scan` selects a per-volume strategy, commits through `RoomScanStore`, and exposes typed state/results. Public scan values live in `core:common`; production permission binding and UI triggers remain deferred to M2-C.

**Tech Stack:** Kotlin 2.0.20, Android API 26–36, Room 2.8.4, Coroutines/Flow 1.9.0, Robolectric 4.16, JUnit 4.

**Required local shell preflight (before every Gradle block):**

```bash
export JAVA_HOME="${YINYUEHE_JAVA17_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null)}"
export PATH="$JAVA_HOME/bin:$PATH"
test -x "$JAVA_HOME/bin/java"
test "$("$JAVA_HOME/bin/java" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/{print $2; exit}')" = "17"
./gradlew --version | rg 'Launcher JVM: 17|JVM: 17'
```

Expected: all four commands exit 0. If JDK 17 is absent, stop and install/point `YINYUEHE_JAVA17_HOME` at Temurin/Corretto 17; do not fall back to the workstation's JDK 25. CI remains pinned to Temurin 17.

## Global Constraints

- Start only after M2-A is squash-merged and `main` CI is green. Create a fresh worktree with `superpowers:using-git-worktrees`; branch: `feature/m2b-mediastore-scanner`.
- Do not change the Room v1 schema. Extend DAOs only; if an implementation discovers a required column change, stop and amend the approved specification before creating a real v2 migration.
- Never hold a Room transaction while iterating a Cursor. Query one volume completely, close its Cursors, then open one short transaction for that volume.
- Every successful incremental scan still performs a complete lightweight ID reconciliation for that volume.
- API 30+ checkpoints advance only to the generation upper bound captured before the query. API 26–29 never use a global maximum timestamp as a watermark.
- Demo rows never enter Room. No permission dialog, Manifest change, ViewModel, Compose, playback, playlist, notification, Paging, FTS, or new module belongs in this PR.
- Stable media ID format is frozen as `local:v1:<base64url-volume>:<decimal-row-id>`; Base64 URL encoding has no padding.
- Do not log paths, titles, artists, albums, Content URIs, or exception messages. Logs, if any, contain only typed codes, volume counts, and aggregate statistics.
- Each task uses a fresh implementation subagent, then specification-compliance and code-quality reviewers. Resolve all Critical/Important findings before the next task.
- Design source of truth: `docs/superpowers/specs/2026-07-13-m2-local-library-design.md`.

## File Structure

### Modify

- `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/dao/TrackDao.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/dao/ScanCheckpointDao.kt`

### Create

- `core/common/src/main/kotlin/app/yinyuehe/core/common/model/LibraryScan.kt`
- `core/common/src/test/kotlin/app/yinyuehe/core/common/model/LibraryScanTest.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/local/mediastore/MediaStoreGateway.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/local/mediastore/AndroidMediaStoreGateway.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/local/mediastore/MediaStoreCursorMapper.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/local/mediastore/MediaStoreStableId.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/local/mediastore/MediaStoreMetadata.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/scan/LibraryScanner.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/scan/ScanAccessPrecondition.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/scan/ScanStore.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/scan/RoomScanStore.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/scan/VolumeScanner.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/scan/DefaultLibraryScanner.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/scan/ScanPrimitives.kt`
- `core/data/src/test/kotlin/app/yinyuehe/core/data/local/mediastore/MediaStoreStableIdTest.kt`
- `core/data/src/test/kotlin/app/yinyuehe/core/data/local/mediastore/MediaStoreMetadataTest.kt`
- `core/data/src/test/kotlin/app/yinyuehe/core/data/local/mediastore/AndroidMediaStoreGatewayTest.kt`
- `core/data/src/test/kotlin/app/yinyuehe/core/data/scan/RoomScanStoreIntegrationTest.kt`
- `core/data/src/test/kotlin/app/yinyuehe/core/data/scan/MediaStoreScannerRoomIntegrationTest.kt`
- `core/data/src/test/kotlin/app/yinyuehe/core/data/scan/VolumeScannerTest.kt`
- `core/data/src/test/kotlin/app/yinyuehe/core/data/scan/DefaultLibraryScannerTest.kt`
- `core/data/src/test/kotlin/app/yinyuehe/core/data/scan/testing/FakeMediaStoreGateway.kt`
- `core/data/src/test/kotlin/app/yinyuehe/core/data/scan/testing/FakeScanStore.kt`
- `core/data/src/test/kotlin/app/yinyuehe/core/data/scan/testing/ScanTestFixtures.kt`

---

### Task 1: Freeze scan contracts, stable IDs, and deterministic metadata

**Files:** public scan model, MediaStore gateway/value files, and their focused tests listed above.

**Interfaces:**

```kotlin
sealed interface LibraryScanRequest {
  data object Automatic : LibraryScanRequest
  data object UserFullRescan : LibraryScanRequest
}

enum class ScanMode { AUTOMATIC, FULL }
enum class ScanCancellationReason { USER, ACCESS_LOST }
enum class ScanOutcome { SUCCESS, PARTIAL_SUCCESS, FAILED, CANCELLED }
enum class ScanStrategy { FULL, GENERATION_WINDOW, LEGACY_FINGERPRINT }

data class ScanStatistics(
  val discovered: Int = 0,
  val inserted: Int = 0,
  val updated: Int = 0,
  val unavailable: Int = 0,
  val unchanged: Int = 0,
)

data class ScanProgress(
  val volumeName: String?,
  val volumeIndex: Int,
  val volumeCount: Int,
  val processed: Int,
  val total: Int?,
  val accumulated: ScanStatistics,
)

sealed interface ScanFailure {
  val code: String
  data object AccessLost : ScanFailure { override val code = "scan_access_lost" }
  data object VolumeEnumeration : ScanFailure { override val code = "scan_volume_enumeration" }
  data class Query(val volumeName: String) : ScanFailure { override val code = "scan_query" }
  data class Database(val volumeName: String) : ScanFailure { override val code = "scan_database" }
  data class VersionChanged(val volumeName: String) : ScanFailure {
    override val code = "scan_version_changed"
  }
  data class Unknown(val volumeName: String?) : ScanFailure { override val code = "scan_unknown" }
}

sealed interface VolumeScanResult {
  val volumeName: String
  data class Committed(
    override val volumeName: String,
    val strategy: ScanStrategy,
    val statistics: ScanStatistics,
  ) : VolumeScanResult
  data class Unmounted(
    override val volumeName: String,
    val unavailable: Int,
  ) : VolumeScanResult
  data class DeferredForFullRescan(
    override val volumeName: String,
    val failure: ScanFailure.VersionChanged,
  ) : VolumeScanResult
  data class Failed(
    override val volumeName: String,
    val failure: ScanFailure,
  ) : VolumeScanResult
}

data class LibraryScanResult(
  val requested: LibraryScanRequest,
  val mode: ScanMode,
  val outcome: ScanOutcome,
  val volumes: List<VolumeScanResult>,
  val statistics: ScanStatistics,
  val startedAtEpochMs: Long,
  val finishedAtEpochMs: Long,
)

sealed interface LibraryScanState {
  data object Idle : LibraryScanState
  data class Running(
    val requested: LibraryScanRequest,
    val mode: ScanMode,
    val progress: ScanProgress,
    val pendingFullRescan: Boolean,
  ) : LibraryScanState
  data class Finished(val result: LibraryScanResult) : LibraryScanState
}
```

- [ ] **Step 1: Write red tests for value invariants and stable identity**

Test non-negative scan counts, deterministic result aggregation, rejection of blank volume names and negative row IDs, same row ID on two volumes producing different IDs, Unicode volume round-trip safety, and exact output such as `local:v1:ZXh0ZXJuYWw:42` for `external/42`.

Run:

```bash
./gradlew :core:common:test --tests '*LibraryScanTest' \
  :core:data:testDebugUnitTest --tests '*MediaStoreStableIdTest' --stacktrace
```

Expected red state: the scan values and `V1StableMediaIdFactory` do not exist.

- [ ] **Step 2: Implement the public scan values and platform snapshots**

Create this internal gateway boundary:

```kotlin
@JvmInline
internal value class MediaStoreVolume(val name: String)

internal sealed interface MediaStoreReadMode {
  data object Full : MediaStoreReadMode
  data class GenerationWindow(
    val afterExclusive: Long,
    val upperInclusive: Long,
  ) : MediaStoreReadMode
}

internal data class MediaStoreSnapshotRequest(
  val volume: MediaStoreVolume,
  val mode: MediaStoreReadMode,
)

internal data class MediaStoreTrackSnapshot(
  val mediaId: TrackId,
  val volumeName: String,
  val mediaStoreId: Long,
  val contentUri: String,
  val displayName: String?,
  val title: String?,
  val artist: String?,
  val album: String?,
  val albumId: Long?,
  val artworkUri: String?,
  val durationMs: Long,
  val mimeType: String?,
  val sizeBytes: Long,
  val folderKey: String?,
  val folderDisplayName: String?,
  val dateAddedSeconds: Long,
  val dateModifiedSeconds: Long,
  val searchText: String,
  val titleSortKey: String,
  val artistSortKey: String,
  val albumSortKey: String,
  val folderSortKey: String,
  val metadataFingerprint: String,
)

internal data class MediaStoreVolumeSnapshot(
  val volume: MediaStoreVolume,
  val projectedRows: List<MediaStoreTrackSnapshot>,
  val presentMediaStoreIds: Set<Long>,
  val reportedTotal: Int?,
)

internal interface MediaStoreGateway {
  val supportsGeneration: Boolean
  suspend fun enumerateExternalVolumes(): List<MediaStoreVolume>
  suspend fun readVersion(volume: MediaStoreVolume): String?
  suspend fun readGeneration(volume: MediaStoreVolume): Long
  suspend fun readSnapshot(
    request: MediaStoreSnapshotRequest,
    onProgress: (processed: Int, total: Int?) -> Unit,
  ): MediaStoreVolumeSnapshot
}
```

Implement `V1StableMediaIdFactory` with UTF-8 Base64 URL encoding without padding. The platform mapper converts blank optional text to null without inventing localized values, clamps negative duration/size/added/modified numbers to zero, and rejects an invalid required row ID/content URI rather than returning a partial row. Build `searchText` with `listOfNotNull(title, artist, album, folderDisplayName).joinToString(" ")` in that exact order, then apply `trim()`, repeated-whitespace collapse, and `lowercase(Locale.ROOT)`; `displayName` is not a product search field. Set `titleSortKey`, `artistSortKey`, `albumSortKey`, and `folderSortKey` by applying that same normalization to the corresponding nullable field, producing `""` for null. Derive `folderKey` from the encoded volume plus a SHA-256 digest of normalized relative/parent path; persist only the final folder name, never the absolute path. Missing path produces null folder values.

- [ ] **Step 3: Test and implement canonical fingerprinting**

Write tests proving null differs from empty, delimiter-like values cannot collide, field order is stable, and every UI/playback metadata change changes the digest. Encode each field as a null marker or UTF-8 byte length plus bytes in this order: content URI, display name, title, artist, album, album ID, artwork URI, duration, MIME, size, folder key/name, added/modified time, and all five derived search/sort strings. Hash with SHA-256 lowercase hex.

```bash
./gradlew :core:data:testDebugUnitTest --tests '*MediaStoreMetadataTest' --stacktrace
```

Expected red state before implementation and `BUILD SUCCESSFUL` after `MediaStoreMetadata.kt` is complete.

- [ ] **Step 4: Commit Task 1**

```bash
git add core/common core/data/src/main/kotlin/app/yinyuehe/core/data/local/mediastore \
  core/data/src/test/kotlin/app/yinyuehe/core/data/local/mediastore
git commit -m "feat: define MediaStore scan contracts"
```

---

### Task 2: Implement complete cross-version MediaStore snapshots

**Files:** `AndroidMediaStoreGateway.kt`, `MediaStoreCursorMapper.kt`, `AndroidMediaStoreGatewayTest.kt`, and fake gateway fixtures.

- [ ] **Step 1: Write the cross-version gateway tests first**

Use Robolectric `ContentProvider`/`MatrixCursor` fixtures. Cover:

- API 26 and 28 expose exactly `external`; API 29+ use the sorted result of `MediaStore.getExternalVolumeNames()`.
- Projection includes `_ID`, display/title/artist/album/album ID, duration, MIME, size, added/modified time, and version-available folder/generation columns.
- Missing optional columns map to null/zero; missing `_ID` or a truncated/failed Cursor rejects the whole snapshot.
- API 30 generation selection is exactly `((GENERATION_ADDED > ? AND GENERATION_ADDED <= ?) OR (GENERATION_MODIFIED > ? AND GENERATION_MODIFIED <= ?))`.
- Incremental `projectedRows` contain only the requested generation window, while `presentMediaStoreIds` comes from a complete `_ID` query.
- `CancellationSignal` cancellation and Cursor exceptions return no partial snapshot; `SecurityException` remains distinguishable by the scan layer.

```bash
./gradlew :core:data:testDebugUnitTest --tests '*AndroidMediaStoreGatewayTest' --stacktrace
```

Expected red state: `AndroidMediaStoreGateway` and Cursor mapping are absent.

- [ ] **Step 2: Implement the Android gateway**

Use `MediaStore.Audio.Media.getContentUri(volume.name)` for API 29+ and `EXTERNAL_CONTENT_URI` for API 26–28. The fixed persisted predicate is `IS_MUSIC != 0`; UI search/filter state never changes it. Read Cursors on the injected IO dispatcher with `CancellationSignal`, `use`, safe optional-column lookup, and a monotonically increasing processed callback.

For full/legacy snapshots, read the complete metadata projection and use those row IDs for reconciliation. For generation snapshots, read the bounded metadata window and issue a second complete `_ID` query. Do not catch cancellation. Re-throw `SecurityException`; wrap other provider failures in an internal exception that contains only a typed code.

- [ ] **Step 3: Verify query completeness and compile all data code**

```bash
./gradlew :core:data:testDebugUnitTest --tests '*AndroidMediaStoreGatewayTest' \
  :core:data:compileDebugKotlin --stacktrace
```

Expected: `BUILD SUCCESSFUL`; tests verify that neither a failed metadata Cursor nor failed ID Cursor yields a snapshot.

- [ ] **Step 4: Commit Task 2**

```bash
git add core/data/src/main/kotlin/app/yinyuehe/core/data/local/mediastore \
  core/data/src/test/kotlin/app/yinyuehe/core/data/local/mediastore
git commit -m "feat: read versioned MediaStore snapshots"
```

---

### Task 3: Commit each volume atomically through real Room

**Files:** both DAO modifications, `ScanStore.kt`, `RoomScanStore.kt`, and `RoomScanStoreIntegrationTest.kt`.

**Store contract:**

```kotlin
internal interface ScanStore {
  suspend fun readCheckpoint(volumeName: String): ScanCheckpoint?
  suspend fun mountedVolumeNames(): Set<String>
  suspend fun commitVolume(commit: VolumeCommit): VolumeCommitStatistics
  suspend fun commitUnmount(commit: VolumeUnmountCommit): Int
}

internal data class ScanCheckpoint(
  val volumeName: String,
  val mediaStoreVersion: String?,
  val generationUpperBound: Long?,
  val lastFullScanEpochMs: Long,
  val lastSuccessfulScanEpochMs: Long,
  val lastScanToken: String,
  val isMounted: Boolean,
  val lastDiscoveredCount: Long,
  val lastInsertedCount: Long,
  val lastUpdatedCount: Long,
  val lastUnavailableCount: Long,
)

internal data class VolumeCommit(
  val snapshot: MediaStoreVolumeSnapshot,
  val strategy: ScanStrategy,
  val mediaStoreVersion: String?,
  val generationUpperBound: Long?,
  val scanToken: String,
  val committedAtEpochMs: Long,
)

internal data class VolumeUnmountCommit(
  val volumeName: String,
  val scanToken: String,
  val committedAtEpochMs: Long,
)

internal enum class ScanTransactionStage {
  AFTER_UPSERT,
  AFTER_UNAVAILABLE_RECONCILIATION,
  BEFORE_CHECKPOINT,
}

internal fun interface ScanTransactionFaultInjector {
  suspend fun check(stage: ScanTransactionStage)
}
```

- [ ] **Step 1: Write the real SQLite transaction tests**

Use `Room.inMemoryDatabaseBuilder`, real DAOs, and `RoomScanStore`; no fake DAO is acceptable here. Test insert/update/unchanged/restore statistics, deletion reconciliation, checkpoint advancement, empty-volume reconciliation, unmount/remount, and duplicate stable-ID protection. At each `ScanTransactionStage`, inject an exception after recording a baseline and assert tracks, availability, fingerprints, scan tokens, and checkpoint are byte-for-byte/equality unchanged after rollback.

```bash
./gradlew :core:data:testDebugUnitTest --tests '*RoomScanStoreIntegrationTest' --stacktrace
```

Expected red state: scan DAO methods and `RoomScanStore` do not exist.

- [ ] **Step 2: Add scan-only DAO operations**

Add a `TrackScanState(mediaStoreId, metadataFingerprint, isAvailable)` projection and DAO methods to read one volume, upsert complete changed entities, update only `lastSeenScanToken` for present-ID chunks of at most 800, mark token-mismatched rows unavailable, and mark an entire volume unavailable. Only rows included in the bounded/full `projectedRows` may transition from unavailable to available; a future-generation ID seen only by reconciliation remains unavailable until its metadata window is consumed. Add checkpoint methods to read one volume, list mounted names, and upsert. Do not concatenate IDs or user values into SQL.

- [ ] **Step 3: Implement the per-volume transaction order**

Inside one `YinYueHeDatabase.withTransaction`:

1. Read existing state for the volume.
2. Upsert only new rows, fingerprint changes, or restored rows, with the new scan token.
3. Mark every `presentMediaStoreId` seen in chunks of at most 800 without changing availability; projected new/changed/restored rows were made available by step 2.
4. Mark rows from that volume whose token differs unavailable.
5. Upsert the checkpoint last, including strategy-sensitive full time, safe generation upper bound, mounted state, and statistics.

`commitUnmount` uses its own short transaction to mark the volume unavailable and copy/update its checkpoint with `isMounted = false`. No fault injector exists in production construction; tests inject the three hooks.

- [ ] **Step 4: Verify rollback and schema stability**

```bash
./gradlew :core:data:testDebugUnitTest --tests '*RoomScanStoreIntegrationTest' \
  :core:data:kspDebugKotlin --stacktrace
git diff --exit-code -- core/data/schemas/app.yinyuehe.core.data.local.db.YinYueHeDatabase/1.json
```

Expected: tests pass and the exported v1 schema is unchanged.

- [ ] **Step 5: Commit Task 3**

```bash
git add core/data/src/main/kotlin/app/yinyuehe/core/data/local/db \
  core/data/src/main/kotlin/app/yinyuehe/core/data/scan \
  core/data/src/test/kotlin/app/yinyuehe/core/data/scan
git commit -m "feat: commit MediaStore volumes atomically"
```

---

### Task 4: Select safe scan strategies and reconcile volumes

**Files:** `VolumeScanner.kt`, `ScanAccessPrecondition.kt`, `ScanPrimitives.kt`, `VolumeScannerTest.kt`, `MediaStoreScannerRoomIntegrationTest.kt`, and scan fakes.

- [ ] **Step 1: Write legacy and API 30+ strategy tests before orchestration**

Legacy tests must prove a same-second metadata edit and a system-clock rollback are found through full-projection fingerprint comparison, unchanged rows are not rewritten, query/cancellation does not mark old rows unavailable, and explicit full scan uses complete reconciliation.

API 30+ tests must prove:

- initial/invalid/unmounted/version-mismatched checkpoint selects full;
- automatic scan captures `versionStart` and upper bound `U`, reads only `(previous, U]`, rechecks version, commits checkpoint `U`, and queues one follow-up automatic when current generation is greater than `U`;
- a fake row injected at `U + 1` after capture is absent from the current commit and present in the follow-up;
- a previously unavailable row that reappears only at `U + 1` is not restored by the current lightweight ID reconciliation and becomes available only in the follow-up metadata window;
- changed `versionEnd` discards the snapshot, leaves Room/checkpoint unchanged, and requests one full rescan.

Across both SDK branches, inject gateway `SecurityException` twice: with the access precondition now false it must become `AccessLost` and stop all remaining work; with access still true it must become a per-volume `Query` failure and must not signal permission revocation.

```bash
./gradlew :core:data:testDebugUnitTest --tests '*VolumeScannerTest' --stacktrace
```

Expected red state: strategy selection and per-volume scanning are absent.

- [ ] **Step 2: Implement access checks and per-volume strategy**

Define:

```kotlin
fun interface ScanAccessPrecondition {
  suspend fun isSatisfied(): Boolean
}

fun interface EpochMillis { fun now(): Long }
fun interface ScanTokenFactory { fun create(): String }
```

Check access at scan start, immediately before each volume query, and after snapshot completion before commit. A false result maps to `ScanFailure.AccessLost`, stops remaining volumes, and never commits the incomplete volume. When the gateway throws `SecurityException`, immediately re-check `ScanAccessPrecondition`: map to `AccessLost` only when current OS access is false; if access is still true, map the volume to typed `ScanFailure.Query` and preserve permission state/cache. Test both branches so a provider-specific security failure cannot falsely downgrade permission history.

On API 30+, re-read version before commit. After commit, re-read generation; return a typed follow-up request when it exceeds `U`. On API 26–29, always request the complete projection and let the store skip equal fingerprints. Never derive a global timestamp watermark.

- [ ] **Step 3: Test and implement mounted-volume reconciliation**

Only after `enumerateExternalVolumes()` returns completely, compare its names with `ScanStore.mountedVolumeNames()`. Commit each missing historical volume as unmounted in its own transaction. Enumeration failure or access loss must not unmount anything; later remount of the same name forces full scan and restores the original stable IDs.

Create the required end-to-end data integration fixture:

```text
FakeMediaStoreGateway
        -> VolumeScanner
        -> RoomScanStore
        -> Room.inMemoryDatabaseBuilder(
             ApplicationProvider.getApplicationContext(),
             YinYueHeDatabase::class.java,
           ).build()
```

Run the successful, query-failure, cancellation, partial-volume, unmount/remount, and all three transaction fault stages through this path. After each injected failure, query real SQLite and assert tracks, availability, scan tokens, and checkpoint equal the saved baseline. `FakeScanStore` remains valid only for pure strategy tests; it cannot satisfy this evidence.

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*VolumeScannerTest*unmount*' --tests '*VolumeScannerTest*partial*' \
  --tests '*MediaStoreScannerRoomIntegrationTest' --stacktrace
```

Expected: successful volumes can commit when another volume has a query/database failure; overall outcome becomes `PARTIAL_SUCCESS`, and failed volumes retain their prior cache.

- [ ] **Step 4: Commit Task 4**

```bash
git add core/data/src/main/kotlin/app/yinyuehe/core/data/scan \
  core/data/src/test/kotlin/app/yinyuehe/core/data/scan
git commit -m "feat: add safe incremental library scanning"
```

---

### Task 5: Add application-scope single-flight behavior and pass the PR gate

**Files:** `LibraryScanner.kt`, `DefaultLibraryScanner.kt`, `DefaultLibraryScannerTest.kt`, and remaining scan fixtures.

**Public interface:**

```kotlin
interface LibraryScanner {
  val state: StateFlow<LibraryScanState>
  suspend fun request(request: LibraryScanRequest): LibraryScanResult
  suspend fun cancelCurrent(reason: ScanCancellationReason)
}
```

- [ ] **Step 1: Write concurrency tests with controllable gates**

Cover maximum concurrency of one, multiple automatic callers joining the current job, caller cancellation not cancelling the independently scoped job, automatic scan plus multiple full requests producing exactly one pending full, a full request joining an already-running full, generation follow-ups coalescing to one automatic, progress monotonicity, normal user cancellation, and access-loss cancellation clearing pending work while completing every pending waiter with a cancelled/access-lost result rather than leaving it suspended.

```bash
./gradlew :core:data:testDebugUnitTest --tests '*DefaultLibraryScannerTest' --stacktrace
```

Expected red state: `DefaultLibraryScanner` does not exist.

- [ ] **Step 2: Implement the mutex-protected coordinator**

Construct it with an externally owned application `CoroutineScope`; do not create `GlobalScope` and do not annotate it for Hilt yet. Jobs launched in that independent scope own their `CompletableDeferred` results, so cancelling one `request().await` caller cannot cancel the scan. Protect current job, pending-full result, and automatic follow-up flags with one `Mutex`. Inside `withLock`, only select or create the shared result plus a lazy job handle and update coordinator references; return those handles, then call `start()` and every `await()` after leaving the lock. Scan execution, `CompletableDeferred.complete`, caller resumption/callbacks, and progress emission also occur outside the lock; completion may reacquire it only for a short state swap before publishing the result. Add an `UnconfinedTestDispatcher` regression that would deadlock if a deferred is completed or awaited while locked. A user full request during automatic waits for the unique subsequent full result; repeated clicks share it.

Emit `Running` with current progress and `pendingFullRescan`; emit `Finished` for each completed job. Cancellation is `CANCELLED`, not a database failure. Access loss stops remaining work and clears pending full/follow-up automatic requests.

- [ ] **Step 3: Run all M2-B and repository regressions**

```bash
./gradlew :core:common:test :core:data:testDebugUnitTest \
  :feature:library:testDebugUnitTest :core:player:testDebugUnitTest --stacktrace
./gradlew test testDebugUnitTest lintDebug assembleDebug --stacktrace
git diff --check
```

Expected: both Gradle commands report `BUILD SUCCESSFUL`; no Room schema diff exists; no Manifest, feature, or player production file changed.

- [ ] **Step 4: Commit Task 5**

```bash
git add core/common core/data docs/superpowers/plans/2026-07-13-m2b-mediastore-scanner.md
git commit -m "feat: coordinate single-flight library scans"
```

- [ ] **Step 5: Complete two-stage review and merge**

First run an independent specification review against M2-B sections 8, 10, 11.2, and 12.2. Fix every Critical/Important finding. Then use a different reviewer for Cursor closure, SDK branching, generation bounds, Mutex ownership, cancellation, transaction rollback, privacy, and schema stability. Re-run the complete gate after fixes.

Push `feature/m2b-mediastore-scanner`, open PR `feat: add resilient MediaStore library scanner`, wait for all GitHub Actions checks, squash-merge, and verify `main` CI before M2-C begins.

## References

- Approved design: `docs/superpowers/specs/2026-07-13-m2-local-library-design.md`
- MediaStore API reference: <https://developer.android.com/reference/android/provider/MediaStore.html>
- Media generation reference: <https://developer.android.com/reference/android/provider/MediaStore.MediaColumns.html>
- Shared media guidance: <https://developer.android.com/training/data-storage/shared/media>
