# M2-C Audio Permission and Library Scan UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the API-correct audio permission state machine, persist request history, wire the M2-B scanner at application scope, and provide resilient library UI that never exposes stale local Content URIs without permission.

**Architecture:** `core:data.permission` reduces real system snapshots plus Proto DataStore history into a typed state. `RoomTrackRepository` combines that state with its raw Room flow to select either the complete local cache or complete Demo catalog. `LibraryViewModel` coordinates permission observations and scan requests; `MainActivity` owns Activity Result launchers/system settings, while Compose consumes a single effect stream and renders immutable state.

**Tech Stack:** Kotlin 2.0.20, Android API 26–36, DataStore 1.2.1, protobuf Gradle plugin 0.10.0, protobuf-javalite/protoc 4.35.1, Hilt 2.57.1, Coroutines/Flow 1.9.0, Compose Material 3.

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

- Start only after M2-B is squash-merged and `main` CI is green. Use `superpowers:using-git-worktrees`; branch: `feature/m2c-audio-permission-library-ui`.
- Add `READ_MEDIA_AUDIO` for API 33+ and `READ_EXTERNAL_STORAGE` with `maxSdkVersion="32"` for API 26–32.
- Keep M1's existing `POST_NOTIFICATIONS` Manifest declaration unchanged. No M2-C effect, launcher, or code path may request it.
- The first launch shows an explanation and button; it never opens the system permission dialog automatically.
- Without audio permission, Room rows remain stored but `observeLibrary()` emits only Demo. With permission, non-empty local cache wins; with permission and an empty cache, Demo is the fallback.
- A scan failure never clears old cache. With usable local content it is non-blocking; without local content Demo remains playable with retry guidance.
- Activity/Compose owns platform launchers only. It does not query MediaStore or Room. ViewModel never holds an Activity, Context, launcher, or `SnackbarHostState`.
- Proto DataStore has exactly one process-wide instance. Permission history reductions and writes are serialized and never synchronously block the main thread.
- Do not add search, sorting, grouping, playlists, notification requests, playback-service changes, new modules, Paging, or FTS.
- Each task uses a fresh implementation subagent, specification-compliance review, then code-quality review. Resolve all Critical/Important findings before continuing.
- Design source of truth: `docs/superpowers/specs/2026-07-13-m2-local-library-design.md`.

## File Structure

### Modify

- `gradle/libs.versions.toml`
- `build.gradle.kts`
- `core/data/build.gradle.kts`
- `feature/library/build.gradle.kts`
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/repository/RoomTrackRepository.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/DataModule.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/scan/DefaultLibraryScanner.kt`
- `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryViewModel.kt`
- `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryUiState.kt`
- `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryScreen.kt`
- `feature/library/src/main/res/values/strings.xml`
- `feature/library/src/test/kotlin/app/yinyuehe/feature/library/LibraryViewModelTest.kt`
- `feature/library/src/androidTest/kotlin/app/yinyuehe/feature/library/LibraryScreenTest.kt`
- `core/testing/src/main/kotlin/app/yinyuehe/core/testing/FakeTrackRepository.kt`
- `app/src/main/kotlin/app/yinyuehe/MainActivity.kt`
- `app/src/main/kotlin/app/yinyuehe/YinYueHeApp.kt`

### Create

- `core/data/src/main/proto/app/yinyuehe/core/data/permission/audio_permission_history.proto`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/permission/AudioPermissionRepository.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/permission/AudioPermissionReducer.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/permission/AudioPermissionPreferencesSerializer.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/permission/ProtoAudioPermissionRepository.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/permission/RequiredAudioPermission.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/permission/AndroidAudioPermissionChecker.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/permission/AudioPermissionScanAccessPrecondition.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/permission/AudioPermissionModule.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/scan/ScannerModule.kt`
- `core/data/src/test/kotlin/app/yinyuehe/core/data/permission/AudioPermissionReducerTest.kt`
- `core/data/src/test/kotlin/app/yinyuehe/core/data/permission/AudioPermissionPreferencesSerializerTest.kt`
- `core/data/src/test/kotlin/app/yinyuehe/core/data/permission/ProtoAudioPermissionRepositoryTest.kt`
- `core/data/src/test/kotlin/app/yinyuehe/core/data/permission/RequiredAudioPermissionTest.kt`
- `core/data/src/test/kotlin/app/yinyuehe/core/data/permission/AudioPermissionScanAccessPreconditionTest.kt`
- `core/testing/src/main/kotlin/app/yinyuehe/core/testing/FakeAudioPermissionRepository.kt`
- `core/testing/src/main/kotlin/app/yinyuehe/core/testing/FakeLibraryScanner.kt`
- `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryEffect.kt`
- `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryStatus.kt`
- `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryUiStateMapper.kt`
- `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryStatusCard.kt`
- `app/src/main/kotlin/app/yinyuehe/AudioPermissionSnapshotReader.kt`
- `app/src/androidTest/kotlin/app/yinyuehe/AppPermissionDeclarationTest.kt`

---

### Task 1: Persist and reduce audio permission history

**Files:** Gradle/proto files and `core:data.permission` serializer, reducer, repository, and focused tests.

**Domain contract:**

```kotlin
enum class AudioPermissionState {
  CHECKING,
  NOT_REQUESTED,
  GRANTED,
  DENIED_CAN_RETRY,
  DENIED_PERMANENTLY,
}

internal enum class AudioPermissionHistory {
  NEVER_REQUESTED,
  REQUESTED_BEFORE,
  GRANTED_BEFORE,
  PERMANENTLY_DENIED,
}

data class AudioPermissionSnapshot(
  val isGranted: Boolean,
  val shouldShowRationale: Boolean,
)

interface AudioPermissionRepository {
  val state: StateFlow<AudioPermissionState>
  val requiredPermission: String
  suspend fun refresh(snapshot: AudioPermissionSnapshot): AudioPermissionState
  suspend fun onRequestResult(snapshot: AudioPermissionSnapshot): AudioPermissionState
  suspend fun onScanAccessLost(): AudioPermissionState
}
```

- [ ] **Step 1: Add the pinned DataStore/protobuf toolchain**

Add version-catalog aliases for DataStore `androidx.datastore:datastore:1.2.1`, `com.google.protobuf:protobuf-javalite:4.35.1`, protoc `4.35.1`, and plugin `com.google.protobuf:0.10.0`. Declare the plugin with `apply false` at root, apply it in `core:data`, and configure:

```kotlin
protobuf {
  protoc { artifact = libs.protobuf.protoc.get().toString() }
  generateProtoTasks {
    all().forEach { task ->
      task.builtins {
        create("java") { option("lite") }
      }
    }
  }
}
```

Add DataStore and javalite production dependencies. Add `androidx.activity:activity-ktx` and `androidx.lifecycle:lifecycle-runtime-ktx` aliases for the app, plus `androidx.activity:activity-compose` and `androidx.core:core-ktx` to `feature:library`.

- [ ] **Step 2: Create the proto and failing truth-table tests**

Use this exact schema and fixed filename `audio_permission.pb`:

```proto
syntax = "proto3";

package app.yinyuehe.core.data.permission.proto;

option java_package = "app.yinyuehe.core.data.permission.proto";
option java_multiple_files = true;

message AudioPermissionPreferences {
  StoredAudioPermissionHistory history = 1;
}

enum StoredAudioPermissionHistory {
  STORED_AUDIO_PERMISSION_HISTORY_NEVER_REQUESTED = 0;
  STORED_AUDIO_PERMISSION_HISTORY_REQUESTED_BEFORE = 1;
  STORED_AUDIO_PERMISSION_HISTORY_GRANTED_BEFORE = 2;
  STORED_AUDIO_PERMISSION_HISTORY_PERMANENTLY_DENIED = 3;
}
```

Create table-driven reducer tests for every row:

| Trigger | Granted | History | Rationale | State | Stored history |
| --- | --- | --- | --- | --- | --- |
| refresh | yes | any | any | `GRANTED` | `GRANTED_BEFORE` |
| refresh | no | `NEVER_REQUESTED` | false | `NOT_REQUESTED` | unchanged |
| refresh | no | `REQUESTED_BEFORE` | true | `DENIED_CAN_RETRY` | unchanged |
| refresh | no | `REQUESTED_BEFORE` | false | `DENIED_PERMANENTLY` | `PERMANENTLY_DENIED` |
| refresh | no | `GRANTED_BEFORE` | either | `DENIED_CAN_RETRY` | `GRANTED_BEFORE` |
| request result | yes | any | any | `GRANTED` | `GRANTED_BEFORE` |
| request result | no | any non-permanent | true | `DENIED_CAN_RETRY` | `REQUESTED_BEFORE` |
| request result | no | any non-permanent | false | `DENIED_PERMANENTLY` | `PERMANENTLY_DENIED` |
| access lost | no | `GRANTED_BEFORE` | n/a | `DENIED_CAN_RETRY` | `GRANTED_BEFORE` |
| refresh | no | `PERMANENTLY_DENIED` | either | `DENIED_PERMANENTLY` | unchanged |

Also assert that a denied request-result event cannot downgrade `PERMANENTLY_DENIED`; only a real granted snapshot can leave that history.

```bash
./gradlew :core:data:generateDebugProto \
  :core:data:testDebugUnitTest --tests '*AudioPermissionReducerTest' --stacktrace
```

Expected red state: proto generation succeeds, while reducer types/tests fail to compile.

- [ ] **Step 3: Implement the pure reducer and serializer**

Keep `refresh`, `request result`, and `access lost` as explicit reducer events. The first `rationale=false` observation with `NEVER_REQUESTED` must remain `NOT_REQUESTED`; a prior grant with automatic reset must first become retryable even when rationale is false. A real granted snapshot always overrides permanent history and stores `GRANTED_BEFORE`.

The serializer returns `AudioPermissionPreferences.getDefaultInstance()`, parses/writes lite protobuf, and converts invalid protobuf to `CorruptionException`. The Hilt DataStore uses `ReplaceFileCorruptionHandler` to replace corruption with the default instance.

- [ ] **Step 4: Write and implement repository persistence tests**

Use `DataStoreFactory` with a temporary file and test scope. Verify one serialized update per state-changing event, stable state on no-op refresh, recovery from corrupt bytes, write failure propagation through a typed `AudioPermissionPersistenceException`, and concurrent callback/resume reductions serialized by one `Mutex`.

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*AudioPermissionPreferencesSerializerTest' \
  --tests '*ProtoAudioPermissionRepositoryTest' --stacktrace
```

Expected red before repository implementation and `BUILD SUCCESSFUL` after it. The repository starts at `CHECKING`, reads/writes DataStore on a coroutine, and updates its StateFlow only after a successful persisted reduction.

- [ ] **Step 5: Commit Task 1**

```bash
git add gradle/libs.versions.toml build.gradle.kts core/data/build.gradle.kts \
  core/data/src/main/proto core/data/src/main/kotlin/app/yinyuehe/core/data/permission \
  core/data/src/test/kotlin/app/yinyuehe/core/data/permission app/build.gradle.kts \
  feature/library/build.gradle.kts
git commit -m "feat: persist audio permission history"
```

---

### Task 2: Gate local content and wire the application scanner

**Files:** permission platform files, `RoomTrackRepository`, scan module, data module, fakes, and related tests.

- [ ] **Step 1: Test SDK mapping and real access precondition first**

Assert SDK 26/30/32 maps to `Manifest.permission.READ_EXTERNAL_STORAGE`, SDK 33/36 maps to `Manifest.permission.READ_MEDIA_AUDIO`, and no input returns `POST_NOTIFICATIONS`. Test that the scan precondition requires both repository state `GRANTED` and current `ContextCompat.checkSelfPermission` truth, so runtime revocation is detected before the next query.

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*RequiredAudioPermissionTest' \
  --tests '*AudioPermissionScanAccessPreconditionTest' --stacktrace
```

Expected red state: platform resolver/checker and precondition are absent.

- [ ] **Step 2: Write the permission-aware repository tests**

Extend the real in-memory Room test matrix:

- `GRANTED + cache` emits only Local.
- `GRANTED + empty cache` emits the full Demo catalog.
- `CHECKING`, retryable denial, and permanent denial plus cache emit Demo and leave every Room row intact.
- denial then re-grant restores cached Local without a database write.
- scan failure while still granted preserves cached Local.
- one permission emission switches the entire list; no Local/Demo mixture is ever observable.

```bash
./gradlew :core:data:testDebugUnitTest --tests '*RoomTrackRepositoryTest' --stacktrace
```

Expected red state: the M2-A repository still exposes cached Local regardless of permission.

- [ ] **Step 3: Combine the source gate and implement production Hilt wiring**

Change only `observeLibrary()`; retain M2-A's independent `observeAvailableLocalTracks()` and `demoTracks()` APIs:

```kotlin
override fun observeLibrary(): Flow<LibraryContent> =
  combine(observeAvailableLocalTracks(), audioPermissionRepository.state) { local, permission ->
    if (permission == AudioPermissionState.GRANTED && local.isNotEmpty()) {
      LibraryContent(LibrarySource.LOCAL, local)
    } else {
      LibraryContent(LibrarySource.DEMO, demos)
    }
  }.distinctUntilChanged()
```

Provide one singleton DataStore/repository, Android gateway, `RoomScanStore`, `VolumeScanner`, and singleton `LibraryScanner`. Provide an application-owned `CoroutineScope(SupervisorJob() + Dispatchers.Default)` via a qualifier; pass it to `DefaultLibraryScanner`. Do not use `GlobalScope`. The scan access precondition checks repository state plus current OS grant. Keep Android Context confined to permission/gateway providers.

- [ ] **Step 4: Verify Hilt and scanner revocation behavior**

```bash
./gradlew :core:data:testDebugUnitTest :core:data:compileDebugKotlin \
  :app:assembleDebug --stacktrace
```

Expected: `BUILD SUCCESSFUL`; Hilt has exactly one binding for `AudioPermissionRepository`, `DataStore<AudioPermissionPreferences>`, `ScanAccessPrecondition`, and `LibraryScanner`.

- [ ] **Step 5: Commit Task 2**

```bash
git add core/data core/testing
git commit -m "feat: gate local library by audio permission"
```

---

### Task 3: Coordinate permission, scanning, source state, and effects

**Files:** `LibraryViewModel`, `LibraryUiState`, effect/status files, fakes, and ViewModel tests.

**Feature contracts:**

```kotlin
sealed interface LibraryEffect {
  data class RequestAudioPermission(val permission: String) : LibraryEffect
  data object OpenAppSettings : LibraryEffect
  data class ShowMessage(val message: LibraryMessage) : LibraryEffect
}

enum class LibraryMessage {
  PERMISSION_STATE_UNAVAILABLE,
  SYSTEM_ACTION_UNAVAILABLE,
  SCAN_FAILED,
  SCAN_PARTIALLY_FAILED,
  PLAYBACK_CONNECTION_FAILED,
  PLAYBACK_FAILED,
}

enum class LibraryStatus {
  CHECKING_PERMISSION,
  PERMISSION_REQUIRED,
  PERMISSION_DENIED_RETRY,
  PERMISSION_DENIED_SETTINGS,
  SCANNING_WITHOUT_CACHE,
  SCANNING_WITH_CACHE,
  NO_LOCAL_MUSIC,
  SCAN_FAILED_WITHOUT_CACHE,
  SCAN_FAILED_WITH_CACHE,
  PARTIAL_SCAN_FAILED,
  READY,
}

data class LibraryUiState(
  val source: LibrarySource = LibrarySource.DEMO,
  val permission: AudioPermissionState = AudioPermissionState.CHECKING,
  val scanState: LibraryScanState = LibraryScanState.Idle,
  val status: LibraryStatus = LibraryStatus.CHECKING_PERMISSION,
  val tracks: List<Track> = emptyList(),
  val hasRetainedLocalCache: Boolean = false,
  val permissionRequestInFlight: Boolean = false,
)
```

- [ ] **Step 1: Write ViewModel tests for the full state matrix**

Use `FakeAudioPermissionRepository`, `FakeLibraryScanner`, `FakeTrackRepository`, and `MainDispatcherRule`. Cover all permission statuses; Demo with retained cache while denied; Local with granted cache; granted empty plus scan running/success/failure; scanning/failure with cache never replacing content; partial failure; cancelled scan returning to stable content without an error; and playback behavior using the currently visible list.

Write effect/trigger tests for:

- first tap emits exactly one `RequestAudioPermission(requiredPermission)`; repeat tap, recomposition-equivalent calls, and rotation-preserved ViewModel state emit no duplicate;
- launcher callback clears in-flight before the next request can occur and uses the post-result rationale;
- permission/settings launch failures clear any in-flight flag and emit only a typed, retryable message;
- initial `CHECKING -> GRANTED` resolution and any non-granted-to-granted transition each issue one `Automatic`; resume/callback races still issue one because the second serialized observation sees the state already granted;
- manual retry while granted issues `UserFullRescan`; ungranted/checking states never call the scanner;
- `ScanFailure.AccessLost` calls `onScanAccessLost()`, calls `cancelCurrent(ACCESS_LOST)`, clears pending scan work, switches content to Demo, and does not emit a generic query-failure message;
- other failed/partial results emit only typed messages and never exception text.

```bash
./gradlew :feature:library:testDebugUnitTest --tests '*LibraryViewModelTest' --stacktrace
```

Expected red state: the old ViewModel has no permission, scanner, status, or effect contract.

- [ ] **Step 2: Implement immutable state composition**

Combine `TrackRepository.observeLibrary()`, raw `observeAvailableLocalTracks()`, permission state, scanner state, and playback state/errors. `hasRetainedLocalCache` is diagnostic UI state only; it never makes denied cached tracks clickable. Derive status with one pure mapper and exhaustively test its precedence: permission first, then running scan, then last typed result, then empty/ready.

- [ ] **Step 3: Implement a single buffered effect stream and serialized actions**

Use one `Channel<LibraryEffect>(Channel.BUFFERED).receiveAsFlow()`. Set `permissionRequestInFlight` before sending the request effect. Clear it only on callback or explicit launch failure. Serialize lifecycle snapshots, launcher results, and scan access loss with one ViewModel `Mutex`; DataStore persistence failure emits `PERMISSION_STATE_UNAVAILABLE` and must not start scanning.

Every actual process-start `CHECKING -> GRANTED` resolution requests one automatic scan. A transition from a non-granted state to granted after launcher/settings also requests one automatic scan. Same-turn callback/onResume duplication is naturally coalesced by the serialized transition check; an ordinary resume that remains granted only refreshes system truth and does not start a second scan. Manual rescan always requests `UserFullRescan`.

- [ ] **Step 4: Verify feature and playback regressions, then commit**

```bash
./gradlew :feature:library:testDebugUnitTest :core:player:testDebugUnitTest --stacktrace
```

Expected: `BUILD SUCCESSFUL`; denied local URIs never reach `onTrackClick` because the visible queue is Demo.

```bash
git add feature/library/src/main core/testing feature/library/src/test
git commit -m "feat: orchestrate permission-aware library scans"
```

---

### Task 4: Add Activity Result boundary and resilient Compose states

**Files:** Manifest, Activity/app composable, snapshot reader, library route/screen/status card/resources, and UI tests.

- [ ] **Step 1: Add Manifest declarations and their failing test**

Insert only these audio permissions beside the existing notification declaration:

```xml
<uses-permission
    android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
```

On the API 36 connected-test device, `AppPermissionDeclarationTest` reads `PackageInfo.requestedPermissions` and asserts `READ_MEDIA_AUDIO` exists while max-SDK-excluded `READ_EXTERNAL_STORAGE` does not appear in the installed package's runtime permission list. Do not use API 36 `PackageInfo` to assert the legacy declaration: package installation legitimately filters a permission whose `maxSdkVersion` is 32. Verify that source contract separately with:

```bash
rg -U 'READ_EXTERNAL_STORAGE"\s+android:maxSdkVersion="32"' app/src/main/AndroidManifest.xml
```

Expected: exactly one match. Separately test `requiredAudioPermissionForSdk` to prove SDK 26/30/32 chooses `READ_EXTERNAL_STORAGE`, SDK 33/36 chooses `READ_MEDIA_AUDIO`, and the request effect never chooses notification permission. M2-D's API 26/30 installed-package acceptance supplies the device-side evidence for the legacy declaration.

- [ ] **Step 2: Move platform operations to MainActivity**

Use one Activity-scoped `by viewModels<LibraryViewModel>()`, register `ActivityResultContracts.RequestPermission()`, and pass two lambdas into `YinYueHeApp`: launch the exact permission string and open `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` with this package URI. Catch platform launch failures and call typed `onPermissionLaunchFailed()`/`onSettingsLaunchFailed()` handlers; never display the exception message. In the permission callback, re-read both current grant and post-result rationale before calling the ViewModel. In `onResume`, re-read real system truth so settings changes and automatic reset are observed.

`AudioPermissionSnapshotReader` is the only Activity helper that calls `ContextCompat.checkSelfPermission` and `ActivityCompat.shouldShowRequestPermissionRationale`. It accepts the ViewModel's required permission string; it never assumes the callback Boolean is authoritative.

- [ ] **Step 3: Consume effects once at the Compose boundary**

`YinYueHeApp` passes the same Activity ViewModel to `LibraryRoute`. `LibraryRoute` uses one `LaunchedEffect(viewModel)` collector: permission/settings effects call the Activity lambdas, while `ShowMessage` calls `SnackbarHostState.showSnackbar` with a resource-backed, typed message. No second collector and no state replay of effects is allowed.

- [ ] **Step 4: Write Compose tests before implementing status cards**

Test Demo remains visible/playable in every denied state; request button is disabled only while its request is in flight; permanent denial shows settings; scanning without cache shows Demo plus progress; scanning with cache keeps Local rows clickable; failed scan with cache is inline/non-blocking; failed/empty without cache shows Demo plus rescan; and progress/statistics contain no private metadata. Use stable test tags and callbacks, not localized text, for actions.

```bash
./gradlew :feature:library:assembleDebugAndroidTest --stacktrace
```

Expected red state: new UI state/status callbacks are not yet rendered.

- [ ] **Step 5: Implement and run connected UI verification**

Keep the existing track-list design and add a compact resource-backed `LibraryStatusCard` above it. Full-screen loading/error is allowed only when there is no playable Demo or Local content; under this design Demo normally remains available. Do not mix Local and Demo in a LazyColumn.

With an API 36 emulator running:

```bash
./gradlew :feature:library:connectedDebugAndroidTest \
  :app:connectedDebugAndroidTest --stacktrace
```

Expected: Compose state tests and permission declaration test pass.

- [ ] **Step 6: Commit Task 4**

```bash
git add app feature/library
git commit -m "feat: add audio permission and scan UX"
```

---

### Task 5: Run the M2-C release gate, two-stage review, and PR merge

- [ ] **Step 1: Run focused state-machine and source-safety verification**

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*AudioPermission*Test' --tests '*RoomTrackRepositoryTest' \
  :feature:library:testDebugUnitTest --tests '*LibraryViewModelTest' --stacktrace
! rg -n 'POST_NOTIFICATIONS' app/src/main/kotlin core/data/src/main/kotlin \
  feature/library/src/main/kotlin
```

Expected: Gradle reports `BUILD SUCCESSFUL`, and the source search finds no notification permission request path.

- [ ] **Step 2: Run the repository gate**

```bash
./gradlew test testDebugUnitTest lintDebug assembleDebug --stacktrace
./gradlew :core:data:assembleDebugAndroidTest :feature:library:assembleDebugAndroidTest \
  :app:assembleDebugAndroidTest --stacktrace
git diff --check
git diff --exit-code -- core/data/schemas/app.yinyuehe.core.data.local.db.YinYueHeDatabase/1.json
```

Expected: all commands pass and Room v1 schema is unchanged.

- [ ] **Step 3: Perform the two independent reviews**

The specification reviewer checks the exact truth table, first-request distinction, re-grant/settings path, source switching, trigger rules, no notification request, old-cache preservation, and M2-C exclusions. After all Critical/Important fixes, a different code-quality reviewer checks DataStore singleton/corruption, Mutex ordering, effect delivery, lifecycle/callback races, Hilt scope ownership, revocation cancellation, URI leakage, and UI content precedence.

- [ ] **Step 4: Commit review fixes and publish**

```bash
git add core/data core/testing feature/library app \
  docs/superpowers/plans/2026-07-13-m2c-audio-permission-library-ui.md
git commit -m "test: harden permission and scan state transitions"
./gradlew test testDebugUnitTest lintDebug assembleDebug --stacktrace
git status --short
git log --oneline origin/main..HEAD
```

Expected: final gate passes and status is clean. Push the branch, open PR `feat: add audio permission and library scan UX`, wait for GitHub Actions, squash-merge, then verify `main` CI before M2-D.

## References

- Approved design: `docs/superpowers/specs/2026-07-13-m2-local-library-design.md`
- Proto DataStore guide: <https://developer.android.com/codelabs/android-proto-datastore>
- Shared media permissions: <https://developer.android.com/training/data-storage/shared/media>
