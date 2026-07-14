package app.yinyuehe.core.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.yinyuehe.core.common.model.LibrarySource
import app.yinyuehe.core.data.DemoTrackCatalog
import app.yinyuehe.core.data.local.db.RoomDatabaseRule
import app.yinyuehe.core.data.local.db.trackEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomTrackRepositoryTest {
  @get:Rule val databaseRule = RoomDatabaseRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()

  private val trackDao
    get() = databaseRule.database.trackDao()

  private val demoCatalog
    get() = DemoTrackCatalog(context)

  private val repository
    get() =
      RoomTrackRepository(
        trackDao = trackDao,
        favoriteDao = databaseRule.database.favoriteDao(),
        recentPlayDao = databaseRule.database.recentPlayDao(),
        demoCatalog = demoCatalog,
      )

  @Test
  fun emptyRoom_emitsOnlyDemoCatalog() = runTest {
    val content = repository.observeLibrary().first()

    assertEquals(LibrarySource.DEMO, content.source)
    assertEquals(demoCatalog.tracks(), content.tracks)
    assertTrue(content.tracks.all { it.isDemo })
    assertTrue(trackDao.getAll().isEmpty())
  }

  @Test
  fun availableLocalTrack_replacesEntireDemoCatalog() = runTest {
    val local = trackEntity(mediaId = "local:one", title = null, displayName = "One.flac")
    trackDao.upsertTracks(listOf(local))

    val content = repository.observeLibrary().first { it.source == LibrarySource.LOCAL }

    assertEquals(listOf("local:one"), content.tracks.map { it.id.value })
    assertTrue(content.tracks.none { it.isDemo })
    assertEquals(listOf(local), trackDao.getAll())
  }

  @Test
  fun unavailableRows_doNotSuppressDemoFallback() = runTest {
    val unavailable = trackEntity(mediaId = "local:hidden", isAvailable = false)
    trackDao.upsertTracks(listOf(unavailable))

    val content = repository.observeLibrary().first()

    assertEquals(LibrarySource.DEMO, content.source)
    assertEquals(demoCatalog.tracks(), content.tracks)
    assertTrue(content.tracks.all { it.isDemo })
    assertEquals(listOf(unavailable), trackDao.getAll())
  }
}
