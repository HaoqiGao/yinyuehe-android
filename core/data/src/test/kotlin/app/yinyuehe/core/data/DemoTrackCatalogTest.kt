package app.yinyuehe.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.data.local.db.toDemoEntity
import app.yinyuehe.core.data.repository.RoomTrackRepository
import dagger.Binds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DemoTrackCatalogTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test
  fun tracks_returnsStableOrderedDemoCatalog() {
    val tracks = DemoTrackCatalog(context).tracks()

    assertEquals(
      listOf("demo:morning-pulse", "demo:city-walk", "demo:soft-echo", "demo:night-drive"),
      tracks.map { it.id.value },
    )
    assertEquals(listOf(3_200L, 3_500L, 3_800L, 4_000L), tracks.map { it.durationMs })
    assertTrue(tracks.all { it.isDemo })
    assertTrue(tracks.all { it.sourceUri.startsWith("android.resource://${context.packageName}/") })
  }

  @Test
  fun anchorIds_areStableAcrossCatalogReorderingAndInsertion() {
    val tracks = DemoTrackCatalog(context).tracks()
    val originalIds = tracks.associate { track -> track.id to track.toDemoEntity().mediaStoreId }
    val inserted =
      tracks.first().copy(
        id = TrackId("demo:inserted"),
        sourceUri = "android.resource://${context.packageName}/inserted",
      )
    val reordered = listOf(inserted) + tracks.reversed()
    val reorderedIds = reordered.associate { track -> track.id to track.toDemoEntity().mediaStoreId }

    assertEquals(originalIds, reorderedIds.filterKeys(originalIds::containsKey))
    assertEquals(reordered.size, reorderedIds.values.toSet().size)
  }

  @Test
  fun dataModule_bindsRoomRepositoryAsTrackRepository() {
    val binding =
      DataModule::class.java.getDeclaredMethod(
        "bindTrackRepository",
        RoomTrackRepository::class.java,
      )

    assertTrue(binding.isAnnotationPresent(Binds::class.java))
    assertEquals(TrackRepository::class.java, binding.returnType)
  }
}
