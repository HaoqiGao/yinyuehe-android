package app.yinyuehe.core.data.local.db

import android.database.sqlite.SQLiteConstraintException
import app.yinyuehe.core.data.local.db.entity.FavoriteEntity
import app.yinyuehe.core.data.local.db.entity.RecentPlayEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RelationDaoTest {
  @get:Rule
  val databaseRule = RoomDatabaseRule()

  private val trackDao
    get() = databaseRule.database.trackDao()

  private val favoriteDao
    get() = databaseRule.database.favoriteDao()

  private val recentPlayDao
    get() = databaseRule.database.recentPlayDao()

  @Test
  fun relations_areUpsertedAndRead() = runTest {
    trackDao.upsertTracks(listOf(trackEntity(mediaId = "local:one")))
    favoriteDao.upsert(FavoriteEntity("local:one", 10L))
    recentPlayDao.upsert(RecentPlayEntity("local:one", 20L, 2L, null))

    assertEquals(FavoriteEntity("local:one", 10L), favoriteDao.find("local:one"))
    assertEquals(
      RecentPlayEntity("local:one", 20L, 2L, null),
      recentPlayDao.find("local:one"),
    )
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

  @Test
  fun recentPlayForMissingTrack_isRejected() = runTest {
    val failure =
      runCatching {
        recentPlayDao.upsert(RecentPlayEntity("demo:not-persisted", 20L, 2L, null))
      }.exceptionOrNull()

    assertTrue(failure is SQLiteConstraintException)
  }
}
