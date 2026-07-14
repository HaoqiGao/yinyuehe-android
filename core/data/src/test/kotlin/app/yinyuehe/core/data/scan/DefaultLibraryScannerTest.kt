package app.yinyuehe.core.data.scan

import app.yinyuehe.core.data.local.db.RoomDatabaseRule
import app.yinyuehe.core.data.local.mediastore.MediaStoreAudio
import app.yinyuehe.core.data.local.mediastore.MediaStoreGateway
import app.yinyuehe.core.data.local.mediastore.stableMediaId
import kotlinx.coroutines.CancellationException
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
}

private class FakeMediaStoreGateway : MediaStoreGateway {
  val rowsByVolume = linkedMapOf<String, List<MediaStoreAudio>>()
  var failure: Throwable? = null

  override suspend fun externalVolumeNames(): List<String> = rowsByVolume.keys.toList()

  override suspend fun readVolume(volumeName: String): List<MediaStoreAudio> {
    failure?.let { throw it }
    return rowsByVolume.getValue(volumeName)
  }
}
