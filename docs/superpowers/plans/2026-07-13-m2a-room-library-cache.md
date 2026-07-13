# M2-A Room Library Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the demo-only repository binding with a versioned Room v1 local-library cache while preserving an all-or-nothing Demo fallback when no available local tracks exist.

**Architecture:** `core:data` owns Room entities, DAOs, mappings, the database, and the concrete repository. `core:common` contains only Android-free domain values. `TrackRepository` exposes the raw available-local Flow and the independent Demo catalog so M2-C can later hide stale Content URIs when permission is absent; for M2-A, `observeLibrary()` emits either `LOCAL` tracks from Room or the complete Demo catalog, and `observeTracks()` remains the playback compatibility facade.

**Tech Stack:** Kotlin 2.0.20, AGP 8.12.3, Room 2.8.4, KSP 2.0.20-1.0.25, Hilt 2.57.1, Coroutines/Flow 1.9.0, JUnit 4, Robolectric 4.16.

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

- Work only in a fresh worktree created with `superpowers:using-git-worktrees`; branch name: `feature/m2a-room-library-cache`.
- `minSdk = 26`, `compileSdk = 36`, `targetSdk = 36`, JDK/JVM target 17.
- Do not add a Gradle module; all persistence code remains inside `:core:data`.
- Do not add MediaStore queries, runtime permission code, scan orchestration, search, grouping, or new product UI in this PR.
- Demo tracks are never persisted in Room and are never mixed with local tracks.
- Preserve separate `observeAvailableLocalTracks()` and `demoTracks()` APIs; M2-C will use them to enforce the permission-aware source switch without clearing Room.
- `favorites` and `recent_plays` v1 foreign keys therefore reference persisted local tracks only. M4 must ship a real schema migration before it supports Demo favorites or Demo playlist relations.
- `exportSchema = true`; check the generated v1 JSON into `core/data/schemas/`.
- Never call `fallbackToDestructiveMigration()` in production or tests.
- Pin Room to stable `2.8.4`; do not update unrelated dependencies.
- Each task uses a fresh implementation subagent, then a specification-compliance reviewer, then a code-quality reviewer. Resolve all Critical/Important findings before the next task.
- Design source of truth: `docs/superpowers/specs/2026-07-13-m2-local-library-design.md`.

## File Structure

### Modify

- `gradle/libs.versions.toml` — Room version, runtime, KTX, compiler, testing, and plugin aliases.
- `build.gradle.kts` — declare the Room plugin alias with `apply false`.
- `core/common/src/main/kotlin/app/yinyuehe/core/common/model/Track.kt` — nullable raw title plus local-library display/filter fields.
- `core/common/src/test/kotlin/app/yinyuehe/core/common/model/TrackTest.kt` — updated domain invariants.
- `core/data/build.gradle.kts` — Room plugin, dependencies, schema directory, and schema test assets.
- `core/data/src/main/kotlin/app/yinyuehe/core/data/TrackRepository.kt` — `LibraryContent` API plus compatibility facade.
- `core/data/src/main/kotlin/app/yinyuehe/core/data/DataModule.kt` — bind `RoomTrackRepository`.
- `core/data/src/test/kotlin/app/yinyuehe/core/data/DemoTrackCatalogTest.kt` — remove the obsolete demo-repository binding assertion.
- `core/player/src/main/kotlin/app/yinyuehe/core/player/TrackMediaItemMapper.kt` — tolerate missing raw title.
- `core/testing/src/main/kotlin/app/yinyuehe/core/testing/FakeTrackRepository.kt` — emit `LibraryContent`.
- `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryScreen.kt` — minimal nullable-title display fallback only.

### Delete

- `core/data/src/main/kotlin/app/yinyuehe/core/data/DemoTrackRepository.kt` — superseded by the Room-backed repository and existing `DemoTrackCatalog`.

### Create

- `core/common/src/main/kotlin/app/yinyuehe/core/common/model/LibraryContent.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/YinYueHeDatabase.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/DatabaseModule.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/entity/TrackEntity.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/entity/FavoriteEntity.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/entity/RecentPlayEntity.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/entity/ScanCheckpointEntity.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/dao/TrackDao.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/dao/FavoriteDao.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/dao/RecentPlayDao.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/dao/ScanCheckpointDao.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/TrackEntityMapper.kt`
- `core/data/src/main/kotlin/app/yinyuehe/core/data/repository/RoomTrackRepository.kt`
- `core/data/src/test/kotlin/app/yinyuehe/core/data/local/db/RoomDatabaseRule.kt`
- `core/data/src/test/kotlin/app/yinyuehe/core/data/local/db/YinYueHeDatabaseTest.kt`
- `core/data/src/test/kotlin/app/yinyuehe/core/data/local/db/TrackEntityMapperTest.kt`
- `core/data/src/test/kotlin/app/yinyuehe/core/data/local/db/TrackDaoTest.kt`
- `core/data/src/test/kotlin/app/yinyuehe/core/data/local/db/RelationDaoTest.kt`
- `core/data/src/test/kotlin/app/yinyuehe/core/data/repository/RoomTrackRepositoryTest.kt`
- `feature/library/src/main/res/values/strings.xml`
- `core/data/src/androidTest/kotlin/app/yinyuehe/core/data/local/db/YinYueHeSchemaBaselineTest.kt`
- `core/data/schemas/app.yinyuehe.core.data.local.db.YinYueHeDatabase/1.json` — generated, never handwritten.

---

### Task 1: Add Room and evolve the domain contract

**Files:**

- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`
- Modify: `core/data/build.gradle.kts`
- Modify: `core/common/src/main/kotlin/app/yinyuehe/core/common/model/Track.kt`
- Create: `core/common/src/main/kotlin/app/yinyuehe/core/common/model/LibraryContent.kt`
- Modify: `core/common/src/test/kotlin/app/yinyuehe/core/common/model/TrackTest.kt`
- Modify: `core/player/src/main/kotlin/app/yinyuehe/core/player/TrackMediaItemMapper.kt`
- Modify: `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryScreen.kt`
- Create: `feature/library/src/main/res/values/strings.xml`

**Interfaces:**

- Produces: `LibrarySource`, `LibraryContent`, and the final M2 `Track` field set used by every later task and PR.
- Preserves: `TrackId`, `Track.sourceUri`, and positional compatibility for the existing first eight `Track` constructor arguments.

- [ ] **Step 1: Add the failing domain tests**

Extend `TrackTest` with these exact cases before changing production code:

```kotlin
@Test
fun missingTitle_isAllowedWhenDisplayNameCanDescribeTheRow() {
  val track = validTrack().copy(title = null, displayName = "01 - Intro.flac")

  assertEquals(null, track.title)
  assertEquals("01 - Intro.flac", track.displayName)
}

@Test
fun negativeSize_isRejected() {
  assertThrows(IllegalArgumentException::class.java) { validTrack().copy(sizeBytes = -1) }
}

@Test
fun blankNonNullTitle_isRejected() {
  assertThrows(IllegalArgumentException::class.java) { validTrack().copy(title = " ") }
}
```

- [ ] **Step 2: Run the domain test and verify the red state**

Run:

```bash
./gradlew :core:common:test --tests '*TrackTest' --stacktrace
```

Expected: compilation fails because `displayName` and `sizeBytes` do not exist and `title` is not nullable.

- [ ] **Step 3: Add the pinned Room dependencies and plugin**

Add these catalog entries without changing existing versions:

```toml
[versions]
room = "2.8.4"

[libraries]
androidx-room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
androidx-room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
androidx-room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
androidx-room-testing = { module = "androidx.room:room-testing", version.ref = "room" }

[plugins]
room = { id = "androidx.room", version.ref = "room" }
```

Declare `alias(libs.plugins.room) apply false` in the root plugin block. Apply `alias(libs.plugins.room)` in `core/data/build.gradle.kts`, then configure:

```kotlin
room {
  schemaDirectory("$projectDir/schemas")
}

android {
  sourceSets {
    getByName("androidTest").assets.srcDir("$projectDir/schemas")
  }
}

dependencies {
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)
  testImplementation(libs.androidx.room.testing)
  testImplementation(libs.kotlinx.coroutines.test)
  androidTestImplementation(libs.androidx.room.testing)
  androidTestImplementation(libs.androidx.test.ext.junit)
}
```

- [ ] **Step 4: Implement the Android-free domain values**

Create `LibraryContent.kt` exactly as follows:

```kotlin
package app.yinyuehe.core.common.model

enum class LibrarySource { DEMO, LOCAL }

data class LibraryContent(
  val source: LibrarySource,
  val tracks: List<Track>,
) {
  init {
    require(tracks.all { it.isDemo == (source == LibrarySource.DEMO) }) {
      "Demo and local tracks must not be mixed"
    }
  }
}
```

Change `Track` to this final M2 shape:

```kotlin
data class Track(
  val id: TrackId,
  val title: String?,
  val artist: String?,
  val album: String?,
  val durationMs: Long?,
  val artworkUri: String?,
  val sourceUri: String,
  val isDemo: Boolean,
  val displayName: String? = null,
  val albumId: Long? = null,
  val mimeType: String? = null,
  val sizeBytes: Long? = null,
  val folderKey: String? = null,
  val folderDisplayName: String? = null,
  val dateAddedSeconds: Long? = null,
  val dateModifiedSeconds: Long? = null,
) {
  init {
    require(title == null || title.isNotBlank()) { "Track title must be null or non-blank" }
    require(sourceUri.isNotBlank()) { "Track sourceUri must not be blank" }
    require(durationMs == null || durationMs >= 0) { "Track duration must not be negative" }
    require(sizeBytes == null || sizeBytes >= 0) { "Track size must not be negative" }
    require(dateAddedSeconds == null || dateAddedSeconds >= 0) {
      "Track dateAddedSeconds must not be negative"
    }
    require(dateModifiedSeconds == null || dateModifiedSeconds >= 0) {
      "Track dateModifiedSeconds must not be negative"
    }
  }
}
```

In `TrackMediaItemMapper`, set the Media3 title with `.setTitle(title ?: displayName)`. Add `unknown_track` and formatted `play_track_content_description` Feature string resources. In `LibraryScreen`, compute `val displayTitle = track.title ?: track.displayName ?: stringResource(R.string.unknown_track)` and `val playDescription = stringResource(R.string.play_track_content_description, displayTitle)` once inside `TrackRow`; use them for row text and semantics. Do not otherwise redesign the screen or hardcode localized text.

- [ ] **Step 5: Run the affected tests and compile checks**

Run:

```bash
./gradlew :core:common:test :core:player:testDebugUnitTest :feature:library:testDebugUnitTest :feature:library:compileDebugKotlin --stacktrace
```

Expected: `BUILD SUCCESSFUL`; existing Demo playback tests remain green and all positional `Track` call sites compile.

- [ ] **Step 6: Commit Task 1**

```bash
git add gradle/libs.versions.toml build.gradle.kts core/data/build.gradle.kts core/common core/player/src/main/kotlin/app/yinyuehe/core/player/TrackMediaItemMapper.kt feature/library/src/main
git commit -m "feat: define M2 local library domain contract"
```

---

### Task 2: Create the exact Room v1 schema and DAOs

**Files:**

- Create: all `local/db/entity`, `local/db/dao`, `YinYueHeDatabase.kt`, and JVM database test files listed above.

**Interfaces:**

- Produces: `YinYueHeDatabase`, `TrackDao`, `FavoriteDao`, `RecentPlayDao`, `ScanCheckpointDao`.
- Consumes: the schema contract in approved design section 5; no scanner types are introduced here.

- [ ] **Step 1: Write failing DAO and foreign-key tests**

Create a Robolectric `RoomDatabaseRule` that opens `Room.inMemoryDatabaseBuilder(context, YinYueHeDatabase::class.java).allowMainThreadQueries().build()` and closes it in `finished()`. In addition to the examples below, test the `(volumeName, mediaStoreId)` unique constraint, checkpoint upsert/read, every declared default and index through Room's exported schema, and a file-backed database that is closed, reopened with `Room.databaseBuilder`, and still contains its cached track.

Add these minimum assertions before creating the database classes:

```kotlin
@Test
fun availableTracks_areDeterministicAndUnavailableRowsAreHidden() = runTest {
  dao.upsertTracks(
    listOf(
      trackEntity(mediaId = "local:b", titleSortKey = "same", isAvailable = true),
      trackEntity(mediaId = "local:a", titleSortKey = "same", isAvailable = true),
      trackEntity(mediaId = "local:hidden", titleSortKey = "aaa", isAvailable = false),
    )
  )

  assertEquals(listOf("local:a", "local:b"), dao.observeAvailableTracks().first().map { it.mediaId })
}

@Test
fun deletingTrack_cascadesFavoriteAndRecentPlay() = runTest {
  trackDao.upsertTracks(listOf(trackEntity(mediaId = "local:one")))
  favoriteDao.upsert(FavoriteEntity("local:one", 10L))
  recentPlayDao.upsert(RecentPlayEntity("local:one", 20L, 2L, null))

  trackDao.deleteByMediaId("local:one")

  assertNull(favoriteDao.find("local:one"))
  assertNull(recentPlayDao.find("local:one"))
}

@Test
fun relationForMissingTrack_isRejected() = runTest {
  val failure =
    runCatching {
      favoriteDao.upsert(FavoriteEntity("demo:not-persisted", 10L))
    }.exceptionOrNull()

  assertTrue(failure is SQLiteConstraintException)
}
```

- [ ] **Step 2: Run tests and verify the red state**

```bash
./gradlew :core:data:testDebugUnitTest --tests '*TrackDaoTest' \
  --tests '*RelationDaoTest' --tests '*YinYueHeDatabaseTest' --stacktrace
```

Expected: compilation fails because the Room v1 types do not exist.

- [ ] **Step 3: Implement the four entities exactly**

Create `TrackEntity` with all approved fields and Room defaults:

```kotlin
@Entity(
  tableName = "tracks",
  indices = [
    Index(value = ["volumeName", "mediaStoreId"], unique = true),
    Index(value = ["isAvailable", "titleSortKey", "mediaId"]),
    Index(value = ["isAvailable", "artistSortKey", "mediaId"]),
    Index(value = ["isAvailable", "albumSortKey", "mediaId"]),
    Index(value = ["isAvailable", "folderSortKey", "mediaId"]),
    Index(value = ["isAvailable", "dateAddedSeconds", "mediaId"]),
    Index(value = ["isAvailable", "durationMs", "mediaId"]),
    Index(value = ["volumeName", "lastSeenScanToken"]),
  ],
)
data class TrackEntity(
  @PrimaryKey val mediaId: String,
  val volumeName: String,
  val mediaStoreId: Long,
  val contentUri: String,
  val displayName: String?,
  val title: String?,
  val artist: String?,
  val album: String?,
  val albumId: Long?,
  val artworkUri: String?,
  @ColumnInfo(defaultValue = "0") val durationMs: Long = 0,
  val mimeType: String?,
  @ColumnInfo(defaultValue = "0") val sizeBytes: Long = 0,
  val folderKey: String?,
  val folderDisplayName: String?,
  @ColumnInfo(defaultValue = "0") val dateAddedSeconds: Long = 0,
  @ColumnInfo(defaultValue = "0") val dateModifiedSeconds: Long = 0,
  @ColumnInfo(defaultValue = "''") val searchText: String = "",
  @ColumnInfo(defaultValue = "''") val titleSortKey: String = "",
  @ColumnInfo(defaultValue = "''") val artistSortKey: String = "",
  @ColumnInfo(defaultValue = "''") val albumSortKey: String = "",
  @ColumnInfo(defaultValue = "''") val folderSortKey: String = "",
  @ColumnInfo(defaultValue = "''") val metadataFingerprint: String = "",
  @ColumnInfo(defaultValue = "1") val isAvailable: Boolean = true,
  val lastSeenScanToken: String,
)
```

Create the relation entities with these exact foreign keys:

```kotlin
@Entity(
  tableName = "favorites",
  foreignKeys = [
    ForeignKey(
      entity = TrackEntity::class,
      parentColumns = ["mediaId"],
      childColumns = ["trackId"],
      onDelete = ForeignKey.CASCADE,
    )
  ],
)
data class FavoriteEntity(
  @PrimaryKey val trackId: String,
  val addedAtEpochMs: Long,
)

@Entity(
  tableName = "recent_plays",
  foreignKeys = [
    ForeignKey(
      entity = TrackEntity::class,
      parentColumns = ["mediaId"],
      childColumns = ["trackId"],
      onDelete = ForeignKey.CASCADE,
    )
  ],
)
data class RecentPlayEntity(
  @PrimaryKey val trackId: String,
  @ColumnInfo(defaultValue = "0") val lastPlayedAtEpochMs: Long = 0,
  @ColumnInfo(defaultValue = "0") val playCount: Long = 0,
  @ColumnInfo(defaultValue = "NULL") val lastPositionMs: Long? = null,
)
```

Create `ScanCheckpointEntity` exactly as the approved v1 table:

```kotlin
@Entity(tableName = "scan_checkpoints")
data class ScanCheckpointEntity(
  @PrimaryKey val volumeName: String,
  val mediaStoreVersion: String?,
  val generationUpperBound: Long?,
  @ColumnInfo(defaultValue = "0") val lastFullScanEpochMs: Long = 0,
  @ColumnInfo(defaultValue = "0") val lastSuccessfulScanEpochMs: Long = 0,
  val lastScanToken: String,
  @ColumnInfo(defaultValue = "1") val isMounted: Boolean = true,
  @ColumnInfo(defaultValue = "0") val lastDiscoveredCount: Long = 0,
  @ColumnInfo(defaultValue = "0") val lastInsertedCount: Long = 0,
  @ColumnInfo(defaultValue = "0") val lastUpdatedCount: Long = 0,
  @ColumnInfo(defaultValue = "0") val lastUnavailableCount: Long = 0,
)
```

- [ ] **Step 4: Implement the DAOs and database**

Use these signatures; do not add scan orchestration methods yet:

```kotlin
@Dao
interface TrackDao {
  @Query(
    """
    SELECT * FROM tracks
    WHERE isAvailable = 1
    ORDER BY titleSortKey ASC, mediaId ASC
    """
  )
  fun observeAvailableTracks(): Flow<List<TrackEntity>>

  @Query("SELECT * FROM tracks WHERE mediaId = :mediaId")
  suspend fun findByMediaId(mediaId: String): TrackEntity?

  @Query("SELECT * FROM tracks ORDER BY mediaId")
  suspend fun getAll(): List<TrackEntity>

  @Upsert suspend fun upsertTracks(tracks: List<TrackEntity>)

  @Query("DELETE FROM tracks WHERE mediaId = :mediaId")
  suspend fun deleteByMediaId(mediaId: String)
}

@Dao
interface FavoriteDao {
  @Upsert suspend fun upsert(entity: FavoriteEntity)
  @Query("SELECT * FROM favorites WHERE trackId = :trackId")
  suspend fun find(trackId: String): FavoriteEntity?
}

@Dao
interface RecentPlayDao {
  @Upsert suspend fun upsert(entity: RecentPlayEntity)
  @Query("SELECT * FROM recent_plays WHERE trackId = :trackId")
  suspend fun find(trackId: String): RecentPlayEntity?
}

@Dao
interface ScanCheckpointDao {
  @Upsert suspend fun upsert(entity: ScanCheckpointEntity)
  @Query("SELECT * FROM scan_checkpoints WHERE volumeName = :volumeName")
  suspend fun find(volumeName: String): ScanCheckpointEntity?
  @Query("SELECT * FROM scan_checkpoints ORDER BY volumeName")
  suspend fun getAll(): List<ScanCheckpointEntity>
}
```

Create `YinYueHeDatabase` with `version = 1`, `exportSchema = true`, and public accessors for all four DAOs:

```kotlin
@Database(
  entities = [
    TrackEntity::class,
    FavoriteEntity::class,
    RecentPlayEntity::class,
    ScanCheckpointEntity::class,
  ],
  version = 1,
  exportSchema = true,
)
abstract class YinYueHeDatabase : RoomDatabase() {
  abstract fun trackDao(): TrackDao
  abstract fun favoriteDao(): FavoriteDao
  abstract fun recentPlayDao(): RecentPlayDao
  abstract fun scanCheckpointDao(): ScanCheckpointDao
}
```

The database, entities, and DAOs are public because Room/Hilt generated code and the public `@Provides` signatures cross generated-package and Gradle-module boundaries. They remain persistence-only types under `core:data.local.db`; feature/domain modules depend only on repository contracts. Do not mix an internal DAO/entity with a public database accessor or provider signature, because Kotlin rejects that exposed type.

- [ ] **Step 5: Run Room tests and generate the v1 schema**

```bash
./gradlew :core:data:testDebugUnitTest :core:data:assembleDebug --stacktrace
```

Expected: `BUILD SUCCESSFUL` and `core/data/schemas/app.yinyuehe.core.data.local.db.YinYueHeDatabase/1.json` exists with four tables and the declared indices.

- [ ] **Step 6: Commit Task 2**

```bash
git add core/data/src/main/kotlin/app/yinyuehe/core/data/local/db core/data/src/test/kotlin/app/yinyuehe/core/data/local/db core/data/schemas
git commit -m "feat: add Room v1 local library schema"
```

---

### Task 3: Map Room tracks and implement the Demo fallback repository

**Files:**

- Create: `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/TrackEntityMapper.kt`
- Create: `core/data/src/main/kotlin/app/yinyuehe/core/data/repository/RoomTrackRepository.kt`
- Modify: `core/data/src/main/kotlin/app/yinyuehe/core/data/TrackRepository.kt`
- Delete: `core/data/src/main/kotlin/app/yinyuehe/core/data/DemoTrackRepository.kt`
- Modify: `core/testing/src/main/kotlin/app/yinyuehe/core/testing/FakeTrackRepository.kt`
- Create: `core/data/src/test/kotlin/app/yinyuehe/core/data/repository/RoomTrackRepositoryTest.kt`
- Modify: `core/data/src/test/kotlin/app/yinyuehe/core/data/DemoTrackCatalogTest.kt`

**Interfaces:**

- Produces: `TrackRepository.observeAvailableLocalTracks()`, `demoTracks()`, and `observeLibrary()`.
- Preserves: `TrackRepository.observeTracks(): Flow<List<Track>>` as a default compatibility method.

- [ ] **Step 1: Write failing mapping and fallback tests**

First cover mapper behavior for complete metadata, nullable metadata, blank title/display name becoming null, negative duration/size/date values becoming null or zero according to the domain contract, Content URI passthrough, and `isDemo = false`. Then cover exactly these repository transitions with a real in-memory Room database and a real `DemoTrackCatalog`:

```kotlin
@Test
fun emptyRoom_emitsOnlyDemoCatalog() = runTest {
  assertEquals(LibrarySource.DEMO, repository.observeLibrary().first().source)
  assertTrue(repository.observeLibrary().first().tracks.all(Track::isDemo))
}

@Test
fun availableLocalTrack_replacesEntireDemoCatalog() = runTest {
  trackDao.upsertTracks(listOf(trackEntity(mediaId = "local:one", title = null, displayName = "One.flac")))

  val content = repository.observeLibrary().first { it.source == LibrarySource.LOCAL }

  assertEquals(listOf("local:one"), content.tracks.map { it.id.value })
  assertTrue(content.tracks.none(Track::isDemo))
}

@Test
fun unavailableRows_doNotSuppressDemoFallback() = runTest {
  trackDao.upsertTracks(listOf(trackEntity(mediaId = "local:hidden", isAvailable = false)))

  assertEquals(LibrarySource.DEMO, repository.observeLibrary().first().source)
}
```

- [ ] **Step 2: Run tests and verify the red state**

```bash
./gradlew :core:data:testDebugUnitTest --tests '*TrackEntityMapperTest' \
  --tests '*RoomTrackRepositoryTest' --stacktrace
```

Expected: compilation fails because `RoomTrackRepository`, `LibraryContent`, and the mapper are not wired.

- [ ] **Step 3: Implement the mapper and repository contract**

Use this repository interface:

```kotlin
interface TrackRepository {
  fun observeAvailableLocalTracks(): Flow<List<Track>>

  fun demoTracks(): List<Track>

  fun observeLibrary(): Flow<LibraryContent>

  fun observeTracks(): Flow<List<Track>> = observeLibrary().map(LibraryContent::tracks)
}
```

Map entities without inventing localized text:

```kotlin
internal fun TrackEntity.toDomain(): Track =
  Track(
    id = TrackId(mediaId),
    title = title?.takeIf { it.isNotBlank() },
    artist = artist?.takeIf { it.isNotBlank() },
    album = album?.takeIf { it.isNotBlank() },
    durationMs = durationMs.takeIf { it > 0 },
    artworkUri = artworkUri?.takeIf { it.isNotBlank() },
    sourceUri = contentUri,
    isDemo = false,
    displayName = displayName?.takeIf { it.isNotBlank() },
    albumId = albumId?.takeIf { it >= 0 },
    mimeType = mimeType?.takeIf { it.isNotBlank() },
    sizeBytes = sizeBytes.coerceAtLeast(0),
    folderKey = folderKey?.takeIf { it.isNotBlank() },
    folderDisplayName = folderDisplayName?.takeIf { it.isNotBlank() },
    dateAddedSeconds = dateAddedSeconds.coerceAtLeast(0),
    dateModifiedSeconds = dateModifiedSeconds.coerceAtLeast(0),
  )
```

Implement `RoomTrackRepository` as one all-or-nothing source switch:

```kotlin
@Singleton
class RoomTrackRepository @Inject internal constructor(
  private val trackDao: TrackDao,
  demoCatalog: DemoTrackCatalog,
) : TrackRepository {
  private val demos = demoCatalog.tracks()

  override fun observeAvailableLocalTracks(): Flow<List<Track>> =
    trackDao.observeAvailableTracks()
      .map { entities -> entities.map { it.toDomain() } }
      .distinctUntilChanged()

  override fun demoTracks(): List<Track> = demos

  override fun observeLibrary(): Flow<LibraryContent> =
    observeAvailableLocalTracks()
      .map { localTracks ->
        if (localTracks.isEmpty()) {
          LibraryContent(LibrarySource.DEMO, demos)
        } else {
          LibraryContent(LibrarySource.LOCAL, localTracks)
        }
      }
      .distinctUntilChanged()
}
```

Delete `DemoTrackRepository`. Update `FakeTrackRepository` to store separate local and Demo values, implement `observeAvailableLocalTracks()` and `demoTracks()`, and derive `observeLibrary()` with the same all-or-nothing fallback. To preserve M1 tests, its `initialTracks` and `setTracks` infer Demo only when every non-empty item has `isDemo = true`; otherwise they update Local. Also add explicit `setLocalTracks` and `setDemoTracks` helpers so M2-C can retain a Local cache while emitting Demo.

- [ ] **Step 4: Run repository, ViewModel, and playback regression tests**

```bash
./gradlew :core:data:testDebugUnitTest :feature:library:testDebugUnitTest :core:player:testDebugUnitTest --stacktrace
```

Expected: `BUILD SUCCESSFUL`; current ViewModel still receives ordered tracks through `observeTracks()`.

- [ ] **Step 5: Commit Task 3**

```bash
git add core/data core/testing feature/library/src/test core/player/src/test
git commit -m "feat: serve Room cache with Demo fallback"
```

---

### Task 4: Wire Hilt, establish the schema baseline, and pass the PR gate

**Files:**

- Create: `core/data/src/main/kotlin/app/yinyuehe/core/data/local/db/DatabaseModule.kt`
- Modify: `core/data/src/main/kotlin/app/yinyuehe/core/data/DataModule.kt`
- Create: `core/data/src/androidTest/kotlin/app/yinyuehe/core/data/local/db/YinYueHeSchemaBaselineTest.kt`
- Modify: `core/data/src/test/kotlin/app/yinyuehe/core/data/DemoTrackCatalogTest.kt`

**Interfaces:**

- Produces: process-singleton `YinYueHeDatabase` and singleton `TrackRepository` binding.
- Does not produce any migration from a nonexistent version; it only establishes the v1 `MigrationTestHelper` baseline.

- [ ] **Step 1: Write the failing Hilt wiring and baseline tests**

Update the reflection test to require `DataModule.bindTrackRepository(RoomTrackRepository): TrackRepository`.

Add an Android schema baseline test using the exported asset:

```kotlin
@RunWith(AndroidJUnit4::class)
class YinYueHeSchemaBaselineTest {
  private val databaseName = "schema-v1-baseline"

  @get:Rule
  val helper =
    MigrationTestHelper(
      InstrumentationRegistry.getInstrumentation(),
      YinYueHeDatabase::class.java,
    )

  @Test
  fun exportedVersion1Schema_canBeCreatedAndOpened() {
    helper.createDatabase(databaseName, 1).close()
    Room.databaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        YinYueHeDatabase::class.java,
        databaseName,
      )
      .build()
      .use { database ->
        database.openHelper.writableDatabase
      }
  }
}
```

- [ ] **Step 2: Run the focused tests and verify the red state**

```bash
./gradlew :core:data:testDebugUnitTest --tests '*DemoTrackCatalogTest' --stacktrace
```

Expected: the binding assertion fails until `DataModule` targets `RoomTrackRepository`.

- [ ] **Step 3: Implement production database and repository wiring**

Create an object Hilt module:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
  @Provides
  @Singleton
  fun provideDatabase(@ApplicationContext context: Context): YinYueHeDatabase =
    Room.databaseBuilder(context, YinYueHeDatabase::class.java, "yinyuehe.db").build()

  @Provides fun provideTrackDao(database: YinYueHeDatabase): TrackDao = database.trackDao()
  @Provides fun provideFavoriteDao(database: YinYueHeDatabase): FavoriteDao = database.favoriteDao()
  @Provides fun provideRecentPlayDao(database: YinYueHeDatabase): RecentPlayDao = database.recentPlayDao()
  @Provides fun provideScanCheckpointDao(database: YinYueHeDatabase): ScanCheckpointDao =
    database.scanCheckpointDao()
}
```

Bind `RoomTrackRepository` as the singleton `TrackRepository`; do not retain a second demo repository binding.

- [ ] **Step 4: Run JVM, schema, Lint, and build verification**

With an API 36 emulator already running, execute:

```bash
./gradlew :core:data:testDebugUnitTest :core:data:connectedDebugAndroidTest --stacktrace
./gradlew test testDebugUnitTest lintDebug assembleDebug --stacktrace
```

Expected: both commands end with `BUILD SUCCESSFUL`; the baseline test creates v1 from the exported schema; no destructive migration is configured.

- [ ] **Step 5: Commit Task 4**

```bash
git add core/data docs/superpowers/plans/2026-07-13-m2a-room-library-cache.md
git commit -m "test: verify Room v1 schema and wiring"
```

- [ ] **Step 6: Complete the two-stage PR review and publish**

Run a fresh specification-compliance review against the M2-A “包含/不包含” list, fix all Critical/Important findings, then run a different code-quality reviewer focused on schema defaults, indices, foreign keys, Flow source switching, and Hilt ownership.

After both reviews are clean:

```bash
./gradlew test testDebugUnitTest lintDebug assembleDebug --stacktrace
git status --short
git log --oneline origin/main..HEAD
```

Expected: full gate succeeds, status is clean, and the branch contains only M2-A commits. Push `feature/m2a-room-library-cache`, open a PR titled `feat: add Room-backed local library cache`, wait for GitHub Actions, squash-merge, and verify the `main` workflow before starting M2-B.

## References

- Approved design: `docs/superpowers/specs/2026-07-13-m2-local-library-design.md`
- Android Room migration and schema export guide: <https://developer.android.com/training/data-storage/room/migrating-db-versions>
- Room 2.8.4 Google Maven metadata: <https://dl.google.com/dl/android/maven2/androidx/room/room-runtime/maven-metadata.xml>
