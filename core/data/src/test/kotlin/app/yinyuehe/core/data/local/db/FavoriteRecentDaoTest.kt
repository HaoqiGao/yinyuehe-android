package app.yinyuehe.core.data.local.db

import app.yinyuehe.core.data.local.db.entity.FavoriteEntity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FavoriteRecentDaoTest {
  @get:Rule val databaseRule = RoomDatabaseRule()

  private val trackDao
    get() = databaseRule.database.trackDao()

  private val favoriteDao
    get() = databaseRule.database.favoriteDao()

  private val recentDao
    get() = databaseRule.database.recentPlayDao()

  @Test
  fun favorites_emitNewestFirstAndRemovalIsObserved() = runTest {
    trackDao.upsertTracks(
      listOf(trackEntity(mediaId = "local:old"), trackEntity(mediaId = "local:new"))
    )
    favoriteDao.upsert(FavoriteEntity("local:old", 10))
    favoriteDao.upsert(FavoriteEntity("local:new", 20))

    assertEquals(
      listOf("local:new", "local:old"),
      favoriteDao.observeFavoriteTracks().first().map { it.mediaId },
    )
    assertEquals(setOf("local:new", "local:old"), favoriteDao.observeTrackIds().first().toSet())

    favoriteDao.delete("local:new")

    assertEquals(listOf("local:old"), favoriteDao.observeFavoriteTracks().first().map { it.mediaId })
    assertNull(favoriteDao.find("local:new"))
  }

  @Test
  fun favorites_activeCollectorReceivesAddAndRemoveInvalidations() = runTest {
    trackDao.upsertTracks(listOf(trackEntity(mediaId = "local:one")))
    val emissions = Channel<List<String>>(Channel.UNLIMITED)
    val collector =
      backgroundScope.launch {
        favoriteDao.observeFavoriteTracks().collect { tracks ->
          emissions.send(tracks.map { it.mediaId })
        }
      }

    assertEquals(emptyList<String>(), emissions.receive())
    favoriteDao.upsert(FavoriteEntity("local:one", 10))
    assertEquals(listOf("local:one"), emissions.receive())
    favoriteDao.delete("local:one")
    assertEquals(emptyList<String>(), emissions.receive())

    collector.cancel()
  }

  @Test
  fun recentPlay_aggregatesOneRowPerTrackAndStoresLatestPosition() = runTest {
    trackDao.upsertTracks(listOf(trackEntity(mediaId = "local:one")))

    recentDao.recordRecent("local:one", playedAtEpochMs = 10, positionMs = 100)
    recentDao.recordRecent("local:one", playedAtEpochMs = 20, positionMs = 200)

    val stored = recentDao.find("local:one")!!
    assertEquals(1, recentDao.observeRecentTracks().first().size)
    assertEquals(2, stored.playCount)
    assertEquals(20, stored.lastPlayedAtEpochMs)
    assertEquals(200L, stored.lastPositionMs)
  }

  @Test
  fun recentTracks_areNewestFirstAndHardLimitedToTwenty() = runTest {
    val tracks = (0 until 21).map { index -> trackEntity(mediaId = "local:$index") }
    trackDao.upsertTracks(tracks)
    tracks.forEachIndexed { index, track ->
      recentDao.recordRecent(track.mediaId, playedAtEpochMs = index.toLong(), positionMs = null)
    }

    val recent = recentDao.observeRecentTracks().first()

    assertEquals(20, recent.size)
    assertEquals("local:20", recent.first().mediaId)
    assertEquals("local:1", recent.last().mediaId)
  }
}
