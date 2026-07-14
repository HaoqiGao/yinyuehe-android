# Resume Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the independent `yinyuehe-android` repository truthfully support every concrete capability stated in the current resume: MediaStore local audio, Room/Flow favorites and recent 20, full queue controls, three Compose pages, Player callback driven MVVM state, local analytics, first-frame/play-start timing, and a 21-scenario acceptance matrix.

**Architecture:** Keep the existing seven-module project and formal Media3 dependencies. `:core:data` owns MediaStore-to-Room scanning and user data; `:core:player` remains the only owner of ExoPlayer/MediaSession and exposes domain `StateFlow`; `:feature:library` hosts three small page composables coordinated by one UDF ViewModel for this urgent vertical slice. The work migrates behavior from the old source-tree demo, never its dependency on `DemoPlaybackService` or Media3 demo modules.

**Tech Stack:** Kotlin, Coroutines/Flow, Room, Hilt, Jetpack Compose Material 3, Media3 ExoPlayer/MediaLibraryService/MediaSession, JUnit, Robolectric, Compose UI Test, Android Lint, GitHub Actions.

## Global Constraints

- `applicationId` and namespace remain `app.yinyuehe`; `minSdk = 26`, `compileSdk = 36`, `targetSdk = 36`, JDK/JVM target 17.
- Use only the formal Maven Media3 artifacts already pinned in `gradle/libs.versions.toml`; do not copy or depend on `DemoPlaybackService`, `DemoMediaLibrarySessionCallback`, `MediaItemTree`, or another upstream demo module.
- Compose never accesses Room, MediaStore, or MediaController directly. All actions go Compose -> `MusicBoxAction` -> ViewModel -> Repository/PlaybackController; all durable state returns by Flow/StateFlow.
- Room Entity, Cursor, Uri, and Media3 `MediaItem` do not enter Feature public state.
- Real local tracks and Demo tracks are never mixed in the main library list. Demo tracks remain the no-permission/empty-device fallback.
- MediaStore IDs are deterministic `volumeName + rowId` identities. Cursor reads finish outside Room transactions; a failed query cannot mark cached rows unavailable.
- Favorites and recent-play rows are Room-backed. `observeRecentTracks()` returns at most 20 rows ordered by newest play time, with one aggregate row per track.
- ExoPlayer remains service-owned. Media3 handles AudioFocus; ExoPlayer handles `ACTION_AUDIO_BECOMING_NOISY`; notification/system controls and in-app controls all feed the same Player callback state.
- Local analytics stores typed events only, caps storage at 500 rows, and never records file paths. A first-frame metric must describe the first rendered frame rather than merely the first `LaunchedEffect`.
- Device-dependent scenarios must be marked `PENDING_DEVICE` until actually executed; build or JVM tests may not be reported as device acceptance.
- Every behavior change follows test-first RED -> GREEN -> REFACTOR and each task ends with a focused test command plus a commit.

---

### Task 1: MediaStore scan plus Room favorites and recent-20 flows

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `core/data/src/main/kotlin/app/yinyuehe/core/data/TrackRepository.kt`
- Modify: `core/data/src/main/kotlin/app/yinyuehe/core/data/repository/RoomTrackRepository.kt`
- Modify: `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/dao/TrackDao.kt`
- Modify: `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/dao/FavoriteDao.kt`
- Modify: `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/dao/RecentPlayDao.kt`
- Modify: `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/dao/ScanCheckpointDao.kt`
- Modify: `core/data/src/main/kotlin/app/yinyuehe/core/data/DataModule.kt`
- Create: `core/data/src/main/kotlin/app/yinyuehe/core/data/local/mediastore/MediaStoreGateway.kt`
- Create: `core/data/src/main/kotlin/app/yinyuehe/core/data/local/mediastore/AndroidMediaStoreGateway.kt`
- Create: `core/data/src/main/kotlin/app/yinyuehe/core/data/local/mediastore/MediaStoreAudio.kt`
- Create: `core/data/src/main/kotlin/app/yinyuehe/core/data/scan/LibraryScanner.kt`
- Create: `core/data/src/main/kotlin/app/yinyuehe/core/data/scan/DefaultLibraryScanner.kt`
- Test: `core/data/src/test/kotlin/app/yinyuehe/core/data/local/mediastore/MediaStoreAudioTest.kt`
- Test: `core/data/src/test/kotlin/app/yinyuehe/core/data/scan/DefaultLibraryScannerTest.kt`
- Test: `core/data/src/test/kotlin/app/yinyuehe/core/data/local/db/FavoriteRecentDaoTest.kt`
- Test: `core/data/src/test/kotlin/app/yinyuehe/core/data/repository/RoomTrackRepositoryUserDataTest.kt`

**Interfaces:**
- Produces:

```kotlin
data class ScanResult(val discovered: Int, val unavailable: Int, val volumeCount: Int)

interface LibraryScanner {
  suspend fun scan(): Result<ScanResult>
}

interface TrackRepository {
  fun observeAvailableLocalTracks(): Flow<List<Track>>
  fun demoTracks(): List<Track>
  fun observeLibrary(): Flow<LibraryContent>
  fun observeFavoriteTrackIds(): Flow<Set<TrackId>>
  fun observeFavoriteTracks(): Flow<List<Track>>
  fun observeRecentTracks(): Flow<List<Track>>
  suspend fun setFavorite(trackId: TrackId, favorite: Boolean): Boolean
  suspend fun recordRecent(trackId: TrackId, positionMs: Long? = null): Boolean
}
```

- `setFavorite` and `recordRecent` persist current catalog Demo IDs through reserved Room anchor rows; unknown Demo IDs and missing local IDs return `false`.
- `AndroidMediaStoreGateway` enumerates API 29+ external volume names and uses the legacy external collection on API 26-28. It queries only `IS_MUSIC != 0`, cleans blank/`<unknown>` metadata, closes every Cursor, and returns platform-neutral rows.
- `DefaultLibraryScanner` performs a complete snapshot per volume, maps stable IDs, then atomically upserts the completed volume and marks rows with a different scan token unavailable. A query failure skips the transaction for that volume.

- [ ] **Step 1: Write failing scanner and identity tests**

Create tests whose assertions include:

```kotlin
assertThat(stableMediaId("external_primary", 42)).isNotEqualTo(stableMediaId("sd-card", 42))
assertThat(result.getOrThrow().discovered).isEqualTo(2)
assertThat(trackDao.findByMediaId(removedId)?.isAvailable).isFalse()
```

- [ ] **Step 2: Run the scanner tests and verify RED**

Run:

```bash
./gradlew :core:data:testDebugUnitTest --tests '*MediaStoreAudioTest' --tests '*DefaultLibraryScannerTest'
```

Expected: FAIL because MediaStore gateway/scanner contracts do not exist.

- [ ] **Step 3: Implement the minimal full-snapshot scanner**

Implement the interfaces above, add `READ_MEDIA_AUDIO` for API 33+ and `READ_EXTERNAL_STORAGE` with `maxSdkVersion="32"`, and add only the DAO queries needed for per-volume upsert/unavailable reconciliation.

- [ ] **Step 4: Write failing favorites and recent tests**

The tests must prove add/remove observation, newest-first ordering, aggregation without duplicates, and the hard limit:

```kotlin
assertThat(repository.observeRecentTracks().first()).hasSize(20)
assertThat(repository.setFavorite(missingId, true)).isFalse()
assertThat(repository.observeFavoriteTrackIds().first()).contains(existingId)
```

- [ ] **Step 5: Run user-data tests and verify RED**

Run:

```bash
./gradlew :core:data:testDebugUnitTest --tests '*FavoriteRecentDaoTest' --tests '*RoomTrackRepositoryUserDataTest'
```

Expected: FAIL because the observable queries and repository operations do not exist.

- [ ] **Step 6: Implement Room/Flow favorites and recent playback**

Use `favorites` and `recent_plays` without changing v1 schema. `recordRecent` updates a single row's `lastPlayedAtEpochMs`, increments `playCount`, and stores a non-negative optional position. `observeRecentTracks()` performs a Room join and fixed `LIMIT 20`.

- [ ] **Step 7: Run focused data verification**

Run:

```bash
./gradlew :core:data:testDebugUnitTest
```

Expected: PASS with zero failing tests.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/AndroidManifest.xml core/data
git commit -m "feat: add MediaStore library and Room user data"
```

---

### Task 2: Full Media3 controls, noisy handling, playback history, and analytics

**Files:**
- Modify: `core/player/src/main/kotlin/app/yinyuehe/core/player/PlaybackController.kt`
- Modify: `core/player/src/main/kotlin/app/yinyuehe/core/player/PlaybackState.kt`
- Modify: `core/player/src/main/kotlin/app/yinyuehe/core/player/PlayerSnapshot.kt`
- Modify: `core/player/src/main/kotlin/app/yinyuehe/core/player/Media3PlaybackController.kt`
- Modify: `core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackService.kt`
- Modify: `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/YinYueHeDatabase.kt`
- Modify: `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/DatabaseModule.kt`
- Create: `core/common/src/main/kotlin/app/yinyuehe/core/common/analytics/PlaybackEvent.kt`
- Create: `core/common/src/main/kotlin/app/yinyuehe/core/common/analytics/PlaybackEventRecorder.kt`
- Create: `core/common/src/main/kotlin/app/yinyuehe/core/common/analytics/PlaybackHistoryRecorder.kt`
- Create: `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/entity/PlaybackEventEntity.kt`
- Create: `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/dao/PlaybackEventDao.kt`
- Create: `core/data/src/main/kotlin/app/yinyuehe/core/data/analytics/RoomPlaybackEventRecorder.kt`
- Create: `core/data/src/main/kotlin/app/yinyuehe/core/data/analytics/RoomPlaybackHistoryRecorder.kt`
- Create: `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/DatabaseMigrations.kt`
- Test: `core/player/src/test/kotlin/app/yinyuehe/core/player/PlayerSnapshotTest.kt`
- Test: `core/player/src/test/kotlin/app/yinyuehe/core/player/PlaybackCommandTest.kt`
- Test: `core/player/src/test/kotlin/app/yinyuehe/core/player/PlaybackTimingTrackerTest.kt`
- Test: `core/data/src/test/kotlin/app/yinyuehe/core/data/analytics/RoomPlaybackEventRecorderTest.kt`
- Test: `core/data/src/androidTest/kotlin/app/yinyuehe/core/data/local/db/YinYueHeMigration1To2Test.kt`

**Interfaces:**
- Consumes: Task 1 `TrackRepository.recordRecent` through a `PlaybackHistoryRecorder` implementation in `:core:data`.
- Produces:

```kotlin
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
}

data class PlaybackState(
  val connection: PlaybackConnection = PlaybackConnection.CONNECTING,
  val currentTrackId: TrackId? = null,
  val currentIndex: Int = -1,
  val isPlaying: Boolean = false,
  val positionMs: Long = 0,
  val durationMs: Long = 0,
  val queueTrackIds: List<TrackId> = emptyList(),
  val shuffleEnabled: Boolean = false,
  val canSeek: Boolean = false,
  val canPrevious: Boolean = false,
  val canNext: Boolean = false,
)
```

- Typed event names are `PLAY_REQUESTED`, `PLAYBACK_STARTED`, `TRACK_CHANGED`, `PLAYBACK_COMPLETED`, `FAVORITE_CHANGED`, `FIRST_FRAME`, and `PLAY_START_LATENCY`.
- Playback events are Room v2 rows ordered newest-first and trimmed to 500. Migration `1 -> 2` only adds this table and its indexes and must preserve v1 data.

- [ ] **Step 1: Expand snapshot/command tests and verify RED**

Tests cover current index, shuffle, seekability, previous/next availability, invalid queue indexes, `C.TIME_UNSET`, and each domain command's intended Media3 operation.

Run:

```bash
./gradlew :core:player:testDebugUnitTest --tests '*PlayerSnapshotTest' --tests '*PlaybackCommandTest'
```

Expected: FAIL because the state fields and commands are absent.

- [ ] **Step 2: Implement controller commands and state mapping**

Map commands to `addMediaItem`, `removeMediaItem`, `seekToDefaultPosition`, `seekToPreviousMediaItem`, `seekToNextMediaItem`, `seekTo`, and `shuffleModeEnabled`. Validate indexes against the live controller queue. Keep the existing 500 ms position ticker and callback-only state publication.

- [ ] **Step 3: Add noisy handling and service-owned history events**

Set `ExoPlayer.Builder.setHandleAudioBecomingNoisy(true)` while retaining `setAudioAttributes(attributes, true)`. Annotate/inject the service as needed so `onMediaItemTransition`, first `onIsPlayingChanged(true)`, and `STATE_ENDED` record history/events once even when actions originated from notification or hardware controls.

- [ ] **Step 4: Write analytics/timing/migration tests and verify RED**

Tests assert the 500-row cap, newest-first Flow, play-start latency emitted only once per request, and v1 rows preserved by migration.

Run:

```bash
./gradlew :core:data:testDebugUnitTest --tests '*RoomPlaybackEventRecorderTest' :core:player:testDebugUnitTest --tests '*PlaybackTimingTrackerTest'
```

Expected: FAIL because analytics storage and timing tracker are absent.

- [ ] **Step 5: Implement typed analytics and start-latency measurement**

Record `PLAY_REQUESTED` immediately before `setMediaItems`; retain a monotonic pending timestamp; on the first callback where the requested track is playing, emit `PLAY_START_LATENCY(durationMs)` and clear it. Never include title, file name, or source path in event rows.

- [ ] **Step 6: Run focused player/data verification**

Run:

```bash
./gradlew :core:player:testDebugUnitTest :core:data:testDebugUnitTest
```

Expected: PASS with zero failing tests.

- [ ] **Step 7: Commit**

```bash
git add core/common core/data core/player
git commit -m "feat: complete Media3 controls and playback analytics"
```

---

### Task 3: Three Compose pages and Player-driven MVVM/UDF

**Files:**
- Modify: `feature/library/build.gradle.kts`
- Replace: `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryUiState.kt`
- Replace: `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryViewModel.kt`
- Replace: `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryScreen.kt`
- Modify: `app/src/main/kotlin/app/yinyuehe/MainActivity.kt`
- Modify: `app/src/main/kotlin/app/yinyuehe/YinYueHeApp.kt`
- Create: `feature/library/src/main/kotlin/app/yinyuehe/feature/library/MusicBoxDestination.kt`
- Create: `feature/library/src/main/kotlin/app/yinyuehe/feature/library/MusicBoxAction.kt`
- Create: `feature/library/src/main/kotlin/app/yinyuehe/feature/library/HomeScreen.kt`
- Create: `feature/library/src/main/kotlin/app/yinyuehe/feature/library/PlayerScreen.kt`
- Create: `feature/library/src/main/kotlin/app/yinyuehe/feature/library/PlaylistsScreen.kt`
- Test: `feature/library/src/test/kotlin/app/yinyuehe/feature/library/LibraryViewModelTest.kt`
- Test: `feature/library/src/androidTest/kotlin/app/yinyuehe/feature/library/LibraryScreenTest.kt`
- Test: `app/src/androidTest/kotlin/app/yinyuehe/AppLaunchTest.kt`

**Interfaces:**
- Consumes: Task 1 `TrackRepository` user-data flows and scanner; Task 2 `PlaybackController.state`, queue commands, and `PlaybackEventRecorder`.
- `MusicBoxDestination` has exactly `HOME`, `PLAYER`, and `PLAYLISTS`.
- `MusicBoxAction` covers destination selection, permission result/request, rescan, play one/all/random, toggle pause, previous, next, seek, add/remove/jump queue, and favorite toggle.
- `LibraryUiState` is immutable and contains the active destination, library tracks/source, favorite IDs/tracks, recent tracks, the exact `PlaybackState`, scan/permission flags, and a user-visible error code.

- [ ] **Step 1: Write ViewModel UDF tests and verify RED**

Tests prove that controller callbacks change `uiState.playback`, play-all uses index 0, random calls `play(..., shuffle = true)`, selecting a track opens Player after acceptance, favorites invoke the repository and emit `FAVORITE_CHANGED`, and all queue/seek actions delegate once.

Run:

```bash
./gradlew :feature:library:testDebugUnitTest
```

Expected: FAIL because three-page actions/state are absent.

- [ ] **Step 2: Implement the combined ViewModel state and action reducer**

Combine Repository library/favorite/recent flows with `PlaybackController.state`; never copy or independently mutate playback facts. Trigger a scan only after permission is granted. Keep all controller calls in ViewModel methods/coroutines.

- [ ] **Step 3: Write three-page Compose tests and verify RED**

Use semantic tags `destination-home`, `destination-player`, `destination-playlists`, `home-track-list`, `player-queue`, and `playlists-recent`. Verify every destination is reachable and callbacks fire for play/pause, previous/next, seek, queue jump/remove, play-all/random, and favorite.

Run:

```bash
./gradlew :feature:library:compileDebugAndroidTestKotlin
```

Expected: FAIL because the page composables and semantics do not exist.

- [ ] **Step 4: Implement Home, Player, and Playlists pages**

Use one small shell with a Material 3 bottom navigation and three separate page files. Home renders the active Demo-or-local library and scan/permission state. Player renders current metadata, progress, controls and queue. Playlists renders the system Favorites and Recent collections with play-all/random actions. Keep 48dp controls and stable `TrackId` keys.

- [ ] **Step 5: Add version-correct permission launcher and first-frame event**

At the Compose/Activity boundary, request `READ_MEDIA_AUDIO` on API 33+ or `READ_EXTERNAL_STORAGE` on API 26-32, pass the result as an action, and keep Demo tracks usable when denied. Capture app start with a monotonic clock and emit `FIRST_FRAME` from an actual frame callback (`withFrameNanos`/draw listener), not from composition entry alone.

- [ ] **Step 6: Run focused UI verification**

Run:

```bash
./gradlew :feature:library:testDebugUnitTest :feature:library:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin
```

Expected: PASS with zero compilation/test failures.

- [ ] **Step 7: Commit**

```bash
git add app feature/library
git commit -m "feat: add three-page playback experience"
```

---

### Task 4: Reproducible quality evidence and 21-scenario acceptance matrix

**Files:**
- Modify: `README.md`
- Modify: `.github/workflows/ci.yml`
- Create: `verification/acceptance-scenarios.md`
- Create: `verification/result-2026-07-14.md`

**Interfaces:**
- Produces exactly 21 numbered acceptance scenarios covering permission/MediaStore, library fallback, playback and queue, favorites/recent persistence, background/system controls, AudioFocus/noisy, analytics, first-frame/start timing, and process restart.
- Each row has `AUTOMATED_PASS`, `MANUAL_PASS`, `PENDING_DEVICE`, or `FAIL`, plus evidence. No row is marked pass without an executed command or device record.

- [ ] **Step 1: Write the 21-scenario matrix**

Number rows `F01` through `F21` exactly once. Mark device-only rows pending unless a named device/API, timestamp, and evidence path exist.

- [ ] **Step 2: Run the complete local release gate**

Run:

```bash
./gradlew test testDebugUnitTest lintDebug assembleDebug --stacktrace
```

Expected: `BUILD SUCCESSFUL`, zero failing tests, successful `lintDebug`, and `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 3: Record reproducible evidence**

Write the exact commit, JDK/SDK versions, command, exit code, automated scenario results, manual pending rows, APK size, and SHA-256 to `verification/result-2026-07-14.md`. Update README with the implemented feature matrix and build command.

- [ ] **Step 4: Keep CI aligned with the local gate**

Ensure pull requests and pushes to `main` use JDK 17, isolated SDK 36, and the same `test testDebugUnitTest lintDebug assembleDebug --stacktrace` command. Do not add a check that cannot run on GitHub-hosted runners.

- [ ] **Step 5: Commit**

```bash
git add README.md .github/workflows/ci.yml verification
git commit -m "docs: add resume parity verification evidence"
```

---

## Final Gate

After all task reviews are clean:

1. Generate a whole-branch review package from `origin/main...HEAD` and dispatch an independent final reviewer.
2. Fix every Critical/Important finding in one fix wave and re-review.
3. Run fresh `./gradlew test testDebugUnitTest lintDebug assembleDebug --stacktrace`.
4. Push `feature/resume-parity`, open a PR to `main`, wait for GitHub Actions, fix failures, and merge only after all gates pass.
5. Do not report notification, AudioFocus, noisy, or process-restart device scenarios as passed unless the acceptance matrix contains real device evidence.
