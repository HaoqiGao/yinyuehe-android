package app.yinyuehe.core.data.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackQueueBlockReason
import app.yinyuehe.core.common.playback.PlaybackQueueItemResolution
import app.yinyuehe.core.data.DemoTrackCatalog
import app.yinyuehe.core.data.local.db.RoomDatabaseRule
import app.yinyuehe.core.data.local.db.trackEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomPlaybackQueueResolverTest {
  @get:Rule val databaseRule = RoomDatabaseRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val demoCatalog
    get() = DemoTrackCatalog(context)

  @Test
  fun mixedQueue_preservesOrderDuplicatesAndPermanentMissingItems() = runTest {
    val localAvailable = TrackId("local:v1:ZXh0ZXJuYWw:1")
    val localUnavailable = TrackId("local:v1:ZXh0ZXJuYWw:2")
    val localMissing = TrackId("local:v1:ZXh0ZXJuYWw:3")
    databaseRule.database.trackDao().upsertTracks(
      listOf(
        trackEntity(
          mediaId = localAvailable.value,
          mediaStoreId = 1,
          isAvailable = true,
        ),
        trackEntity(
          mediaId = localUnavailable.value,
          mediaStoreId = 2,
          isAvailable = false,
        ),
      )
    )
    val demoId = demoCatalog.tracks().first().id
    val requested =
      listOf(
        demoId,
        localAvailable,
        demoId,
        localMissing,
        localAvailable,
        localUnavailable,
      )
    val resolver = resolver(permissionGranted = true)

    val result = resolver.resolve(requested)

    assertNull(result.temporaryBlockReason)
    assertEquals(requested, result.items.map { item -> item.trackId })
    assertEquals(requested.indices.toList(), result.items.map { item -> item.originalIndex })
    assertTrue(result.items[0] is PlaybackQueueItemResolution.Resolved)
    assertTrue(result.items[1] is PlaybackQueueItemResolution.Resolved)
    assertTrue(result.items[2] is PlaybackQueueItemResolution.Resolved)
    assertTrue(result.items[3] is PlaybackQueueItemResolution.PermanentlyMissing)
    assertTrue(result.items[4] is PlaybackQueueItemResolution.Resolved)
    assertTrue(result.items[5] is PlaybackQueueItemResolution.PermanentlyMissing)
  }

  @Test
  fun permissionDenied_resolvesDemoAndBlocksAllLocalsWithoutQueryingRoom() = runTest {
    val cachedLocalId = TrackId("local:v1:ZXh0ZXJuYWw:9")
    val missingLocalId = TrackId("local:v1:ZXh0ZXJuYWw:10")
    databaseRule.database.trackDao().upsertTracks(
      listOf(trackEntity(mediaId = cachedLocalId.value, mediaStoreId = 9, isAvailable = true))
    )
    val demoId = demoCatalog.tracks().first().id
    databaseRule.database.close()

    val result =
      resolver(permissionGranted = false).resolve(listOf(demoId, cachedLocalId, missingLocalId))

    assertEquals(PlaybackQueueBlockReason.PERMISSION_DENIED, result.temporaryBlockReason)
    assertTrue(result.items[0] is PlaybackQueueItemResolution.Resolved)
    result.items.drop(1).forEach { item ->
      val blocked = item as PlaybackQueueItemResolution.TemporarilyBlocked
      assertEquals(PlaybackQueueBlockReason.PERMISSION_DENIED, blocked.reason)
    }
  }

  @Test
  fun pureDemoQueue_doesNotConsultAudioPermission() = runTest {
    val resolver =
      RoomPlaybackQueueResolver(
        database = databaseRule.database,
        demoTrackCatalog = demoCatalog,
        permissionChecker =
          AudioReadPermissionChecker {
            error("Pure Demo restore must not check local audio permission")
          },
      )
    val demoIds = demoCatalog.tracks().take(2).map { track -> track.id }

    val result = resolver.resolve(demoIds)

    assertNull(result.temporaryBlockReason)
    assertTrue(result.items.all { item -> item is PlaybackQueueItemResolution.Resolved })
  }

  @Test
  fun missingDemo_isPermanentlyMissingWithoutConsultingAudioPermission() = runTest {
    val missingDemoId = TrackId("demo:not-in-catalog")
    val resolver =
      RoomPlaybackQueueResolver(
        database = databaseRule.database,
        demoTrackCatalog = demoCatalog,
        permissionChecker =
          AudioReadPermissionChecker {
            error("Missing Demo restore must not check local audio permission")
          },
      )

    val result = resolver.resolve(listOf(missingDemoId))

    assertNull(result.temporaryBlockReason)
    assertTrue(result.items.single() is PlaybackQueueItemResolution.PermanentlyMissing)
  }

  @Test
  fun moreThanOneSqlLimit_isDeduplicatedIntoExactSafeBatches() {
    val ids = (0 until 1_205).map { index -> TrackId("local:v1:dGVzdA:$index") }
    val batches = uniqueLocalIdBatches(ids + ids.first())

    assertEquals(listOf(899, 306), batches.map { batch -> batch.size })
    assertEquals(ids.map { id -> id.value }, batches.flatten())
  }

  @Test
  fun roomFailure_propagatesInsteadOfBecomingAllMissing() = runTest {
    val localId = TrackId("local:v1:ZXh0ZXJuYWw:7")
    val resolver = resolver(permissionGranted = true)
    databaseRule.database.trackDao().getAll()
    databaseRule.database.close()

    val failure = runCatching { resolver.resolve(listOf(localId)) }.exceptionOrNull()

    assertNotNull(failure)
  }

  private fun resolver(permissionGranted: Boolean): RoomPlaybackQueueResolver =
    RoomPlaybackQueueResolver(
      database = databaseRule.database,
      demoTrackCatalog = demoCatalog,
      permissionChecker = AudioReadPermissionChecker { permissionGranted },
    )
}
