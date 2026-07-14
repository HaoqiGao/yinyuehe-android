package app.yinyuehe.core.data.scan

import app.yinyuehe.core.data.local.db.RoomDatabaseRule
import app.yinyuehe.core.data.local.db.DEMO_VOLUME_NAME
import app.yinyuehe.core.data.local.db.entity.ScanCheckpointEntity
import app.yinyuehe.core.data.local.db.trackEntity
import app.yinyuehe.core.data.local.mediastore.MediaStoreAudio
import app.yinyuehe.core.data.local.mediastore.MediaStoreGateway
import app.yinyuehe.core.data.local.mediastore.stableMediaId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DefaultLibraryScannerTest {
  @get:Rule val databaseRule = RoomDatabaseRule()

  private val database
    get() = databaseRule.database

  private val gateway = FakeMediaStoreGateway()

  private val scanner
    get() =
      DefaultLibraryScanner(
        gateway = gateway,
        database = database,
        trackDao = database.trackDao(),
        checkpointDao = database.scanCheckpointDao(),
      )

  @Test
  fun scan_commitsCompletedVolumeAndReportsCounts() = runTest {
    gateway.rowsByVolume["external_primary"] =
      listOf(audio("external_primary", 1), audio("external_primary", 2))

    val result = scanner.scan().getOrThrow()

    assertEquals(2, result.discovered)
    assertEquals(0, result.unavailable)
    assertEquals(1, result.volumeCount)
    assertEquals(2, database.trackDao().getAll().size)
  }

  @Test
  fun scan_marksRowsMissingFromTheNextCompleteSnapshotUnavailable() = runTest {
    gateway.rowsByVolume["external_primary"] =
      listOf(audio("external_primary", 1), audio("external_primary", 2))
    scanner.scan().getOrThrow()
    gateway.rowsByVolume["external_primary"] = listOf(audio("external_primary", 1))

    val result = scanner.scan().getOrThrow()

    val removedId = stableMediaId("external_primary", 2)
    assertEquals(1, result.unavailable)
    assertFalse(database.trackDao().findByMediaId(removedId)!!.isAvailable)
    assertTrue(
      database.trackDao().findByMediaId(stableMediaId("external_primary", 1))!!.isAvailable
    )
  }

  @Test
  fun scan_unmountsAnAbsentVolumeAndReportsNewlyUnavailableRows() = runTest {
    gateway.rowsByVolume["external_primary"] = listOf(audio("external_primary", 1))
    gateway.rowsByVolume["sd-card"] = listOf(audio("sd-card", 2))
    scanner.scan().getOrThrow()
    gateway.rowsByVolume.remove("sd-card")

    val result = scanner.scan().getOrThrow()

    assertEquals(1, result.discovered)
    assertEquals(1, result.unavailable)
    assertEquals(1, result.volumeCount)
    assertTrue(
      database.trackDao().findByMediaId(stableMediaId("external_primary", 1))!!.isAvailable
    )
    assertFalse(database.trackDao().findByMediaId(stableMediaId("sd-card", 2))!!.isAvailable)
    assertTrue(database.scanCheckpointDao().find("external_primary")!!.isMounted)
    assertFalse(database.scanCheckpointDao().find("sd-card")!!.isMounted)
  }

  @Test
  fun scan_emptySuccessfulEnumerationUnmountsEveryPreviouslyKnownVolume() = runTest {
    gateway.rowsByVolume["external_primary"] = listOf(audio("external_primary", 1))
    gateway.rowsByVolume["sd-card"] = listOf(audio("sd-card", 2))
    scanner.scan().getOrThrow()
    gateway.rowsByVolume.clear()

    val result = scanner.scan().getOrThrow()

    assertEquals(0, result.discovered)
    assertEquals(2, result.unavailable)
    assertEquals(0, result.volumeCount)
    assertTrue(database.trackDao().getAll().none { it.isAvailable })
    assertTrue(database.scanCheckpointDao().getAll().none { it.isMounted })
  }

  @Test
  fun scan_api29PrimaryVolumeReconcilesTheLegacyExternalVolume() = runTest {
    gateway.rowsByVolume["external"] = listOf(audio("external", 42))
    scanner.scan().getOrThrow()
    gateway.rowsByVolume.clear()
    gateway.rowsByVolume["external_primary"] = listOf(audio("external_primary", 42))

    val result = scanner.scan().getOrThrow()

    val available = database.trackDao().observeAvailableTracks(DEMO_VOLUME_NAME).first()
    assertEquals(1, result.unavailable)
    assertEquals(listOf(stableMediaId("external_primary", 42)), available.map { it.mediaId })
    assertFalse(database.trackDao().findByMediaId(stableMediaId("external", 42))!!.isAvailable)
    assertFalse(database.scanCheckpointDao().find("external")!!.isMounted)
  }

  @Test
  fun scan_absentVolumesNeverMarkReservedDemoAnchorsUnavailable() = runTest {
    gateway.rowsByVolume["external_primary"] = listOf(audio("external_primary", 1))
    scanner.scan().getOrThrow()
    database.trackDao().upsertTracks(
      listOf(
        trackEntity(
          mediaId = "demo:anchor",
          volumeName = DEMO_VOLUME_NAME,
          mediaStoreId = -1,
          isAvailable = true,
        )
      )
    )
    database.scanCheckpointDao().upsert(checkpoint(DEMO_VOLUME_NAME))
    gateway.rowsByVolume.clear()

    val result = scanner.scan().getOrThrow()

    assertEquals(1, result.unavailable)
    assertTrue(database.trackDao().findByMediaId("demo:anchor")!!.isAvailable)
    assertTrue(database.scanCheckpointDao().find(DEMO_VOLUME_NAME)!!.isMounted)
  }

  @Test
  fun failedVolumeQuery_doesNotMutateItsPreviousRows() = runTest {
    gateway.rowsByVolume["external_primary"] = listOf(audio("external_primary", 1, "Before"))
    scanner.scan().getOrThrow()
    gateway.failure = IllegalStateException("provider failed")

    val result = scanner.scan()

    val stored =
      database.trackDao().findByMediaId(stableMediaId("external_primary", 1))!!
    assertTrue(result.isFailure)
    assertTrue(stored.isAvailable)
    assertEquals("Before", stored.title)
  }

  @Test
  fun laterVolumeQueryFailure_rollsBackEveryCurrentVolume() = runTest {
    gateway.rowsByVolume["external_primary"] =
      listOf(audio("external_primary", 1, "Primary before"))
    gateway.rowsByVolume["sd-card"] = listOf(audio("sd-card", 2, "SD before"))
    scanner.scan().getOrThrow()
    val before = databaseSnapshot()
    gateway.rowsByVolume["external_primary"] =
      listOf(audio("external_primary", 1, "Primary after"))
    gateway.failuresByVolume["sd-card"] = IllegalStateException("SD provider failed")

    val result = scanner.scan()

    assertTrue(result.isFailure)
    assertEquals(before, databaseSnapshot())
  }

  @Test
  fun laterVolumeValidationFailure_rollsBackEveryCurrentVolume() = runTest {
    gateway.rowsByVolume["external_primary"] =
      listOf(audio("external_primary", 1, "Primary before"))
    gateway.rowsByVolume["sd-card"] = listOf(audio("sd-card", 2, "SD before"))
    scanner.scan().getOrThrow()
    val before = databaseSnapshot()
    gateway.rowsByVolume["external_primary"] =
      listOf(audio("external_primary", 1, "Primary after"))
    gateway.rowsByVolume["sd-card"] = listOf(audio("wrong-volume", 2, "Invalid"))

    val result = scanner.scan()

    assertTrue(result.isFailure)
    assertEquals(before, databaseSnapshot())
  }

  @Test
  fun laterVolumeCancellation_rollsBackEveryCurrentVolume() = runTest {
    gateway.rowsByVolume["external_primary"] =
      listOf(audio("external_primary", 1, "Primary before"))
    gateway.rowsByVolume["sd-card"] = listOf(audio("sd-card", 2, "SD before"))
    scanner.scan().getOrThrow()
    val before = databaseSnapshot()
    gateway.rowsByVolume["external_primary"] =
      listOf(audio("external_primary", 1, "Primary after"))
    gateway.failuresByVolume["sd-card"] = CancellationException("cancel SD read")

    try {
      scanner.scan()
      throw AssertionError("Expected scan cancellation to propagate")
    } catch (_: CancellationException) {
      // Expected: no current-volume snapshot has been committed yet.
    }

    assertEquals(before, databaseSnapshot())
  }

  @Test
  fun volumeEnumerationFailure_doesNotMutateAnyVolume() = runTest {
    gateway.rowsByVolume["external_primary"] = listOf(audio("external_primary", 1))
    scanner.scan().getOrThrow()
    val before = databaseSnapshot()
    gateway.enumerationFailure = IllegalStateException("volume enumeration failed")

    val result = scanner.scan()

    assertTrue(result.isFailure)
    assertEquals(before, databaseSnapshot())
  }

  @Test
  fun cancellation_isPropagatedToTheCaller() = runTest {
    gateway.rowsByVolume["external_primary"] = listOf(audio("external_primary", 1))
    gateway.failure = CancellationException("cancel scan")

    try {
      scanner.scan()
      throw AssertionError("Expected scan cancellation to propagate")
    } catch (_: CancellationException) {
      // Expected: cancellation is control flow, not an ordinary failed result.
    }
  }

  private fun audio(volume: String, id: Long, title: String = "Track $id") =
    MediaStoreAudio(
      volumeName = volume,
      mediaStoreId = id,
      contentUri = "content://media/$volume/audio/media/$id",
      displayName = "$title.mp3",
      title = title,
      artist = "Artist",
      album = "Album",
      albumId = 3,
      artworkUri = null,
      durationMs = 1_000,
      mimeType = "audio/mpeg",
      sizeBytes = 2_000,
      dateAddedSeconds = 3_000,
      dateModifiedSeconds = 4_000,
    )

  private suspend fun databaseSnapshot() =
    database.trackDao().getAll() to database.scanCheckpointDao().getAll()

  private fun checkpoint(volumeName: String) =
    ScanCheckpointEntity(
      volumeName = volumeName,
      mediaStoreVersion = null,
      generationUpperBound = null,
      lastFullScanEpochMs = 1,
      lastSuccessfulScanEpochMs = 1,
      lastScanToken = "seed",
      isMounted = true,
    )
}

private class FakeMediaStoreGateway : MediaStoreGateway {
  val rowsByVolume = linkedMapOf<String, List<MediaStoreAudio>>()
  val failuresByVolume = mutableMapOf<String, Throwable>()
  var enumerationFailure: Throwable? = null
  var failure: Throwable? = null

  override suspend fun externalVolumeNames(): List<String> {
    enumerationFailure?.let { throw it }
    return rowsByVolume.keys.toList()
  }

  override suspend fun readVolume(volumeName: String): List<MediaStoreAudio> {
    failure?.let { throw it }
    failuresByVolume[volumeName]?.let { throw it }
    return rowsByVolume.getValue(volumeName)
  }
}
