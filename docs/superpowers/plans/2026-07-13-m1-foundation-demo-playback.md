# M1 Standalone Foundation and Demo Playback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone, multi-module Android app that lists the four bundled YinYueHe demo tracks and plays a selected track through a Media3 `MediaLibraryService`.

**Architecture:** A thin Hilt-enabled `:app` renders `:feature:library`; the feature consumes a `TrackRepository` from `:core:data` and a `PlaybackController` from `:core:player`. Domain models live in the Android-free `:core:common`, visual tokens live in `:core:designsystem`, and test fakes live in `:core:testing` only on test classpaths.

**Tech Stack:** Kotlin 2.0.20, AGP 8.12.3, Gradle 8.13, JDK 17, compile/target SDK 36, Compose Material 3, Hilt 2.57.1, KSP, Coroutines/Flow, Media3 1.10.1, JUnit 4, Robolectric, Compose UI Test.

## Global Constraints

- Repository root is `yinyuehe-android`; do not modify the downloaded Media3 source tree except to read or copy the four approved demo audio assets.
- `applicationId` and namespace root are `app.yinyuehe`.
- `minSdk = 26`, `compileSdk = 36`, `targetSdk = 36`, JDK/JVM target 17.
- Use Kotlin DSL, a version catalog, convention plugins, fixed dependency versions, KSP instead of kapt, and no dynamic versions.
- Feature modules do not depend on one another; Compose does not access Media3, Room, Retrofit, MediaStore, or Android services directly.
- Room Entity, network DTO, and Media3 `MediaItem` types never enter feature public APIs.
- The app remains usable without audio permission, device music, network access, an account, or a backend.
- Use test-first development for behavior; every task ends with focused verification and an atomic commit.
- Do not add playlists, MediaStore scanning, online lyrics, metadata lookup, queue restoration, benchmark modules, release signing, or adaptive navigation in M1; those have explicit later milestones in the roadmap.

## Final M1 File Map

```text
yinyuehe-android/
├── .github/workflows/ci.yml
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/app/yinyuehe/{MainActivity.kt,YinYueHeApp.kt,YinYueHeApplication.kt}
│       └── res/values/{strings.xml,themes.xml}
├── build-logic/
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── src/main/kotlin/app/yinyuehe/buildlogic/
│       ├── AndroidApplicationConventionPlugin.kt
│       ├── AndroidComposeConventionPlugin.kt
│       ├── AndroidLibraryConventionPlugin.kt
│       ├── AndroidProjectConfig.kt
│       └── KotlinLibraryConventionPlugin.kt
├── core/
│   ├── common/src/main/kotlin/app/yinyuehe/core/common/model/Track.kt
│   ├── data/src/main/{kotlin/app/yinyuehe/core/data,res/raw}/...
│   ├── designsystem/src/main/kotlin/app/yinyuehe/core/designsystem/theme/...
│   ├── player/src/main/kotlin/app/yinyuehe/core/player/...
│   └── testing/src/main/kotlin/app/yinyuehe/core/testing/...
├── feature/library/src/main/kotlin/app/yinyuehe/feature/library/...
├── gradle/libs.versions.toml
├── gradle/wrapper/...
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── settings.gradle.kts
├── LICENSE
└── README.md
```

---

### Task 1: Bootstrap the standalone Gradle build

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `build-logic/settings.gradle.kts`
- Create: `build-logic/build.gradle.kts`
- Create: `build-logic/src/main/kotlin/app/yinyuehe/buildlogic/AndroidProjectConfig.kt`
- Create: `build-logic/src/main/kotlin/app/yinyuehe/buildlogic/AndroidApplicationConventionPlugin.kt`
- Create: `build-logic/src/main/kotlin/app/yinyuehe/buildlogic/AndroidLibraryConventionPlugin.kt`
- Create: `build-logic/src/main/kotlin/app/yinyuehe/buildlogic/AndroidComposeConventionPlugin.kt`
- Create: `build-logic/src/main/kotlin/app/yinyuehe/buildlogic/KotlinLibraryConventionPlugin.kt`
- Copy: `../gradlew`, `../gradlew.bat`, `../gradle/wrapper/gradle-wrapper.jar`, `../gradle/wrapper/gradle-wrapper.properties`

**Interfaces:**
- Consumes: the proven AGP 8.12.3 / Gradle 8.13 / JDK 17 baseline from the source Media3 release.
- Produces: convention plugin IDs `yinyuehe.android.application`, `yinyuehe.android.library`, `yinyuehe.android.compose`, and `yinyuehe.kotlin.library`.

- [ ] **Step 1: Prepare JDK 17 and copy the existing Gradle 8.13 wrapper**

Run:

```bash
brew install openjdk@17
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
"$JAVA_HOME/bin/java" -version
mkdir -p gradle/wrapper
cp ../gradlew ../gradlew.bat .
cp ../gradle/wrapper/gradle-wrapper.jar ../gradle/wrapper/gradle-wrapper.properties gradle/wrapper/
chmod +x gradlew
```

Expected: Java reports version 17 and `./gradlew --version` reports Gradle 8.13. Keep `JAVA_HOME` set to this path for every local Gradle command in the plan; Gradle 8.13 cannot run on the machine's existing Java 25 runtime.

- [ ] **Step 2: Add the root build and exact dependency catalog**

Create `settings.gradle.kts`:

```kotlin
pluginManagement {
  includeBuild("build-logic")
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "yinyuehe-android"
```

Create `build.gradle.kts`:

```kotlin
plugins {
  alias(libs.plugins.hilt) apply false
  alias(libs.plugins.ksp) apply false
}

tasks.register<Delete>("clean") {
  delete(layout.buildDirectory)
}
```

Create `gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.caching=true
org.gradle.parallel=true
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

Create `gradle/libs.versions.toml`:

```toml
[versions]
agp = "8.12.3"
kotlin = "2.0.20"
ksp = "2.0.20-1.0.25"
hilt = "2.57.1"
composeBom = "2024.12.01"
activityCompose = "1.10.1"
coreKtx = "1.15.0"
lifecycle = "2.8.7"
coroutines = "1.9.0"
media3 = "1.10.1"
junit = "4.13.2"
androidxTestCore = "1.6.1"
androidxTestExt = "1.2.1"
espresso = "3.6.1"
robolectric = "4.16"

[libraries]
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-ktx = { module = "androidx.lifecycle:lifecycle-viewmodel-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-foundation = { module = "androidx.compose.foundation:foundation" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-compose-ui = { module = "androidx.compose.ui:ui" }
androidx-compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
androidx-compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
androidx-compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
androidx-compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
androidx-test-core = { module = "androidx.test:core", version.ref = "androidxTestCore" }
androidx-test-ext-junit = { module = "androidx.test.ext:junit", version.ref = "androidxTestExt" }
androidx-test-espresso-core = { module = "androidx.test.espresso:espresso-core", version.ref = "espresso" }
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-compiler", version.ref = "hilt" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-guava = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-guava", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
media3-common = { module = "androidx.media3:media3-common", version.ref = "media3" }
media3-exoplayer = { module = "androidx.media3:media3-exoplayer", version.ref = "media3" }
media3-session = { module = "androidx.media3:media3-session", version.ref = "media3" }
junit = { module = "junit:junit", version.ref = "junit" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }

[plugins]
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 3: Add convention plugins**

Create `build-logic/settings.gradle.kts`:

```kotlin
pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "build-logic"
```

Create `build-logic/build.gradle.kts`:

```kotlin
plugins {
  `kotlin-dsl`
}

group = "app.yinyuehe.buildlogic"

dependencies {
  implementation("com.android.tools.build:gradle:8.12.3")
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.20")
  implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.0.20")
}

gradlePlugin {
  plugins {
    register("androidApplication") {
      id = "yinyuehe.android.application"
      implementationClass = "app.yinyuehe.buildlogic.AndroidApplicationConventionPlugin"
    }
    register("androidLibrary") {
      id = "yinyuehe.android.library"
      implementationClass = "app.yinyuehe.buildlogic.AndroidLibraryConventionPlugin"
    }
    register("androidCompose") {
      id = "yinyuehe.android.compose"
      implementationClass = "app.yinyuehe.buildlogic.AndroidComposeConventionPlugin"
    }
    register("kotlinLibrary") {
      id = "yinyuehe.kotlin.library"
      implementationClass = "app.yinyuehe.buildlogic.KotlinLibraryConventionPlugin"
    }
  }
}
```

Create `build-logic/src/main/kotlin/app/yinyuehe/buildlogic/AndroidProjectConfig.kt`:

```kotlin
package app.yinyuehe.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

internal fun Project.configureAndroid(extension: CommonExtension<*, *, *, *, *, *>) {
  extension.apply {
    compileSdk = 36
    defaultConfig {
      minSdk = 26
      testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
      sourceCompatibility = JavaVersion.VERSION_17
      targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
      abortOnError = true
      warningsAsErrors = true
    }
  }
  tasks.withType(KotlinCompile::class.java).configureEach {
    kotlinOptions.jvmTarget = "17"
  }
}
```

Create `build-logic/src/main/kotlin/app/yinyuehe/buildlogic/AndroidApplicationConventionPlugin.kt`:

```kotlin
package app.yinyuehe.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) = with(target) {
    pluginManager.apply("com.android.application")
    pluginManager.apply("org.jetbrains.kotlin.android")
    extensions.configure<ApplicationExtension> {
      configureAndroid(this)
    }
  }
}
```

Create `build-logic/src/main/kotlin/app/yinyuehe/buildlogic/AndroidLibraryConventionPlugin.kt`:

```kotlin
package app.yinyuehe.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) = with(target) {
    pluginManager.apply("com.android.library")
    pluginManager.apply("org.jetbrains.kotlin.android")
    extensions.configure<LibraryExtension> {
      configureAndroid(this)
    }
  }
}
```

Create `build-logic/src/main/kotlin/app/yinyuehe/buildlogic/AndroidComposeConventionPlugin.kt`:

```kotlin
package app.yinyuehe.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.findByType

class AndroidComposeConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) = with(target) {
    pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
    extensions.findByType<ApplicationExtension>()?.buildFeatures?.compose = true
    extensions.findByType<LibraryExtension>()?.buildFeatures?.compose = true
  }
}
```

Create `build-logic/src/main/kotlin/app/yinyuehe/buildlogic/KotlinLibraryConventionPlugin.kt`:

```kotlin
package app.yinyuehe.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

class KotlinLibraryConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) = with(target) {
    pluginManager.apply("org.jetbrains.kotlin.jvm")
    extensions.configure<KotlinJvmProjectExtension> {
      jvmToolchain(17)
    }
  }
}
```

- [ ] **Step 4: Verify the root build**

Run:

```bash
./gradlew help --warning-mode=all
```

Expected: `BUILD SUCCESSFUL`; no project modules exist yet, and no repository is declared outside `settings.gradle.kts`.

- [ ] **Step 5: Commit the build foundation**

```bash
git add gradlew gradlew.bat gradle build-logic settings.gradle.kts build.gradle.kts gradle.properties
git commit -m "build: bootstrap standalone Android project"
```

---

### Task 2: Define the Android-free track domain model

**Files:**
- Modify: `settings.gradle.kts`
- Create: `core/common/build.gradle.kts`
- Test: `core/common/src/test/kotlin/app/yinyuehe/core/common/model/TrackTest.kt`
- Create: `core/common/src/main/kotlin/app/yinyuehe/core/common/model/Track.kt`

**Interfaces:**
- Consumes: convention plugin `yinyuehe.kotlin.library`.
- Produces: `TrackId(String)` and `Track(id, title, artist, album, durationMs, artworkUri, sourceUri, isDemo)`.

- [ ] **Step 1: Register `:core:common` and write the failing model test**

Append to `settings.gradle.kts`:

```kotlin
include(":core:common")
```

Create `core/common/build.gradle.kts`:

```kotlin
plugins {
  id("yinyuehe.kotlin.library")
}

dependencies {
  testImplementation(libs.junit)
}
```

Create `core/common/src/test/kotlin/app/yinyuehe/core/common/model/TrackTest.kt`:

```kotlin
package app.yinyuehe.core.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TrackTest {
  @Test
  fun validTrack_keepsDomainValues() {
    val track =
      Track(
        id = TrackId("demo:morning-pulse"),
        title = "晨间节拍",
        artist = "音悦盒 Demo Band",
        album = "Compose Sessions",
        durationMs = 3_200,
        artworkUri = null,
        sourceUri = "android.resource://app.yinyuehe/1",
        isDemo = true,
      )

    assertEquals("demo:morning-pulse", track.id.value)
    assertEquals(3_200L, track.durationMs)
  }

  @Test
  fun blankId_isRejected() {
    assertThrows(IllegalArgumentException::class.java) { TrackId(" ") }
  }

  @Test
  fun blankTitle_isRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      validTrack().copy(title = " ")
    }
  }

  @Test
  fun negativeDuration_isRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      validTrack().copy(durationMs = -1)
    }
  }

  private fun validTrack() =
    Track(
      id = TrackId("demo:test"),
      title = "Test",
      artist = null,
      album = null,
      durationMs = null,
      artworkUri = null,
      sourceUri = "android.resource://app.yinyuehe/1",
      isDemo = true,
    )
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew :core:common:test --tests '*TrackTest'
```

Expected: compilation fails with unresolved references to `Track` and `TrackId`.

- [ ] **Step 3: Implement the minimal validated model**

Create `core/common/src/main/kotlin/app/yinyuehe/core/common/model/Track.kt`:

```kotlin
package app.yinyuehe.core.common.model

@JvmInline
value class TrackId(val value: String) {
  init {
    require(value.isNotBlank()) { "TrackId must not be blank" }
  }
}

data class Track(
  val id: TrackId,
  val title: String,
  val artist: String?,
  val album: String?,
  val durationMs: Long?,
  val artworkUri: String?,
  val sourceUri: String,
  val isDemo: Boolean,
) {
  init {
    require(title.isNotBlank()) { "Track title must not be blank" }
    require(sourceUri.isNotBlank()) { "Track sourceUri must not be blank" }
    require(durationMs == null || durationMs >= 0) { "Track duration must not be negative" }
  }
}
```

- [ ] **Step 4: Run the focused and module tests**

Run:

```bash
./gradlew :core:common:test
```

Expected: `TrackTest` passes and `BUILD SUCCESSFUL` appears.

- [ ] **Step 5: Commit the domain model**

```bash
git add settings.gradle.kts core/common
git commit -m "feat: add validated track domain model"
```

---

### Task 3: Migrate the four demo tracks behind a repository

**Files:**
- Modify: `settings.gradle.kts`
- Create: `core/data/build.gradle.kts`
- Create: `core/data/src/main/AndroidManifest.xml`
- Copy: `core/data/src/main/res/raw/demo_*.wav`
- Test: `core/data/src/test/kotlin/app/yinyuehe/core/data/DemoTrackCatalogTest.kt`
- Create: `core/data/src/main/kotlin/app/yinyuehe/core/data/TrackRepository.kt`
- Create: `core/data/src/main/kotlin/app/yinyuehe/core/data/DemoTrackCatalog.kt`
- Create: `core/data/src/main/kotlin/app/yinyuehe/core/data/DemoTrackRepository.kt`
- Create: `core/data/src/main/kotlin/app/yinyuehe/core/data/DataModule.kt`
- Create: `docs/assets/demo-audio.md`

**Interfaces:**
- Consumes: `Track` and `TrackId` from `:core:common`.
- Produces: `TrackRepository.observeTracks(): Flow<List<Track>>`; Hilt binds `DemoTrackRepository` as the M1 implementation.

- [ ] **Step 1: Register the data module and copy the approved assets**

Append to `settings.gradle.kts`:

```kotlin
include(":core:data")
```

Create `core/data/build.gradle.kts`:

```kotlin
plugins {
  id("yinyuehe.android.library")
  alias(libs.plugins.hilt)
  alias(libs.plugins.ksp)
}

android {
  namespace = "app.yinyuehe.core.data"
}

dependencies {
  implementation(project(":core:common"))
  implementation(libs.hilt.android)
  implementation(libs.kotlinx.coroutines.core)
  ksp(libs.hilt.compiler)

  testImplementation(libs.junit)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.robolectric)
}
```

Create `core/data/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

Run:

```bash
mkdir -p core/data/src/main/res/raw
cp ../demos/yinyuehe/src/main/res/raw/demo_city_walk.wav core/data/src/main/res/raw/
cp ../demos/yinyuehe/src/main/res/raw/demo_morning_pulse.wav core/data/src/main/res/raw/
cp ../demos/yinyuehe/src/main/res/raw/demo_night_drive.wav core/data/src/main/res/raw/
cp ../demos/yinyuehe/src/main/res/raw/demo_soft_echo.wav core/data/src/main/res/raw/
```

Expected: the four files total less than 500 KiB and retain their original names.

- [ ] **Step 2: Write the failing catalog test**

Create `core/data/src/test/kotlin/app/yinyuehe/core/data/DemoTrackCatalogTest.kt`:

```kotlin
package app.yinyuehe.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DemoTrackCatalogTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test
  fun tracks_returnsStableOrderedDemoCatalog() {
    val tracks = DemoTrackCatalog(context).tracks()

    assertEquals(
      listOf("demo:morning-pulse", "demo:city-walk", "demo:soft-echo", "demo:night-drive"),
      tracks.map { it.id.value },
    )
    assertEquals(listOf(3_200L, 3_500L, 3_800L, 4_000L), tracks.map { it.durationMs })
    assertTrue(tracks.all { it.isDemo })
    assertTrue(tracks.all { it.sourceUri.startsWith("android.resource://${context.packageName}/") })
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run:

```bash
./gradlew :core:data:testDebugUnitTest --tests '*DemoTrackCatalogTest'
```

Expected: compilation fails with unresolved reference `DemoTrackCatalog`.

- [ ] **Step 4: Implement the catalog, repository contract, and Hilt binding**

Create `core/data/src/main/kotlin/app/yinyuehe/core/data/TrackRepository.kt`:

```kotlin
package app.yinyuehe.core.data

import app.yinyuehe.core.common.model.Track
import kotlinx.coroutines.flow.Flow

interface TrackRepository {
  fun observeTracks(): Flow<List<Track>>
}
```

Create `core/data/src/main/kotlin/app/yinyuehe/core/data/DemoTrackCatalog.kt`:

```kotlin
package app.yinyuehe.core.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

internal class DemoTrackCatalog @Inject constructor(
  @ApplicationContext private val context: Context,
) {
  fun tracks(): List<Track> =
    listOf(
      track("demo:morning-pulse", "晨间节拍", 3_200L, R.raw.demo_morning_pulse),
      track("demo:city-walk", "城市漫步", 3_500L, R.raw.demo_city_walk),
      track("demo:soft-echo", "轻回声", 3_800L, R.raw.demo_soft_echo),
      track("demo:night-drive", "夜行模式", 4_000L, R.raw.demo_night_drive),
    )

  private fun track(id: String, title: String, durationMs: Long, resourceId: Int): Track =
    Track(
      id = TrackId(id),
      title = title,
      artist = "音悦盒 Demo Band",
      album = "Compose Sessions",
      durationMs = durationMs,
      artworkUri = null,
      sourceUri =
        Uri.Builder()
          .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
          .authority(context.packageName)
          .appendPath(resourceId.toString())
          .build()
          .toString(),
      isDemo = true,
    )
}
```

Create `core/data/src/main/kotlin/app/yinyuehe/core/data/DemoTrackRepository.kt`:

```kotlin
package app.yinyuehe.core.data

import app.yinyuehe.core.common.model.Track
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class DemoTrackRepository @Inject internal constructor(
  catalog: DemoTrackCatalog,
) : TrackRepository {
  private val tracks = catalog.tracks()

  override fun observeTracks(): Flow<List<Track>> = flowOf(tracks)
}
```

Create `core/data/src/main/kotlin/app/yinyuehe/core/data/DataModule.kt`:

```kotlin
package app.yinyuehe.core.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
  @Binds
  @Singleton
  abstract fun bindTrackRepository(repository: DemoTrackRepository): TrackRepository
}
```

Create `docs/assets/demo-audio.md`:

```markdown
# Demo audio provenance

The four short WAV files in `core/data/src/main/res/raw` were migrated from the Apache-2.0 licensed AndroidX Media3 release source used to build the original YinYueHe demo.

| File | SHA-256 |
| --- | --- |
| `demo_city_walk.wav` | `f05488482dc2e188efbd01ff27e059d179567e6bc4ef0b5f32daed053b737de6` |
| `demo_morning_pulse.wav` | `2da53e00228afb597b9684a18705448cc763aef3b5797bb6d9689eefe3a7713d` |
| `demo_night_drive.wav` | `0d8518dfafa3313eb802508aea459bd0a15ee9731c5486bf2fff600b976eef0b` |
| `demo_soft_echo.wav` | `d8a518992b43a5242673a7729e031a2851195f29b13014e591f6c8f4be3213fe` |
```

- [ ] **Step 5: Run data tests and Android Lint**

Run:

```bash
./gradlew :core:data:testDebugUnitTest :core:data:lintDebug
```

Expected: `DemoTrackCatalogTest` passes, Lint reports no errors, and the resource URIs use the Robolectric application package.

- [ ] **Step 6: Commit the demo data boundary**

```bash
git add settings.gradle.kts core/data docs/assets/demo-audio.md
git commit -m "feat: migrate demo tracks behind repository"
```

---

### Task 4: Add the Media3 playback service and controller boundary

**Files:**
- Modify: `settings.gradle.kts`
- Create: `core/player/build.gradle.kts`
- Create: `core/player/src/main/AndroidManifest.xml`
- Test: `core/player/src/test/kotlin/app/yinyuehe/core/player/PlayerSnapshotTest.kt`
- Create: `core/player/src/main/kotlin/app/yinyuehe/core/player/PlaybackController.kt`
- Create: `core/player/src/main/kotlin/app/yinyuehe/core/player/PlaybackState.kt`
- Create: `core/player/src/main/kotlin/app/yinyuehe/core/player/PlayerSnapshot.kt`
- Create: `core/player/src/main/kotlin/app/yinyuehe/core/player/TrackMediaItemMapper.kt`
- Create: `core/player/src/main/kotlin/app/yinyuehe/core/player/Media3PlaybackController.kt`
- Create: `core/player/src/main/kotlin/app/yinyuehe/core/player/PlayerModule.kt`
- Create: `core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackService.kt`

**Interfaces:**
- Consumes: `Track` and `TrackId`.
- Produces: `PlaybackController.state: StateFlow<PlaybackState>`, `suspend play(List<Track>, Int)`, and `togglePlayPause()`.

- [ ] **Step 1: Register the player module and write the failing state-mapping test**

Append to `settings.gradle.kts`:

```kotlin
include(":core:player")
```

Create `core/player/build.gradle.kts`:

```kotlin
plugins {
  id("yinyuehe.android.library")
  alias(libs.plugins.hilt)
  alias(libs.plugins.ksp)
}

android {
  namespace = "app.yinyuehe.core.player"
}

dependencies {
  implementation(project(":core:common"))
  implementation(libs.androidx.core.ktx)
  implementation(libs.hilt.android)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.guava)
  implementation(libs.media3.common)
  implementation(libs.media3.exoplayer)
  implementation(libs.media3.session)
  ksp(libs.hilt.compiler)

  testImplementation(libs.junit)
}
```

Create `core/player/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
  <application>
    <service
        android:name=".service.PlaybackService"
        android:exported="true"
        android:foregroundServiceType="mediaPlayback">
      <intent-filter>
        <action android:name="androidx.media3.session.MediaLibraryService" />
        <action android:name="android.media.browse.MediaBrowserService" />
      </intent-filter>
    </service>
  </application>
</manifest>
```

Create `core/player/src/test/kotlin/app/yinyuehe/core/player/PlayerSnapshotTest.kt`:

```kotlin
package app.yinyuehe.core.player

import app.yinyuehe.core.common.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayerSnapshotTest {
  @Test
  fun snapshot_mapsMediaIdsAndClampsUnknownTimes() {
    val state =
      PlayerSnapshot(
          connection = PlaybackConnection.CONNECTED,
          currentMediaId = "demo:city-walk",
          isPlaying = false,
          positionMs = -1,
          durationMs = -1,
          queueMediaIds = listOf("demo:morning-pulse", "demo:city-walk"),
        )
        .toPlaybackState()

    assertEquals(TrackId("demo:city-walk"), state.currentTrackId)
    assertEquals(0L, state.positionMs)
    assertEquals(0L, state.durationMs)
    assertEquals(2, state.queueTrackIds.size)
    assertFalse(state.isPlaying)
  }
}
```

- [ ] **Step 2: Run the mapper test to verify it fails**

Run:

```bash
./gradlew :core:player:testDebugUnitTest --tests '*PlayerSnapshotTest'
```

Expected: compilation fails with unresolved references to playback model types.

- [ ] **Step 3: Implement playback API and pure mapping**

Create `core/player/src/main/kotlin/app/yinyuehe/core/player/PlaybackState.kt`:

```kotlin
package app.yinyuehe.core.player

import app.yinyuehe.core.common.model.TrackId

enum class PlaybackConnection { CONNECTING, CONNECTED, DISCONNECTED }

data class PlaybackState(
  val connection: PlaybackConnection = PlaybackConnection.CONNECTING,
  val currentTrackId: TrackId? = null,
  val isPlaying: Boolean = false,
  val positionMs: Long = 0,
  val durationMs: Long = 0,
  val queueTrackIds: List<TrackId> = emptyList(),
)
```

Create `core/player/src/main/kotlin/app/yinyuehe/core/player/PlaybackController.kt`:

```kotlin
package app.yinyuehe.core.player

import app.yinyuehe.core.common.model.Track
import kotlinx.coroutines.flow.StateFlow

interface PlaybackController {
  val state: StateFlow<PlaybackState>
  suspend fun play(tracks: List<Track>, startIndex: Int)
  fun togglePlayPause()
}
```

Create `core/player/src/main/kotlin/app/yinyuehe/core/player/PlayerSnapshot.kt`:

```kotlin
package app.yinyuehe.core.player

import app.yinyuehe.core.common.model.TrackId

internal data class PlayerSnapshot(
  val connection: PlaybackConnection,
  val currentMediaId: String?,
  val isPlaying: Boolean,
  val positionMs: Long,
  val durationMs: Long,
  val queueMediaIds: List<String>,
)

internal fun PlayerSnapshot.toPlaybackState(): PlaybackState =
  PlaybackState(
    connection = connection,
    currentTrackId = currentMediaId?.takeIf(String::isNotBlank)?.let(::TrackId),
    isPlaying = isPlaying,
    positionMs = positionMs.coerceAtLeast(0),
    durationMs = durationMs.coerceAtLeast(0),
    queueTrackIds = queueMediaIds.filter(String::isNotBlank).map(::TrackId),
  )
```

- [ ] **Step 4: Implement Track-to-MediaItem mapping, service, controller, and Hilt binding**

Create `core/player/src/main/kotlin/app/yinyuehe/core/player/TrackMediaItemMapper.kt`:

```kotlin
package app.yinyuehe.core.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.yinyuehe.core.common.model.Track

internal fun Track.toMediaItem(): MediaItem =
  MediaItem.Builder()
    .setMediaId(id.value)
    .setUri(sourceUri)
    .setMediaMetadata(
      MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setArtworkUri(artworkUri?.let(Uri::parse))
        .setDurationMs(durationMs)
        .setIsPlayable(true)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .build()
    )
    .build()
```

Create `core/player/src/main/kotlin/app/yinyuehe/core/player/service/PlaybackService.kt`:

```kotlin
package app.yinyuehe.core.player.service

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class PlaybackService : MediaLibraryService() {
  private var session: MediaLibrarySession? = null

  override fun onCreate() {
    super.onCreate()
    val player =
      ExoPlayer.Builder(this)
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build(),
          true,
        )
        .build()
    val callback =
      object : MediaLibrarySession.Callback {
        override fun onAddMediaItems(
          mediaSession: MediaSession,
          controller: MediaSession.ControllerInfo,
          mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = Futures.immediateFuture(mediaItems)
      }
    session = MediaLibrarySession.Builder(this, player, callback).build()
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
    session

  override fun onDestroy() {
    session?.run {
      player.release()
      release()
    }
    session = null
    super.onDestroy()
  }
}
```

Create `core/player/src/main/kotlin/app/yinyuehe/core/player/Media3PlaybackController.kt`:

```kotlin
package app.yinyuehe.core.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.player.service.PlaybackService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await

class Media3PlaybackController @Inject constructor(
  @ApplicationContext context: Context,
) : PlaybackController {
  private val _state = MutableStateFlow(PlaybackState())
  override val state: StateFlow<PlaybackState> = _state.asStateFlow()

  private val controllerFuture =
    MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java)),
      )
      .buildAsync()
  private var controller: MediaController? = null

  private val listener =
    object : Player.Listener {
      override fun onEvents(player: Player, events: Player.Events) {
        _state.value = player.snapshot(PlaybackConnection.CONNECTED).toPlaybackState()
      }
    }

  init {
    controllerFuture.addListener(
      {
        runCatching { controllerFuture.get() }
          .onSuccess {
            controller = it
            it.addListener(listener)
            _state.value = it.snapshot(PlaybackConnection.CONNECTED).toPlaybackState()
          }
          .onFailure {
            _state.value = PlaybackState(connection = PlaybackConnection.DISCONNECTED)
          }
      },
      ContextCompat.getMainExecutor(context),
    )
  }

  override suspend fun play(tracks: List<Track>, startIndex: Int) {
    require(tracks.isNotEmpty()) { "Playback queue must not be empty" }
    require(startIndex in tracks.indices) { "startIndex must reference the queue" }
    val mediaController = controllerFuture.await()
    mediaController.setMediaItems(tracks.map { it.toMediaItem() }, startIndex, 0)
    mediaController.prepare()
    mediaController.play()
  }

  override fun togglePlayPause() {
    controller?.let { if (it.isPlaying) it.pause() else it.play() }
  }
}

private fun Player.snapshot(connection: PlaybackConnection): PlayerSnapshot =
  PlayerSnapshot(
    connection = connection,
    currentMediaId = currentMediaItem?.mediaId,
    isPlaying = isPlaying,
    positionMs = currentPosition,
    durationMs = duration.takeUnless { it == C.TIME_UNSET } ?: 0,
    queueMediaIds = List(mediaItemCount) { getMediaItemAt(it).mediaId },
  )
```

Create `core/player/src/main/kotlin/app/yinyuehe/core/player/PlayerModule.kt`:

```kotlin
package app.yinyuehe.core.player

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PlayerModule {
  @Binds
  @Singleton
  abstract fun bindPlaybackController(controller: Media3PlaybackController): PlaybackController
}
```

- [ ] **Step 5: Run player tests, Lint, and compilation**

Run:

```bash
./gradlew :core:player:testDebugUnitTest :core:player:lintDebug :core:player:assembleDebug
```

Expected: `PlayerSnapshotTest` passes, the service manifest merges, and all tasks finish with `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit the player boundary**

```bash
git add settings.gradle.kts core/player
git commit -m "feat: add Media3 playback service boundary"
```

---

### Task 5: Build the warm record design system

**Files:**
- Modify: `settings.gradle.kts`
- Create: `core/designsystem/build.gradle.kts`
- Create: `core/designsystem/src/main/AndroidManifest.xml`
- Test: `core/designsystem/src/test/kotlin/app/yinyuehe/core/designsystem/theme/ThemeContrastTest.kt`
- Create: `core/designsystem/src/main/kotlin/app/yinyuehe/core/designsystem/theme/Color.kt`
- Create: `core/designsystem/src/main/kotlin/app/yinyuehe/core/designsystem/theme/Theme.kt`

**Interfaces:**
- Consumes: Compose Material 3.
- Produces: `YinYueHeTheme(darkTheme, content)` and semantic light/dark color schemes.

- [ ] **Step 1: Register the module and write the failing contrast test**

Append to `settings.gradle.kts`:

```kotlin
include(":core:designsystem")
```

Create `core/designsystem/build.gradle.kts`:

```kotlin
plugins {
  id("yinyuehe.android.library")
  id("yinyuehe.android.compose")
}

android {
  namespace = "app.yinyuehe.core.designsystem"
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  debugImplementation(libs.androidx.compose.ui.tooling)
  testImplementation(libs.junit)
}
```

Create `core/designsystem/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

Create `core/designsystem/src/test/kotlin/app/yinyuehe/core/designsystem/theme/ThemeContrastTest.kt`:

```kotlin
package app.yinyuehe.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
  @Test
  fun primaryContent_meetsWcagAa() {
    assertTrue(contrast(OnLightPrimary, LightPrimary) >= 4.5)
    assertTrue(contrast(OnDarkPrimary, DarkPrimary) >= 4.5)
  }

  @Test
  fun backgroundContent_meetsWcagAa() {
    assertTrue(contrast(LightOnBackground, LightBackground) >= 4.5)
    assertTrue(contrast(DarkOnBackground, DarkBackground) >= 4.5)
  }

  private fun contrast(foreground: Color, background: Color): Float {
    val lighter = maxOf(foreground.luminance(), background.luminance())
    val darker = minOf(foreground.luminance(), background.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
  }
}
```

- [ ] **Step 2: Run the contrast test to verify it fails**

Run:

```bash
./gradlew :core:designsystem:testDebugUnitTest --tests '*ThemeContrastTest'
```

Expected: compilation fails because the semantic colors do not exist.

- [ ] **Step 3: Implement semantic colors and theme**

Create `core/designsystem/src/main/kotlin/app/yinyuehe/core/designsystem/theme/Color.kt`:

```kotlin
package app.yinyuehe.core.designsystem.theme

import androidx.compose.ui.graphics.Color

internal val LightPrimary = Color(0xFFA83E2C)
internal val OnLightPrimary = Color(0xFFFFFFFF)
internal val LightSecondary = Color(0xFF596B55)
internal val LightBackground = Color(0xFFFFF8EF)
internal val LightOnBackground = Color(0xFF27211D)
internal val LightSurface = Color(0xFFFFFDFC)

internal val DarkPrimary = Color(0xFFFFB4A2)
internal val OnDarkPrimary = Color(0xFF5F160B)
internal val DarkSecondary = Color(0xFFBFCDB8)
internal val DarkBackground = Color(0xFF191512)
internal val DarkOnBackground = Color(0xFFEEE0D5)
internal val DarkSurface = Color(0xFF241F1B)
```

Create `core/designsystem/src/main/kotlin/app/yinyuehe/core/designsystem/theme/Theme.kt`:

```kotlin
package app.yinyuehe.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors =
  lightColorScheme(
    primary = LightPrimary,
    onPrimary = OnLightPrimary,
    secondary = LightSecondary,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnBackground,
  )

private val DarkColors =
  darkColorScheme(
    primary = DarkPrimary,
    onPrimary = OnDarkPrimary,
    secondary = DarkSecondary,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnBackground,
  )

@Composable
fun YinYueHeTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = if (darkTheme) DarkColors else LightColors,
    content = content,
  )
}
```

- [ ] **Step 4: Verify contrast and module compilation**

Run:

```bash
./gradlew :core:designsystem:testDebugUnitTest :core:designsystem:lintDebug
```

Expected: both WCAG AA tests pass and Lint reports no errors.

- [ ] **Step 5: Commit the design system**

```bash
git add settings.gradle.kts core/designsystem
git commit -m "feat: add warm record design system"
```

---

### Task 6: Add test fakes and the demo library feature

**Files:**
- Modify: `settings.gradle.kts`
- Create: `core/testing/build.gradle.kts`
- Create: `core/testing/src/main/AndroidManifest.xml`
- Create: `core/testing/src/main/kotlin/app/yinyuehe/core/testing/MainDispatcherRule.kt`
- Create: `core/testing/src/main/kotlin/app/yinyuehe/core/testing/FakeTrackRepository.kt`
- Create: `core/testing/src/main/kotlin/app/yinyuehe/core/testing/FakePlaybackController.kt`
- Create: `feature/library/build.gradle.kts`
- Create: `feature/library/src/main/AndroidManifest.xml`
- Test: `feature/library/src/test/kotlin/app/yinyuehe/feature/library/LibraryViewModelTest.kt`
- Test: `feature/library/src/androidTest/kotlin/app/yinyuehe/feature/library/LibraryScreenTest.kt`
- Create: `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryUiState.kt`
- Create: `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryViewModel.kt`
- Create: `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryScreen.kt`

**Interfaces:**
- Consumes: `TrackRepository`, `PlaybackController`, `Track`, and Material theme tokens.
- Produces: `LibraryViewModel`, `LibraryRoute(viewModel)`, and stateless `LibraryScreen(state, onTrackClick)`.

- [ ] **Step 1: Add `:core:testing` with exact fakes**

Append to `settings.gradle.kts`:

```kotlin
include(":core:testing")
include(":feature:library")
```

Create `core/testing/build.gradle.kts`:

```kotlin
plugins {
  id("yinyuehe.android.library")
}

android {
  namespace = "app.yinyuehe.core.testing"
}

dependencies {
  api(project(":core:common"))
  api(project(":core:data"))
  api(project(":core:player"))
  api(libs.junit)
  api(libs.kotlinx.coroutines.test)
}
```

Create `core/testing/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

Create `core/testing/src/main/kotlin/app/yinyuehe/core/testing/MainDispatcherRule.kt`:

```kotlin
package app.yinyuehe.core.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
  private val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
  override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
  override fun finished(description: Description) = Dispatchers.resetMain()
}
```

Create `core/testing/src/main/kotlin/app/yinyuehe/core/testing/FakeTrackRepository.kt`:

```kotlin
package app.yinyuehe.core.testing

import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.data.TrackRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeTrackRepository(initialTracks: List<Track> = emptyList()) : TrackRepository {
  private val tracks = MutableStateFlow(initialTracks)
  override fun observeTracks(): Flow<List<Track>> = tracks
  fun setTracks(value: List<Track>) { tracks.value = value }
}
```

Create `core/testing/src/main/kotlin/app/yinyuehe/core/testing/FakePlaybackController.kt`:

```kotlin
package app.yinyuehe.core.testing

import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.player.PlaybackController
import app.yinyuehe.core.player.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakePlaybackController : PlaybackController {
  data class PlayRequest(val tracks: List<Track>, val startIndex: Int)

  private val mutableState = MutableStateFlow(PlaybackState())
  override val state: StateFlow<PlaybackState> = mutableState
  val playRequests = mutableListOf<PlayRequest>()
  var toggleCount = 0

  override suspend fun play(tracks: List<Track>, startIndex: Int) {
    playRequests += PlayRequest(tracks, startIndex)
  }

  override fun togglePlayPause() {
    toggleCount += 1
  }
}
```

- [ ] **Step 2: Configure the feature and write failing ViewModel/UI tests**

Create `feature/library/build.gradle.kts`:

```kotlin
plugins {
  id("yinyuehe.android.library")
  id("yinyuehe.android.compose")
  alias(libs.plugins.hilt)
  alias(libs.plugins.ksp)
}

android {
  namespace = "app.yinyuehe.feature.library"
}

dependencies {
  implementation(project(":core:common"))
  implementation(project(":core:data"))
  implementation(project(":core:player"))
  implementation(project(":core:designsystem"))
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.ktx)
  implementation(libs.hilt.android)
  ksp(libs.hilt.compiler)

  testImplementation(project(":core:testing"))
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.espresso.core)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
}
```

Create `feature/library/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

Create `feature/library/src/test/kotlin/app/yinyuehe/feature/library/LibraryViewModelTest.kt`:

```kotlin
package app.yinyuehe.feature.library

import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.testing.FakePlaybackController
import app.yinyuehe.core.testing.FakeTrackRepository
import app.yinyuehe.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun repositoryTracks_areExposedInOrder() = runTest {
    val repository = FakeTrackRepository(listOf(track("one"), track("two")))
    val viewModel = LibraryViewModel(repository, FakePlaybackController())
    assertEquals(listOf("one", "two"), viewModel.uiState.value.tracks.map { it.id.value })
  }

  @Test
  fun selectingTrack_playsWholeQueueAtSelectedIndex() = runTest {
    val tracks = listOf(track("one"), track("two"))
    val player = FakePlaybackController()
    val viewModel = LibraryViewModel(FakeTrackRepository(tracks), player)

    viewModel.onTrackClick(TrackId("two"))

    assertEquals(1, player.playRequests.single().startIndex)
    assertEquals(tracks, player.playRequests.single().tracks)
  }

  private fun track(id: String) =
    Track(TrackId(id), id, null, null, 1_000, null, "android.resource://app.yinyuehe/$id", true)
}
```

Create `feature/library/src/androidTest/kotlin/app/yinyuehe/feature/library/LibraryScreenTest.kt`:

```kotlin
package app.yinyuehe.feature.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.designsystem.theme.YinYueHeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LibraryScreenTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun trackRow_isVisibleAndClickable() {
    var selected: TrackId? = null
    val track = Track(TrackId("demo:test"), "晨间节拍", "Demo Band", null, 3_200, null, "uri", true)
    composeRule.setContent {
      YinYueHeTheme {
        LibraryScreen(LibraryUiState(tracks = listOf(track))) { selected = it }
      }
    }

    composeRule.onNodeWithText("晨间节拍").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("播放晨间节拍").performClick()
    assertEquals(TrackId("demo:test"), selected)
  }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```bash
./gradlew :feature:library:testDebugUnitTest --tests '*LibraryViewModelTest'
```

Expected: compilation fails because `LibraryViewModel`, `LibraryUiState`, and `LibraryScreen` do not exist.

- [ ] **Step 4: Implement ViewModel and Compose library screen**

Create `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryUiState.kt`:

```kotlin
package app.yinyuehe.feature.library

import app.yinyuehe.core.common.model.Track

data class LibraryUiState(
  val isLoading: Boolean = true,
  val tracks: List<Track> = emptyList(),
)
```

Create `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryViewModel.kt`:

```kotlin
package app.yinyuehe.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.data.TrackRepository
import app.yinyuehe.core.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class LibraryViewModel @Inject constructor(
  repository: TrackRepository,
  private val playbackController: PlaybackController,
) : ViewModel() {
  val uiState: StateFlow<LibraryUiState> =
    repository
      .observeTracks()
      .map { LibraryUiState(isLoading = false, tracks = it) }
      .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

  fun onTrackClick(trackId: TrackId) {
    val tracks = uiState.value.tracks
    val index = tracks.indexOfFirst { it.id == trackId }
    if (index < 0) return
    viewModelScope.launch { playbackController.play(tracks, index) }
  }
}
```

Create `feature/library/src/main/kotlin/app/yinyuehe/feature/library/LibraryScreen.kt`:

```kotlin
package app.yinyuehe.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import java.util.Locale

@Composable
fun LibraryRoute(viewModel: LibraryViewModel) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  LibraryScreen(state, viewModel::onTrackClick)
}

@Composable
fun LibraryScreen(
  state: LibraryUiState,
  onTrackClick: (TrackId) -> Unit,
) {
  Scaffold { innerPadding ->
    Column(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
    ) {
      Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
        Text("下午好", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        Text("音悦盒", style = MaterialTheme.typography.headlineLarge)
        Text("随时可播放的本地音乐", style = MaterialTheme.typography.bodyMedium)
      }
      if (state.isLoading) {
        Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
          CircularProgressIndicator()
        }
      } else {
        LazyColumn(
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          items(state.tracks, key = { it.id.value }) { track ->
            TrackRow(track, onTrackClick)
          }
        }
      }
    }
  }
}

@Composable
private fun TrackRow(track: Track, onTrackClick: (TrackId) -> Unit) {
  Card(
    modifier =
      Modifier.fillMaxWidth()
        .clickable { onTrackClick(track.id) }
        .semantics { contentDescription = "播放${track.title}" },
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("◉", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
      Spacer(Modifier.size(14.dp))
      Column(Modifier.weight(1f)) {
        Text(track.title, style = MaterialTheme.typography.titleMedium)
        Text(track.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
      }
      Text(formatDuration(track.durationMs), style = MaterialTheme.typography.labelMedium)
    }
  }
}

private fun formatDuration(durationMs: Long?): String {
  if (durationMs == null) return "--:--"
  val totalSeconds = durationMs / 1_000
  return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
```

- [ ] **Step 5: Run ViewModel tests and compile the UI test APK**

Run:

```bash
./gradlew :feature:library:testDebugUnitTest :feature:library:assembleDebugAndroidTest :feature:library:lintDebug
```

Expected: both ViewModel tests pass; the Compose instrumentation test APK compiles; Lint has no errors.

- [ ] **Step 6: Run the Compose UI test on an available emulator**

Run:

```bash
adb devices
./gradlew :feature:library:connectedDebugAndroidTest
```

Expected: at least one emulator is listed as `device`, and `LibraryScreenTest` passes. If no emulator exists, create an API 36 AVD before executing this step; do not mark the task complete from compilation alone.

- [ ] **Step 7: Commit the library feature**

```bash
git add settings.gradle.kts core/testing feature/library
git commit -m "feat: add demo library feature"
```

---

### Task 7: Integrate the app shell and verify real demo playback

**Files:**
- Modify: `settings.gradle.kts`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/app/yinyuehe/YinYueHeApplication.kt`
- Create: `app/src/main/kotlin/app/yinyuehe/MainActivity.kt`
- Create: `app/src/main/kotlin/app/yinyuehe/YinYueHeApp.kt`
- Test: `app/src/androidTest/kotlin/app/yinyuehe/AppLaunchTest.kt`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/themes.xml`
- Create: `app/src/main/res/values-night/themes.xml`

**Interfaces:**
- Consumes: `LibraryRoute`, Hilt bindings from data/player, and `YinYueHeTheme`.
- Produces: installable `app-debug.apk` with launcher Activity and merged playback service.

- [ ] **Step 1: Register and configure `:app`**

Append to `settings.gradle.kts`:

```kotlin
include(":app")
```

Create `app/build.gradle.kts`:

```kotlin
plugins {
  id("yinyuehe.android.application")
  id("yinyuehe.android.compose")
  alias(libs.plugins.hilt)
  alias(libs.plugins.ksp)
}

android {
  namespace = "app.yinyuehe"
  defaultConfig {
    applicationId = "app.yinyuehe"
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"
  }
  buildTypes {
    release {
      isMinifyEnabled = false
    }
  }
}

dependencies {
  implementation(project(":core:data"))
  implementation(project(":core:designsystem"))
  implementation(project(":core:player"))
  implementation(project(":feature:library"))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.hilt.android)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  ksp(libs.hilt.compiler)

  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.espresso.core)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
}
```

- [ ] **Step 2: Write the failing app launch test**

Create `app/src/androidTest/kotlin/app/yinyuehe/AppLaunchTest.kt`:

```kotlin
package app.yinyuehe

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLaunchTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun launch_displaysDemoCatalog() {
    composeRule.onNodeWithText("晨间节拍").assertIsDisplayed()
    composeRule.onNodeWithText("夜行模式").assertIsDisplayed()
  }
}
```

- [ ] **Step 3: Compile the test to verify it fails**

Run:

```bash
./gradlew :app:assembleDebugAndroidTest
```

Expected: compilation fails with unresolved reference `MainActivity`.

- [ ] **Step 4: Add Application, Activity, root Composable, and resources**

Create `app/src/main/kotlin/app/yinyuehe/YinYueHeApplication.kt`:

```kotlin
package app.yinyuehe

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class YinYueHeApplication : Application()
```

Create `app/src/main/kotlin/app/yinyuehe/MainActivity.kt`:

```kotlin
package app.yinyuehe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent { YinYueHeApp() }
  }
}
```

Create `app/src/main/kotlin/app/yinyuehe/YinYueHeApp.kt`:

```kotlin
package app.yinyuehe

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import app.yinyuehe.core.designsystem.theme.YinYueHeTheme
import app.yinyuehe.feature.library.LibraryRoute
import app.yinyuehe.feature.library.LibraryViewModel

@Composable
fun YinYueHeApp() {
  YinYueHeTheme {
    LibraryRoute(viewModel<LibraryViewModel>())
  }
}
```

Create `app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
  <application
      android:name=".YinYueHeApplication"
      android:allowBackup="false"
      android:label="@string/app_name"
      android:supportsRtl="true"
      android:theme="@style/Theme.YinYueHe">
    <activity
        android:name=".MainActivity"
        android:exported="true">
      <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
      </intent-filter>
    </activity>
  </application>
</manifest>
```

Create `app/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
  <string name="app_name">音悦盒</string>
</resources>
```

Create `app/src/main/res/values/themes.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
  <style name="Theme.YinYueHe" parent="android:style/Theme.Material.Light.NoActionBar">
    <item name="android:fontFamily">sans</item>
    <item name="android:windowLightStatusBar">true</item>
    <item name="android:navigationBarColor">#FFF8EF</item>
    <item name="android:statusBarColor">@android:color/transparent</item>
  </style>
</resources>
```

Create `app/src/main/res/values-night/themes.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
  <style name="Theme.YinYueHe" parent="android:style/Theme.Material.NoActionBar">
    <item name="android:fontFamily">sans</item>
    <item name="android:windowLightStatusBar">false</item>
    <item name="android:navigationBarColor">#191512</item>
    <item name="android:statusBarColor">@android:color/transparent</item>
  </style>
</resources>
```

- [ ] **Step 5: Run all local quality gates and the launch test**

Run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
```

Expected: all unit tests and `AppLaunchTest` pass, every Android module passes Lint, and `app/build/outputs/apk/debug/app-debug.apk` is generated.

- [ ] **Step 6: Install and verify actual playback on an emulator or device**

Run:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n app.yinyuehe/.MainActivity
```

Then tap “晨间节拍”. Expected:

- The Activity launch result reports `Status: ok`.
- The four demo tracks render in the approved order.
- Tapping “晨间节拍” starts audible playback.
- `adb shell dumpsys media_session` shows an active session owned by `app.yinyuehe`.
- On API 33+, notification permission may still be denied in M1; playback must work, and permission UX is implemented in M2.

- [ ] **Step 7: Commit the runnable app**

```bash
git add settings.gradle.kts app
git commit -m "feat: integrate runnable demo player app"
```

---

### Task 8: Add CI, repository hygiene, and milestone documentation

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `.gitignore`
- Create: `README.md`
- Copy: `../LICENSE` to `LICENSE`

**Interfaces:**
- Consumes: root Gradle quality gates.
- Produces: reproducible GitHub CI and a recruiter-readable M1 repository entry point.

- [ ] **Step 1: Add repository hygiene and Apache-2.0 license**

Create `.gitignore`:

```gitignore
.gradle/
.idea/
.kotlin/
**/build/
local.properties
*.iml
*.jks
*.keystore
keystore.properties
.DS_Store
.superpowers/
```

Run:

```bash
cp ../LICENSE LICENSE
```

Expected: the repository uses the same Apache-2.0 license as the migrated source assets, and signing material is ignored.

- [ ] **Step 2: Add the pull-request CI workflow**

Create `.github/workflows/ci.yml`:

```yaml
name: Android CI

on:
  pull_request:
  push:
    branches: [main]

permissions:
  contents: read

jobs:
  verify:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - uses: gradle/actions/setup-gradle@v4
      - name: Unit tests, lint, and debug build
        run: ./gradlew testDebugUnitTest lintDebug assembleDebug --stacktrace
```

- [ ] **Step 3: Add a truthful M1 README**

Create `README.md`:

````markdown
# 音悦盒 · YinYueHe

音悦盒是一款本地优先的 Android 音乐播放器，也是一份可复现的 Android 客户端工程作品。

## 当前里程碑

M1 提供四首内置演示曲、Jetpack Compose 曲库首页和基于 Media3 `MediaLibraryService` 的后台播放链路。它不依赖设备本地音乐、网络、账号或后端。

## 技术结构

- Kotlin + Jetpack Compose + Material 3
- MVVM 与单向数据流
- Hilt 依赖注入
- Media3 ExoPlayer、MediaSession、MediaLibraryService
- 多模块 Gradle 工程、version catalog 与 convention plugins
- JUnit、Robolectric、Compose UI Test、Android Lint、GitHub Actions

## 构建

环境：JDK 17、Android SDK 36。

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`

## 设计与计划

- 产品设计：`docs/superpowers/specs/2026-07-13-yinyuehe-product-design.md`
- 6–8 周路线图：`docs/superpowers/plans/2026-07-13-yinyuehe-roadmap.md`
- M1 实施计划：`docs/superpowers/plans/2026-07-13-m1-foundation-demo-playback.md`

## 许可

代码和内置演示音频按 Apache License 2.0 提供。演示音频来源与校验和见 `docs/assets/demo-audio.md`。
````

- [ ] **Step 4: Verify exactly what CI will run**

Run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug --stacktrace
git status --short
```

Expected: Gradle reports `BUILD SUCCESSFUL`; Git status lists only `.github/workflows/ci.yml`, `.gitignore`, `README.md`, and `LICENSE` before staging.

- [ ] **Step 5: Commit CI and documentation**

```bash
git add .github .gitignore README.md LICENSE
git commit -m "ci: verify tests lint and debug build"
```

---

## M1 Completion Gate

Run from a clean checkout with JDK 17 and an API 36 emulator:

```bash
./gradlew clean testDebugUnitTest lintDebug assembleDebug
./gradlew :feature:library:connectedDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n app.yinyuehe/.MainActivity
adb shell dumpsys media_session
git status --short --branch
```

M1 is complete only when:

- Gradle and the connected UI test report success.
- The four Demo tracks appear in stable order.
- Selecting each track starts playback through `PlaybackService`.
- The active media session belongs to `app.yinyuehe`.
- There are no Android Lint errors or warnings promoted to errors.
- The Git worktree is clean and the eight task commits are present.
- No Media3 source module, signing key, API secret, personal media file, or generated build output is committed.
