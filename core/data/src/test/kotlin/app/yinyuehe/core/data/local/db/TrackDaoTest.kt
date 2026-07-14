package app.yinyuehe.core.data.local.db

import android.database.sqlite.SQLiteConstraintException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackDaoTest {
  @get:Rule
  val databaseRule = RoomDatabaseRule()

  private val dao
    get() = databaseRule.database.trackDao()

  @Test
  fun availableTracks_areDeterministicAndUnavailableRowsAreHidden() = runTest {
    dao.upsertTracks(
      listOf(
        trackEntity(mediaId = "local:b", titleSortKey = "same", isAvailable = true),
        trackEntity(mediaId = "local:a", titleSortKey = "same", isAvailable = true),
        trackEntity(mediaId = "local:hidden", titleSortKey = "aaa", isAvailable = false),
      )
    )

    assertEquals(
      listOf("local:a", "local:b"),
      dao.observeAvailableTracks(excludedVolumeName = DEMO_VOLUME_NAME).first().map { it.mediaId },
    )
  }

  @Test
  fun availableTracks_excludeTheReservedDemoVolume() = runTest {
    dao.upsertTracks(
      listOf(
        trackEntity(mediaId = "local:one"),
        trackEntity(
          mediaId = "demo:anchor",
          volumeName = DEMO_VOLUME_NAME,
          mediaStoreId = -1,
        ),
      )
    )

    assertEquals(
      listOf("local:one"),
      dao.observeAvailableTracks(excludedVolumeName = DEMO_VOLUME_NAME).first().map {
        it.mediaId
      },
    )
  }

  @Test
  fun upsertTracks_insertsAndUpdatesByMediaId() = runTest {
    val original = trackEntity(mediaId = "local:one", title = "Old title")
    val updated = original.copy(title = "New title", metadataFingerprint = "new-fingerprint")

    dao.upsertTracks(listOf(original))
    dao.upsertTracks(listOf(updated))

    assertEquals(updated, dao.findByMediaId("local:one"))
    assertEquals(listOf(updated), dao.getAll())
  }

  @Test
  fun duplicateVolumeAndMediaStoreId_isRejected() = runTest {
    dao.upsertTracks(
      listOf(
        trackEntity(
          mediaId = "local:first",
          volumeName = "external_primary",
          mediaStoreId = 42L,
        )
      )
    )

    val failure =
      runCatching {
        databaseRule.database.openHelper.writableDatabase.execSQL(
          """
          INSERT INTO tracks (
            mediaId, volumeName, mediaStoreId, contentUri, lastSeenScanToken
          ) VALUES (?, ?, ?, ?, ?)
          """.trimIndent(),
          arrayOf(
            "local:second",
            "external_primary",
            42L,
            "content://media/local:second",
            "scan-1",
          ),
        )
      }.exceptionOrNull()

    assertTrue(failure is SQLiteConstraintException)
    assertEquals(listOf("local:first"), dao.getAll().map { it.mediaId })
  }
}
