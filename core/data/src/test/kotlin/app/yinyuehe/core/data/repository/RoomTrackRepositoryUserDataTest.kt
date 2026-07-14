package app.yinyuehe.core.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.data.DemoTrackCatalog
import app.yinyuehe.core.data.local.db.RoomDatabaseRule
import app.yinyuehe.core.data.local.db.trackEntity
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
class RoomTrackRepositoryUserDataTest {
  @get:Rule val databaseRule = RoomDatabaseRule()

  private val database
    get() = databaseRule.database

  private val context = ApplicationProvider.getApplicationContext<Context>()

  private val repository
    get() =
      RoomTrackRepository(
        trackDao = database.trackDao(),
        favoriteDao = database.favoriteDao(),
        recentPlayDao = database.recentPlayDao(),
        demoCatalog = DemoTrackCatalog(context),
      )

  @Test
  fun setFavorite_returnsFalseForMissingOrDemoOnlyIds() = runTest {
    val missingId = TrackId("local:missing")
    val demoId = repository.demoTracks().first().id

    assertFalse(repository.setFavorite(missingId, true))
    assertFalse(repository.setFavorite(demoId, true))
    assertTrue(repository.observeFavoriteTrackIds().first().isEmpty())
  }

  @Test
  fun favoriteMutations_areExposedAsIdsAndDomainTracks() = runTest {
    val existingId = TrackId("local:one")
    database.trackDao().upsertTracks(listOf(trackEntity(mediaId = existingId.value)))

    assertTrue(repository.setFavorite(existingId, true))
    assertTrue(repository.observeFavoriteTrackIds().first().contains(existingId))
    assertEquals(listOf(existingId), repository.observeFavoriteTracks().first().map { it.id })

    assertTrue(repository.setFavorite(existingId, false))
    assertTrue(repository.observeFavoriteTracks().first().isEmpty())
  }

  @Test
  fun recordRecent_returnsFalseWhenNoRoomTrackExists() = runTest {
    assertFalse(repository.recordRecent(TrackId("demo:not-persisted"), positionMs = 10))
    assertTrue(repository.observeRecentTracks().first().isEmpty())
  }

  @Test
  fun recordRecent_incrementsOneAggregateAndClampsNegativePosition() = runTest {
    val existingId = TrackId("local:one")
    database.trackDao().upsertTracks(listOf(trackEntity(mediaId = existingId.value)))

    assertTrue(repository.recordRecent(existingId, positionMs = 50))
    assertTrue(repository.recordRecent(existingId, positionMs = -1))

    val stored = database.recentPlayDao().find(existingId.value)!!
    assertEquals(2, stored.playCount)
    assertEquals(0L, stored.lastPositionMs)
    assertEquals(listOf(existingId), repository.observeRecentTracks().first().map { it.id })
  }
}
