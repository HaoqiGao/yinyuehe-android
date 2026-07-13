# M2-D Searchable Library Views Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the local library with safe search/sort/filter queries, song/album/artist/folder views, restorable UI state, deterministic 1000-track evidence, and API 26/30/33/36 MediaStore-to-MediaSession acceptance.

**Architecture:** Pure query/group values live in `core:common`. `core:data` builds only enum-whitelisted SQL plus bound user arguments and observes Room through `@RawQuery`; the M2-C base source gate decides Demo versus Local independently from query result size. `feature:library` restores query/navigation values with `SavedStateHandle`, renders group summaries or tracks, and always sends the currently visible track list to playback.

**Tech Stack:** Kotlin 2.0.20, Room 2.8.4, SQLite `LIKE`, Coroutines/Flow 1.9.0, Compose Material 3, SavedStateHandle, Media3 1.10.1, Android API 26–36.

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

- Start only after M2-C is squash-merged and `main` CI is green. Use `superpowers:using-git-worktrees`; branch: `feature/m2d-library-search-views`.
- Do not change Room v1, its schema JSON, a column, an index, or database version. If implementation requires a physical change, stop and obtain approval for a real v2 migration and migration test.
- Do not add a module, FTS, Paging, debounce, online data, playlists, favorite/recent screens, notification behavior, queue restoration, or performance claims.
- All user search/filter/group keys are SQLite bound arguments. Only fixed enum-to-column/expression mappings and `ASC`/`DESC` enter SQL text.
- Querying zero rows while the M2-C base gate is Local must emit `LibraryContent(LOCAL, emptyList())`; it means “no matches,” not Demo fallback. Only the base permission/cache gate may select Demo.
- Demo is shown as the Songs view, unfiltered and ungrouped. Query controls are disabled while Demo is the source, but the user's last Local view/query remain saved for re-grant.
- Album keys are volume-qualified when `albumId` exists; folder keys rely on M2-B's volume qualification; fallback keys are collision-safe. Every list order ends with a deterministic key.
- 1000-track checks prove correctness/basic usability only. Startup, frame, memory, and stability measurements remain M6.
- Each task uses a fresh implementation subagent, then specification-compliance and code-quality reviewers. Resolve all Critical/Important findings before the next task.
- Design source of truth: `docs/superpowers/specs/2026-07-13-m2-local-library-design.md`.

## File Structure

### Modify

- `core/data/build.gradle.kts`
- `feature/library/build.gradle.kts`
- `app/build.gradle.kts`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/TrackRepository.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/repository/RoomTrackRepository.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/dao/TrackDao.kt`
- `core/testing/src/main/kotlin/app/yinyuehe/core/testing/FakeTrackRepository.kt`
- `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryViewModel.kt`
- `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryUiState.kt`
- `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryStatus.kt`
- `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryScreen.kt`
- `feature/library/src/main/res/values/strings.xml`
- `feature/library/src/test/kotlin/app/yinyuehe/feature/library/LibraryViewModelTest.kt`
- `feature/library/src/androidTest/kotlin/app/yinyuehe/feature/library/LibraryScreenTest.kt`

### Create

- `core/common/src/main/kotlin/app/yinyuehe/core/common/model/LibraryQuery.kt`
- `core/common/src/test/kotlin/app/yinyuehe/core/common/model/LibraryQueryTest.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/query/BoundSql.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/query/LibraryQuerySqlFactory.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/query/LibraryGroupRow.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/query/LibraryGroupRowMapper.kt`
- `core/data/src/test/kotlin/app/yinyuehe/core/data/local/db/query/LibraryQuerySqlFactoryTest.kt`
- `core/data/src/test/kotlin/app/yinyuehe/core/data/local/db/query/TrackQueryDaoTest.kt`
- `core/data/src/test/kotlin/app/yinyuehe/core/data/repository/RoomTrackRepositoryQueryTest.kt`
- `core/data/src/test/kotlin/app/yinyuehe/core/data/repository/LibraryGroupingRepositoryTest.kt`
- `core/data/src/test/kotlin/app/yinyuehe/core/data/testing/LibraryFixtureFactoryTest.kt`
- `core/data/src/testFixtures/kotlin/app/yinyuehe/core/data/testing/LibraryTrackFixture.kt`
- `core/data/src/testFixtures/kotlin/app/yinyuehe/core/data/testing/LibraryFixtureFactory.kt`
- `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryRoute.kt`
- `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryQueryControls.kt`
- `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryTrackList.kt`
- `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryGroupList.kt`
- `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryFilterSheet.kt`
- `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryTestTags.kt`
- `feature/library/src/main/kotlin/app/yinyuehe/feature/library/SelectedLibraryGroup.kt`
- `feature/library/src/test/kotlin/app/yinyuehe/feature/library/LibraryViewModelQueryTest.kt`
- `feature/library/src/androidTest/kotlin/app/yinyuehe/feature/library/LibraryLargeFixtureScreenTest.kt`
- `app/src/debug/kotlin/app/yinyuehe/M2AcceptanceEntryPoint.kt`
- `app/src/androidTest/kotlin/app/yinyuehe/M2PermissionAndRealPlaybackTest.kt`
- `app/src/androidTest/kotlin/app/yinyuehe/M2RevokedPermissionTest.kt`
- `scripts/run-m2-device-acceptance.sh`
- `docs/testing/m2-device-matrix.md`

---

### Task 1: Define query values and a deterministic 1000-track fixture

**Files:** `LibraryQuery.kt`, its tests, data test-fixture files, and build files.

**Domain contract:**

```kotlin
enum class LibraryView { SONGS, ALBUMS, ARTISTS, FOLDERS }
enum class TrackSortField { TITLE, ARTIST, ALBUM, DATE_ADDED, DURATION }
enum class SortDirection { ASCENDING, DESCENDING }

data class TrackSort(
  val field: TrackSortField = TrackSortField.TITLE,
  val direction: SortDirection = SortDirection.ASCENDING,
)

data class TrackFilter(
  val mimeTypes: Set<String> = emptySet(),
  val minimumDurationMs: Long? = null,
  val folderKey: String? = null,
) {
  init {
    require(mimeTypes.all { it.isNotBlank() })
    require(minimumDurationMs == null || minimumDurationMs >= 0)
    require(folderKey == null || folderKey.isNotBlank())
  }
}

@JvmInline
value class LibraryGroupKey(val value: String) {
  init { require(value.isNotBlank()) }
}

data class LibraryGroupRef(
  val view: LibraryView,
  val key: LibraryGroupKey,
) {
  init { require(view != LibraryView.SONGS) }
}

data class TrackQuery(
  val searchText: String = "",
  val sort: TrackSort = TrackSort(),
  val filter: TrackFilter = TrackFilter(),
  val group: LibraryGroupRef? = null,
)

data class LibraryGroupSummary(
  val ref: LibraryGroupRef,
  val displayName: String?,
  val trackCount: Int,
  val artworkUri: String?,
) {
  init { require(trackCount > 0) }
}
```

- [ ] **Step 1: Write red invariant/default tests**

Test all defaults, reject blank MIME/folder/group keys, reject a Songs group, reject negative duration, and accept Unicode/raw search strings without normalization at the UI value layer.

```bash
./gradlew :core:common:test --tests '*LibraryQueryTest' --stacktrace
```

Expected red state: query types do not exist.

- [ ] **Step 2: Implement the pure types and enable Android test fixtures**

Add:

```kotlin
android {
  testFixtures { enable = true }
}
```

to `core:data`. Expose its test fixtures to Feature/App tests with `testImplementation(testFixtures(project(":core:data")))` and `androidTestImplementation(testFixtures(project(":core:data")))`. Do not put the fixture in `core:testing`: that module already depends on `core:data`, so a reverse dependency would form a cycle.

- [ ] **Step 3: Create and test the fixed fixture**

`LibraryTrackFixture` is a public test-only DTO, not a Room Entity. `LibraryFixtureFactory.create(count = 1_000)` returns stable rows without random values and includes Chinese/English metadata, null titles/artists/albums, literal `%`, `_`, and `\` inside searchable title/artist fields, two volumes, present/missing album IDs, MP3/FLAC/OGG/WAV, 20 volume-qualified folders, deterministic durations/dates/media IDs, and fixed first/last identities. Unit-test exact count, uniqueness, category coverage, and deterministic equality across two runs.

```bash
./gradlew :core:data:compileDebugTestFixturesKotlin \
  :core:data:testDebugUnitTest --tests '*LibraryFixtureFactoryTest' --stacktrace
```

Expected: `BUILD SUCCESSFUL`; fixture generation itself performs no I/O and uses no current time.

- [ ] **Step 4: Commit Task 1**

```bash
git add core/common core/data/build.gradle.kts core/data/src/testFixtures \
  core/data/src/test feature/library/build.gradle.kts app/build.gradle.kts
git commit -m "feat: define library query contracts"
```

---

### Task 2: Build safe bound SQL for tracks and verify 1000 rows

**Files:** `BoundSql.kt`, `LibraryQuerySqlFactory.kt`, Track DAO, SQL/DAO tests.

**DAO boundary:**

```kotlin
@RawQuery(observedEntities = [TrackEntity::class])
fun observeTracks(query: SupportSQLiteQuery): Flow<List<TrackEntity>>

internal data class BoundSql(
  val statement: String,
  val arguments: List<Any>,
) {
  fun asQuery(): SupportSQLiteQuery =
    SimpleSQLiteQuery(statement, arguments.toTypedArray())
}
```

- [ ] **Step 1: Write SQL factory security tests**

Test blank normalization, English case, Chinese text, literal `\`, `%`, `_`, quote/SQL-comment payloads, sorted MIME values, minimum duration, folder filter, all filter combinations, and all five sort fields in both directions. Assert hostile input occurs only in `arguments`, never in `statement`. Every generated ORDER BY must end `, mediaId ASC`.

Use this exact escaping rule after `trim`, repeated-whitespace collapse, and `lowercase(Locale.ROOT)`:

```kotlin
internal fun escapeLike(value: String): String =
  buildString {
    value.forEach { character ->
      if (character == '\\' || character == '%' || character == '_') append('\\')
      append(character)
    }
  }
```

```bash
./gradlew :core:data:testDebugUnitTest --tests '*LibraryQuerySqlFactoryTest' --stacktrace
```

Expected red state: query factory and RawQuery DAO method are absent.

- [ ] **Step 2: Implement whitelist-only track queries**

Start with `SELECT * FROM tracks WHERE isAvailable = 1`. Add `searchText LIKE ? ESCAPE '\'`, an `IN` clause containing exactly one `?` for each sorted MIME input, `durationMs >= ?`, `folderKey = ?`, and group expression equality only when their domain values are present. Bind search by concatenating a literal `%`, the escaped normalized value, and a trailing literal `%`; sort MIME inputs before binding for deterministic tests.

Map sort fields only through this fixed table:

| Field | Column |
| --- | --- |
| `TITLE` | `titleSortKey` |
| `ARTIST` | `artistSortKey` |
| `ALBUM` | `albumSortKey` |
| `DATE_ADDED` | `dateAddedSeconds` |
| `DURATION` | `durationMs` |

Direction maps only to literal `ASC` or `DESC`; append `mediaId ASC` regardless of direction.

- [ ] **Step 3: Write and pass real Room query tests**

Insert fixture entities in chunks of 100. Verify escaped literal matches, blank search, case-insensitive English, Chinese, each sort/direction/tie-break, MIME/duration/folder filters and combinations, unavailable exclusion, and query cancellation/re-observation after an entity update. The persisted `searchText` must be M2-B's exact `title + artist + album + folderDisplayName` normalized sequence; display name is not searchable.

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*TrackQueryDaoTest' --stacktrace
```

Expected: all correctness assertions pass for the fixed 1000 rows without Paging or FTS.

- [ ] **Step 4: Commit Task 2**

```bash
git add core/data/src/main/kotlin/app/yinyuehe/core/data/local/db \
  core/data/src/test/kotlin/app/yinyuehe/core/data/local/db
git commit -m "feat: query local tracks safely"
```

---

### Task 3: Add collision-safe grouping and permission-aware repository queries

**Files:** group row/mapper, SQL factory, DAO, repository interface/implementation/fake, and repository tests.

**Repository contract:**

```kotlin
interface TrackRepository {
  fun observeAvailableLocalTracks(): Flow<List<Track>>
  fun demoTracks(): List<Track>
  fun observeLibrary(): Flow<LibraryContent>
  fun observeLibrary(query: TrackQuery): Flow<LibraryContent>
  fun observeGroups(
    view: LibraryView,
    query: TrackQuery,
  ): Flow<List<LibraryGroupSummary>>
  fun observeTracks(): Flow<List<Track>> = observeLibrary().map(LibraryContent::tracks)
}

internal data class LibraryGroupRow(
  val groupKey: String,
  val displayName: String?,
  val trackCount: Long,
  val artworkUri: String?,
)

@RawQuery(observedEntities = [TrackEntity::class])
fun observeGroups(query: SupportSQLiteQuery): Flow<List<LibraryGroupRow>>
```

- [ ] **Step 1: Write grouping-key and aggregation tests first**

Cover same album ID on two volumes, missing album ID fallback, delimiter/Unicode values, unknown album/artist/folder, same folder display name on two volumes, artwork stability, unavailable rows, search/filter applied before aggregation, summary/detail count equality, and deterministic order.

Use fixed SQL key expressions selected only by `LibraryView`:

```sql
-- ALBUMS
CASE WHEN albumId IS NOT NULL
  THEN 'album:id:' || hex(CAST(volumeName AS BLOB)) || ':' || albumId
  ELSE 'album:fallback:' || hex(CAST(albumSortKey AS BLOB)) || ':' ||
       hex(CAST(artistSortKey AS BLOB))
END

-- ARTISTS
'artist:' || hex(CAST(artistSortKey AS BLOB))

-- FOLDERS
CASE WHEN folderKey IS NULL
  THEN 'folder:unknown'
  ELSE 'folder:' || hex(CAST(folderKey AS BLOB))
END
```

Display names and artwork use deterministic aggregate selection and counts use `COUNT(*)`. Each grouped SELECT also emits an aggregate sort alias: `MIN(albumSortKey) AS groupSortKey`, `MIN(artistSortKey) AS groupSortKey`, or `MIN(folderSortKey) AS groupSortKey` for its fixed view. Map `ASCENDING` to `ORDER BY groupSortKey ASC, groupKey ASC` and `DESCENDING` to `ORDER BY groupSortKey DESC, groupKey ASC`; no other direction text is accepted. Root groups never order by a non-aggregated row column, because rows sharing an album/group key may have inconsistent metadata. `TrackSort.field` is ignored for root summaries. A group-detail condition compares the selected fixed expression to one bound `LibraryGroupKey` argument.

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*LibraryGroupingRepositoryTest' --stacktrace
```

Expected red state: group RawQuery and repository API are absent.

- [ ] **Step 2: Implement group summaries and mapping**

Reject `LibraryView.SONGS` in `observeGroups`. Apply search/MIME/duration/folder filters before `GROUP BY`. Use `MIN` for a deterministic non-null representative display/artwork value and range-check `COUNT(*)` before mapping Long to Int. Group names are allowed to remain null; the UI supplies localized unknown labels.

- [ ] **Step 3: Lock the source-gate behavior with repository tests**

Implement query content as a combination of the no-argument M2-C base content and the DAO query:

```kotlin
combine(observeLibrary(), trackDao.observeTracks(sqlFactory.tracks(query).asQuery())) {
    base,
    queried,
  ->
  if (base.source == LibrarySource.DEMO) {
    base
  } else {
    LibraryContent(LibrarySource.LOCAL, queried.map { it.toDomain() })
  }
}
```

Verify Local plus zero search/filter rows stays `LOCAL` with an empty list; Demo stays the complete unfiltered Demo catalog; denied cached rows never leak through query APIs; Local queries never mix Demo; and groups emit empty when the base source is Demo. Folder option queries clear both `group` and `filter.folderKey` before asking for folder summaries so the option list does not filter itself.

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*RoomTrackRepositoryQueryTest' \
  --tests '*LibraryGroupingRepositoryTest' --stacktrace
git diff --exit-code -- core/data/schemas/app.yinyuehe.core.data.local.db.YinYueHeDatabase/1.json
```

Expected: tests pass and Room v1 is unchanged.

- [ ] **Step 4: Commit Task 3**

```bash
git add core/data core/testing
git commit -m "feat: expose grouped library queries"
```

---

### Task 4: Restore and coordinate query/view/detail state

**Files:** ViewModel, UiState/status, selected-group value, fake repository, and ViewModel query tests.

**Saved keys:**

```text
library.view
library.query.search
library.query.sort_field
library.query.sort_direction
library.query.mime_types
library.query.minimum_duration_ms
library.query.folder_key
library.group.view
library.group.key
library.group.name
```

- [ ] **Step 1: Write SavedStateHandle and state-transition tests**

Test every query action, process-style recreation through a real `Bundle`-compatible saved-state round trip, invalid enum/negative/corrupt values falling back safely, sorted MIME persistence as an `ArrayList<String>`, root-view change clearing group detail, group back preserving query, folder options ignoring current folder, and selected group retaining its display name when a later search empties the summary list. The round-trip test must fail if a raw Kotlin `Set` is assigned to `SavedStateHandle`, because that value is not guaranteed to survive Android process recreation.

Test source behavior: Demo renders effective `SONGS` and disables controls while preserving the saved Local view; returning Local restores that view. Local base cache plus zero queried tracks/groups maps to new status `NO_MATCHING_RESULTS`, while truly empty base cache remains `NO_LOCAL_MUSIC`.

```bash
./gradlew :feature:library:testDebugUnitTest \
  --tests '*LibraryViewModelQueryTest' --stacktrace
```

Expected red state: ViewModel has no query/SavedStateHandle/group coordination.

- [ ] **Step 2: Implement query actions and `flatMapLatest` observations**

Add typed actions for search, root view, sort, MIME set, minimum duration, folder, group open, and group back. Save each sanitized value immediately. Persist MIME values only as a sorted `ArrayList<String>` and restore them into an immutable `Set`; never put `Set<String>` directly into `SavedStateHandle`. Persist enum names as strings and minimum duration as a nullable `Long`, with corruption-safe readers for every key. Observe tracks for Songs/group detail and summaries for Album/Artist/Folder roots. Do not query groups for Demo. Search changes are immediate; do not add debounce for a 1000-row target.

Extend UiState with effective/current Local view, `TrackQuery`, group summaries, selected group, folder options, sorted distinct MIME options derived from the raw Local cache only while the base source is Local, and `queryControlsEnabled`. Keep M2-C permission/scan/status fields intact; when the source is Demo, both MIME and folder options are empty so cached metadata is not exposed.

- [ ] **Step 3: Prove playback uses only the visible query result**

For Songs and each group detail, click a middle result and assert the fake controller receives exactly the current sorted/filtered visible list and index. A track absent from current results is ignored. Group-root clicks navigate and never invoke playback.

```bash
./gradlew :feature:library:testDebugUnitTest \
  --tests '*LibraryViewModelTest' --tests '*LibraryViewModelQueryTest' --stacktrace
```

Expected: `BUILD SUCCESSFUL` with existing permission/scanner tests still green.

- [ ] **Step 4: Commit Task 4**

```bash
git add feature/library/src/main core/testing feature/library/src/test
git commit -m "feat: coordinate searchable library state"
```

---

### Task 5: Render four views, details, filters, and the 1000-item list

**Files:** route/screen split, query/group/track/filter components, resources, test tags, and Compose tests.

- [ ] **Step 1: Write Compose interaction tests before UI components**

Cover the four root selectors, search action, all sorts/directions, MIME/duration/folder filters, group summary count, group open/back (toolbar and system back), unknown localized names, no-match status, disabled Demo controls, stable track/group test tags, and M2-C status cards remaining visible without covering content.

Freeze semantic tags and Lazy keys in `LibraryTestTags`; do not construct alternate forms in individual composables:

```kotlin
fun track(id: TrackId): String = "track:${id.value}"
fun group(ref: LibraryGroupRef): String = "group:${ref.view.name}:${ref.key.value}"

// Lazy keys remain the raw stable identities:
track.id.value
"${group.ref.view.name}:${group.ref.key.value}"
```

```bash
./gradlew :feature:library:assembleDebugAndroidTest --stacktrace
```

Expected red state: split components/tags and callbacks are absent.

- [ ] **Step 2: Implement the focused Compose files**

Move `LibraryRoute` out of the old screen file. Use `BackHandler(selectedGroup != null)` to call group back; do not add Navigation. Keep a single LazyColumn for the visible screen. Render resource-backed unknown song/album/artist/folder labels, stable semantics, and a compact filter sheet. Preserve the M2-C Snackbar/effect collector and status card.

- [ ] **Step 3: Add the fixed 1000-item UI test**

Map the shared fixture into UiState, switch views and search, then call `performScrollToNode(hasTestTag(LibraryTestTags.track(fixedLastId)))` on the list and assert that tagged row is displayed/clickable. Do not use `performScrollToIndex(999)`: status cards, headers, or controls can change the LazyColumn's semantic index without changing the 1000-track requirement. Also verify duplicate primary sort values retain deterministic media-ID order after an updated state is supplied.

With an API 36 emulator running:

```bash
./gradlew :feature:library:connectedDebugAndroidTest --stacktrace
```

Expected: all four views and the 1000-item basic interaction test pass. Do not report timing or memory numbers.

- [ ] **Step 4: Commit Task 5**

```bash
git add feature/library
git commit -m "feat: add complete local library views"
```

---

### Task 6: Verify real MediaStore permission, playback, revocation, and all PR gates

**Files:** debug Hilt entry point, two App instrumentation tests, acceptance script, and actual evidence document.

- [ ] **Step 1: Add the debug-only acceptance entry point and dependencies**

The Hilt entry point installed in `SingletonComponent` exposes `TrackRepository`, `LibraryScanner`, and `PlaybackController`. Add App androidTest dependencies on Media3 session and coroutines-guava so the test can connect an independent controller to `PlaybackService`. No acceptance-only permission enters the release Manifest.

- [ ] **Step 2: Write the real permission/scan/playback test**

The host script first uninstalls both target and test packages, installs fresh debug/androidTest APKs, and asserts the SDK-specific audio-read permission is denied. It then pushes the existing `core/data/src/main/res/raw/demo_soft_echo.wav` to `/sdcard/Music/YinYueHeM2/m2_acceptance.wav` and broadcasts `MEDIA_SCANNER_SCAN_FILE`; it does not grant the audio-read permission.

`M2PermissionAndRealPlaybackTest` then:

1. asserts the SDK-specific audio permission is denied, launches `MainActivity`, and verifies Demo plus the permission explanation;
2. taps the stable audio-permission test tag;
3. uses platform `UiAutomation` accessibility nodes to assert the system Permission Controller is foreground and activates its version-appropriate Allow button;
4. on API 33/36 asserts `POST_NOTIFICATIONS` remains ungranted and no second permission request appears;
5. queries the now-authorized MediaStore row by the unique fixture display name, waits for matching Local content, and asserts the Track `sourceUri` equals that exact MediaStore Content URI;
6. connects an independent Media3 `MediaController` to `PlaybackService`, registers its listener, asserts `COMMAND_SET_REPEAT_MODE` is available, and sets `REPEAT_MODE_ONE` before playback so the 3.8-second fixture cannot finish during the remaining assertions;
7. clicks test tag `track:<mediaId>`;
8. waits until domain `PlaybackController.state` has the same `currentTrackId` and `isPlaying = true`;
9. asserts the independent controller has the same `currentMediaItem.mediaId`, `isPlaying = true`, and `playbackState == Player.STATE_READY`;
10. while playback is still active, reads `dumpsys media_session` through `InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand`, closes the returned descriptor, and asserts this package/session is present with a playing state; it emits only a sanitized typed pass/fail marker, never the raw dump or personal metadata;
11. restores `REPEAT_MODE_OFF` and releases only the test controller after all three playback layers have passed, leaving production session ownership unchanged.

The test locates Allow through a fixed resource-ID list for AOSP `com.android.permissioncontroller` and legacy `com.android.packageinstaller`; it fails rather than coordinate-tapping if no known button exists.

- [ ] **Step 3: Capture the live session, then write revocation as a separate process test**

The first instrumentation test itself captures and validates `dumpsys media_session` through `UiAutomation` before repeat-one is cleared and before its process exits. The host records the sanitized instrumentation result; it must never infer a live session from a dump taken after that process has exited.

After the first instrumentation command exits successfully, the host revokes `READ_EXTERNAL_STORAGE` on API 26/30 or `READ_MEDIA_AUDIO` on API 33/36 and launches `M2RevokedPermissionTest`. Because `pm revoke` may kill the app, never combine these phases in one instrumentation process.

The second test asserts persisted Local cache exists through `observeAvailableLocalTracks()`, public `observeLibrary()` emits Demo only, `LibraryScanner.state` stays `Idle`, the real media ID/tag is absent, and a Demo row remains clickable. This proves the denied restart does not query MediaStore.

- [ ] **Step 4: Implement and run the API 26/30/33/36 host script**

`scripts/run-m2-device-acceptance.sh` inherits and re-validates the JDK 17 preflight, requires `ANDROID_SERIAL`, rejects SDKs outside `26 30 33 36`, builds APKs, performs a fresh uninstall/install of both packages, verifies the correct audio permission is initially denied, seeds/scans the WAV, runs the first instrumentation phase and requires its sanitized live-session assertion, then performs the correct permission revoke and second instrumentation phase. It stores sanitized package/session output under `/tmp/yinyuehe-m2-<sdk>/`, re-grants for cleanup, removes the seeded file, deletes its MediaStore row (or broadcasts a scan after deletion and waits until the row disappears), and exits nonzero on any timeout or missing assertion.

Run once against each configured emulator/device:

```bash
ANDROID_SERIAL="$ANDROID_SERIAL" scripts/run-m2-device-acceptance.sh
```

Create `docs/testing/m2-device-matrix.md` only from actual runs. For each API record system image, serial alias (not a personal device identifier), requested permission, audio dialog result, absence of notification request on API 33/36, scan/content-URI result, domain playback result, independent Media3/session result, revoked-content result, date, commit SHA, and pass/fail. Include commands and sanitized log paths; do not include titles/paths from personal media.

- [ ] **Step 5: Run the full code and schema gate**

```bash
./gradlew test testDebugUnitTest lintDebug assembleDebug --stacktrace
./gradlew :core:data:assembleDebugAndroidTest :feature:library:assembleDebugAndroidTest \
  :app:assembleDebugAndroidTest --stacktrace
./gradlew :app:assembleRelease --stacktrace
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
APKANALYZER="$SDK_ROOT/cmdline-tools/latest/bin/apkanalyzer"
RELEASE_APK="app/build/outputs/apk/release/app-release-unsigned.apk"
test -x "$APKANALYZER" && test -f "$RELEASE_APK"
"$APKANALYZER" manifest print "$RELEASE_APK" > /tmp/yinyuehe-release-manifest.xml
"$APKANALYZER" dex packages --defined-only "$RELEASE_APK" > /tmp/yinyuehe-release-dex.txt
! rg -n 'WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE|androidx\.test\.runner|M2PermissionAndRealPlaybackTest|M2RevokedPermissionTest' \
  /tmp/yinyuehe-release-manifest.xml
! rg -n 'M2AcceptanceEntryPoint|M2PermissionAndRealPlaybackTest|M2RevokedPermissionTest' \
  /tmp/yinyuehe-release-dex.txt
git diff --check
git diff --exit-code -- core/data/schemas/app.yinyuehe.core.data.local.db.YinYueHeDatabase/1.json
```

Expected: all commands pass, Room v1 is unchanged, the Release Manifest contains no debug/test storage permissions, runner, or acceptance tests, and the Release DEX contains no debug/test acceptance classes.

- [ ] **Step 6: Complete the two-stage review and merge**

First ask an independent reviewer to trace every M2-D query, group, UI, fixture, and device-matrix acceptance back to the approved specification and confirm no M3–M6 scope entered. Fix all Critical/Important findings. Then use a different reviewer for SQL binding/escaping, key collisions, source-gate URI safety, Flow cancellation, SavedState restoration, stable Compose keys, test-fixture dependency direction, and device-test cleanup/privacy.

After fixes, rerun the complete gate and the affected device matrix, then:

```bash
git add core/common core/data core/testing feature/library app scripts docs/testing \
  docs/superpowers/plans/2026-07-13-m2d-library-search-views.md
git commit -m "test: verify M2 local library device flows"
git status --short
git log --oneline origin/main..HEAD
```

Push `feature/m2d-library-search-views`, open PR `feat: complete searchable local library`, wait for all GitHub Actions, squash-merge, verify `main` CI, and only then mark M2 complete.

## References

- Approved design: `docs/superpowers/specs/2026-07-13-m2-local-library-design.md`
- Room migration/schema guide: <https://developer.android.com/training/data-storage/room/migrating-db-versions>
- MediaStore reference: <https://developer.android.com/reference/android/provider/MediaStore.html>
- Shared media guidance: <https://developer.android.com/training/data-storage/shared/media>
