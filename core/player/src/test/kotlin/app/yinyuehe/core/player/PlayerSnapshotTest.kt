package app.yinyuehe.core.player

import app.yinyuehe.core.common.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayerSnapshotTest {
  @Test
  fun snapshot_mapsMediaIdsAndClampsUnknownTimes() {
    val state =
      PlayerSnapshot(
          connection = PlaybackConnection.CONNECTED,
          currentMediaId = "demo:city-walk",
          isPlaying = false,
          positionMs = -1,
          durationMs = -1,
          queueMediaIds = listOf("demo:morning-pulse", "demo:city-walk"),
        )
        .toPlaybackState()

    assertEquals(TrackId("demo:city-walk"), state.currentTrackId)
    assertEquals(0L, state.positionMs)
    assertEquals(0L, state.durationMs)
    assertEquals(2, state.queueTrackIds.size)
    assertFalse(state.isPlaying)
  }
}
