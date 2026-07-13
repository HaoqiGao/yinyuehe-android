package app.yinyuehe.core.player

import app.yinyuehe.core.common.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayerSnapshotTest {
  @Test
  fun snapshot_mapsMediaIdsFiltersBlanksAndPreservesConnection() {
    val state =
      PlayerSnapshot(
          connection = PlaybackConnection.DISCONNECTED,
          currentMediaId = "demo:city-walk",
          isPlaying = false,
          positionMs = 250,
          durationMs = 1_000,
          queueMediaIds = listOf("demo:morning-pulse", "", "  ", "demo:city-walk"),
        )
        .toPlaybackState()

    assertEquals(PlaybackConnection.DISCONNECTED, state.connection)
    assertEquals(TrackId("demo:city-walk"), state.currentTrackId)
    assertEquals(
      listOf(TrackId("demo:morning-pulse"), TrackId("demo:city-walk")),
      state.queueTrackIds,
    )
    assertFalse(state.isPlaying)
  }

  @Test
  fun snapshot_clampsUnknownTimes() {
    val state =
      PlayerSnapshot(
          connection = PlaybackConnection.CONNECTED,
          currentMediaId = null,
          isPlaying = false,
          positionMs = -1,
          durationMs = -1,
          queueMediaIds = emptyList(),
        )
        .toPlaybackState()

    assertEquals(0L, state.positionMs)
    assertEquals(0L, state.durationMs)
  }
}
