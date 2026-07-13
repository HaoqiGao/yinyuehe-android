package app.yinyuehe.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
}
