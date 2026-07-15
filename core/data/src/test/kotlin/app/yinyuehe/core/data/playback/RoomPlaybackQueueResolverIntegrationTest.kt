package app.yinyuehe.core.data.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackQueueItemResolution
import app.yinyuehe.core.data.DemoTrackCatalog
import app.yinyuehe.core.data.local.db.DEMO_VOLUME_NAME
import app.yinyuehe.core.data.local.db.RoomDatabaseRule
import app.yinyuehe.core.data.local.db.trackEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomPlaybackQueueResolverIntegrationTest {
  @get:Rule val databaseRule = RoomDatabaseRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test
  fun availableIdLookup_excludesUnavailableAndDemoVolumeRows() = runTest {
    val availableId = TrackId("local:v1:ZXh0ZXJuYWw:1")
    val unavailableId = TrackId("local:v1:ZXh0ZXJuYWw:2")
    val demoRowId = TrackId("local:v1:ZXh0ZXJuYWw:3")
    databaseRule.database.trackDao().upsertTracks(
      listOf(
        trackEntity(mediaId = availableId.value, mediaStoreId = 1),
        trackEntity(mediaId = unavailableId.value, mediaStoreId = 2, isAvailable = false),
        trackEntity(
          mediaId = demoRowId.value,
          volumeName = DEMO_VOLUME_NAME,
          mediaStoreId = 3,
        ),
      )
    )

    val found =
      databaseRule.database
        .trackDao()
        .findAvailableByMediaIds(
          mediaIds = listOf(availableId.value, unavailableId.value, demoRowId.value),
          excludedVolumeName = DEMO_VOLUME_NAME,
        )

    assertEquals(listOf(availableId.value), found.map { entity -> entity.mediaId })
  }

  @Test
  fun realRoomQuery_rebuildsAtLeast1205UniqueIdsAndADuplicateInOriginalOrder() = runTest {
    val uniqueIds = (0 until 1_205).map { index -> TrackId("local:v1:dGVzdA:$index") }
    databaseRule.database.trackDao().upsertTracks(
      uniqueIds.mapIndexed { index, id ->
        trackEntity(
          mediaId = id.value,
          mediaStoreId = index.toLong(),
          isAvailable = true,
        )
      }
    )
    val requested = uniqueIds + uniqueIds[400]
    val resolver =
      RoomPlaybackQueueResolver(
        database = databaseRule.database,
        demoTrackCatalog = DemoTrackCatalog(context),
        permissionChecker = AudioReadPermissionChecker { true },
      )

    val result = resolver.resolve(requested)

    assertEquals(requested, result.items.map { item -> item.trackId })
    assertEquals(requested.indices.toList(), result.items.map { item -> item.originalIndex })
    assertTrue(result.items.all { item -> item is PlaybackQueueItemResolution.Resolved })
  }
}
