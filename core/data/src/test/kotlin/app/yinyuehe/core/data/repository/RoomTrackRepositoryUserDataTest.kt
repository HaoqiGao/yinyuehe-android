package app.yinyuehe.core.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.data.DemoTrackCatalog
import app.yinyuehe.core.data.local.db.DEMO_VOLUME_NAME
import app.yinyuehe.core.data.local.db.RoomDatabaseRule
import app.yinyuehe.core.data.local.db.entity.FavoriteEntity
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
  fun userDataMutations_returnFalseForUnknownIds() = runTest {
    val missingId = TrackId("local:missing")
    val unknownDemoId = TrackId("demo:not-in-catalog")

    assertFalse(repository.setFavorite(missingId, true))
    assertFalse(repository.setFavorite(unknownDemoId, true))
    assertFalse(repository.recordRecent(missingId, positionMs = null))
    assertFalse(repository.recordRecent(unknownDemoId, positionMs = 10))
    assertTrue(repository.observeFavoriteTrackIds().first().isEmpty())
    assertTrue(repository.observeRecentTracks().first().isEmpty())
    assertTrue(database.trackDao().getAll().isEmpty())
  }

  @Test
  fun allCatalogDemos_supportFavoritesAndRecentHistory() = runTest {
    val demos = repository.demoTracks()

    demos.forEachIndexed { index, demo ->
      assertTrue(repository.setFavorite(demo.id, true))
      assertTrue(repository.recordRecent(demo.id, positionMs = index.toLong()))
    }

    val favorites = repository.observeFavoriteTracks().first()
    val recent = repository.observeRecentTracks().first()
    assertEquals(demos.map { it.id }.toSet(), repository.observeFavoriteTrackIds().first())
    assertEquals(demos.associateBy { it.id }, favorites.associateBy { it.id })
    assertEquals(demos.associateBy { it.id }, recent.associateBy { it.id })
    assertTrue(favorites.all { it.isDemo })
    assertTrue(recent.all { it.isDemo })
  }

  @Test
  fun persistedDemoAnchors_resolveCurrentCatalogAndHideRetiredEntries() = runTest {
    val current = repository.demoTracks().first()
    val retiredId = "demo:retired"
    database.trackDao().upsertTracks(
      listOf(
        trackEntity(
          mediaId = current.id.value,
          volumeName = DEMO_VOLUME_NAME,
          mediaStoreId = -900,
          contentUri = "android.resource://stale/1",
          title = "Stale title",
        ),
        trackEntity(
          mediaId = retiredId,
          volumeName = DEMO_VOLUME_NAME,
          mediaStoreId = -901,
          contentUri = "android.resource://stale/2",
          title = "Retired title",
        ),
      )
    )
    database.favoriteDao().upsert(FavoriteEntity(retiredId, addedAtEpochMs = 1))
    database.favoriteDao().upsert(FavoriteEntity(current.id.value, addedAtEpochMs = 2))
    database.recentPlayDao().recordRecent(retiredId, playedAtEpochMs = 1, positionMs = null)
    database.recentPlayDao().recordRecent(
      current.id.value,
      playedAtEpochMs = 2,
      positionMs = null,
    )

    assertEquals(listOf(current), repository.observeFavoriteTracks().first())
    assertEquals(listOf(current), repository.observeRecentTracks().first())
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

  @Test
  fun recentHistory_withDemoAndLocalTracks_remainsLimitedToTwenty() = runTest {
    val demo = repository.demoTracks().first()
    assertTrue(repository.recordRecent(demo.id, positionMs = null))
    val locals =
      (0 until 20).map { index ->
        trackEntity(mediaId = "local:$index", mediaStoreId = 1_000L + index)
      }
    database.trackDao().upsertTracks(locals)
    locals.forEachIndexed { index, track ->
      database.recentPlayDao().recordRecent(
        trackId = track.mediaId,
        playedAtEpochMs = index.toLong(),
        positionMs = null,
      )
    }
    database.recentPlayDao().recordRecent(
      trackId = demo.id.value,
      playedAtEpochMs = 1_000L,
      positionMs = null,
    )

    val recent = repository.observeRecentTracks().first()

    assertEquals(20, recent.size)
    assertEquals(demo, recent.first())
    assertFalse(TrackId("local:0") in recent.map { it.id })
  }
}
