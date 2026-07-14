package app.yinyuehe.core.player

import androidx.media3.common.C
import app.yinyuehe.core.common.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSnapshotTest {
  @Test
  fun bufferingPlaybackRequest_mapsToEnabledPauseAction() {
    val state =
      snapshot(
          playWhenReady = true,
          isEnded = false,
          canPlayPause = true,
          canSeekToDefaultPosition = false,
        )
        .toPlaybackState()

    assertEquals(PlaybackToggleAction.PAUSE, state.toggleAction)
    assertTrue(state.canTogglePlayPause)
  }

  @Test
  fun toggleAction_isDisabledWhenPlayPauseCommandIsUnavailable() {
    val state =
      snapshot(
          playWhenReady = true,
          isEnded = false,
          canPlayPause = false,
          canSeekToDefaultPosition = true,
        )
        .toPlaybackState()

    assertEquals(PlaybackToggleAction.PAUSE, state.toggleAction)
    assertFalse(state.canTogglePlayPause)
  }

  @Test
  fun endedPlayback_mapsToEnabledPlayActionWhenRestartCommandsAreAvailable() {
    val state =
      snapshot(
          playWhenReady = true,
          isEnded = true,
          canPlayPause = true,
          canSeekToDefaultPosition = true,
        )
        .toPlaybackState()

    assertEquals(PlaybackToggleAction.PLAY, state.toggleAction)
    assertTrue(state.canTogglePlayPause)
  }

  @Test
  fun endedPlayback_disablesPlayActionWhenRestartSeekIsUnavailable() {
    val state =
      snapshot(
          playWhenReady = true,
          isEnded = true,
          canPlayPause = true,
          canSeekToDefaultPosition = false,
        )
        .toPlaybackState()

    assertEquals(PlaybackToggleAction.PLAY, state.toggleAction)
    assertFalse(state.canTogglePlayPause)
  }

  @Test
  fun snapshot_mapsMediaIdsFiltersBlanksAndPreservesConnection() {
    val state =
      PlayerSnapshot(
          connection = PlaybackConnection.DISCONNECTED,
          currentMediaId = "demo:city-walk",
          currentIndex = 3,
          isPlaying = false,
          playWhenReady = false,
          isEnded = false,
          positionMs = 250,
          durationMs = 1_000,
          queueMediaIds = listOf("demo:morning-pulse", "", "  ", "demo:city-walk"),
          shuffleEnabled = true,
          canPlayPause = false,
          canSeekToDefaultPosition = false,
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
          playWhenReady = false,
          isEnded = false,
          positionMs = -1,
          durationMs = C.TIME_UNSET,
          queueMediaIds = emptyList(),
          shuffleEnabled = false,
          canPlayPause = false,
          canSeekToDefaultPosition = false,
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
          playWhenReady = false,
          isEnded = false,
          positionMs = 0,
          durationMs = 0,
          queueMediaIds = emptyList(),
          shuffleEnabled = false,
          canPlayPause = false,
          canSeekToDefaultPosition = false,
          canSeek = false,
          canPrevious = false,
          canNext = false,
        )
        .toPlaybackState()

    assertEquals(C.INDEX_UNSET, state.currentIndex)
  }
}

private fun snapshot(
  playWhenReady: Boolean,
  isEnded: Boolean,
  canPlayPause: Boolean,
  canSeekToDefaultPosition: Boolean,
) =
  PlayerSnapshot(
    connection = PlaybackConnection.CONNECTED,
    currentMediaId = "local:one",
    currentIndex = 0,
    isPlaying = false,
    playWhenReady = playWhenReady,
    isEnded = isEnded,
    positionMs = 0,
    durationMs = 1_000,
    queueMediaIds = listOf("local:one"),
    shuffleEnabled = false,
    canPlayPause = canPlayPause,
    canSeekToDefaultPosition = canSeekToDefaultPosition,
    canSeek = true,
    canPrevious = false,
    canNext = false,
  )
