package app.yinyuehe.core.player

import androidx.media3.common.C
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
          currentIndex = 3,
          isPlaying = false,
          positionMs = 250,
          durationMs = 1_000,
          queueMediaIds = listOf("demo:morning-pulse", "", "  ", "demo:city-walk"),
          shuffleEnabled = true,
          canSeek = true,
          canPrevious = true,
          canNext = false,
        )
        .toPlaybackState()

    assertEquals(PlaybackConnection.DISCONNECTED, state.connection)
    assertEquals(TrackId("demo:city-walk"), state.currentTrackId)
    assertEquals(
      listOf(TrackId("demo:morning-pulse"), TrackId("demo:city-walk")),
      state.queueTrackIds,
    )
    assertEquals(1, state.currentIndex)
    assertEquals(true, state.shuffleEnabled)
    assertEquals(true, state.canSeek)
    assertEquals(true, state.canPrevious)
    assertEquals(false, state.canNext)
    assertFalse(state.isPlaying)
  }

  @Test
  fun snapshot_clampsUnknownTimes() {
    val state =
      PlayerSnapshot(
          connection = PlaybackConnection.CONNECTED,
          currentMediaId = null,
          currentIndex = -1,
          isPlaying = false,
          positionMs = -1,
          durationMs = C.TIME_UNSET,
          queueMediaIds = emptyList(),
          shuffleEnabled = false,
          canSeek = false,
          canPrevious = false,
          canNext = false,
        )
        .toPlaybackState()

    assertEquals(0L, state.positionMs)
    assertEquals(0L, state.durationMs)
  }

  @Test
  fun snapshot_normalizesAProspectiveIndexWhenTheQueueIsEmpty() {
    val state =
      PlayerSnapshot(
          connection = PlaybackConnection.CONNECTED,
          currentMediaId = null,
          currentIndex = 0,
          isPlaying = false,
          positionMs = 0,
          durationMs = 0,
          queueMediaIds = emptyList(),
          shuffleEnabled = false,
          canSeek = false,
          canPrevious = false,
          canNext = false,
        )
        .toPlaybackState()

    assertEquals(C.INDEX_UNSET, state.currentIndex)
  }
}
