package app.yinyuehe.core.player

import androidx.media3.common.C
import androidx.media3.common.Player
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackConnectionError
import app.yinyuehe.core.common.playback.PlaybackError
import app.yinyuehe.core.common.playback.PlaybackErrorType
import app.yinyuehe.core.common.playback.PlaybackRepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSnapshotTest {
  @Test
  fun reconnectingStatePreservesConnectionErrorUntilRealSnapshot() {
    val reconnecting = connectingPlaybackState(exhaustedPlaybackState())

    assertEquals(PlaybackConnection.CONNECTING, reconnecting.connection)
    assertEquals(PlaybackConnectionError.RETRIES_EXHAUSTED, reconnecting.connectionError)
    assertFalse(reconnecting.canTogglePlayPause)
    assertFalse(reconnecting.canChangeQueue)
  }

  @Test
  fun exhaustedConnectionDisablesTransportAndConnectedSnapshotClearsConnectionError() {
    val exhausted = exhaustedPlaybackState()
    assertEquals(PlaybackConnection.DISCONNECTED, exhausted.connection)
    assertEquals(PlaybackConnectionError.RETRIES_EXHAUSTED, exhausted.connectionError)
    assertFalse(exhausted.canTogglePlayPause)
    assertFalse(exhausted.canChangeQueue)

    val connected =
      snapshot(
          playWhenReady = false,
          isEnded = false,
          canPlayPause = true,
          canSeekToDefaultPosition = true,
        )
        .toPlaybackState()
    assertEquals(PlaybackConnection.CONNECTED, connected.connection)
    assertNull(connected.connectionError)
  }

  @Test
  fun media3RepeatModes_mapCompletelyToDomainRepeatModes() {
    assertEquals(
      PlaybackRepeatMode.OFF,
      media3RepeatModeToPlaybackRepeatMode(Player.REPEAT_MODE_OFF),
    )
    assertEquals(
      PlaybackRepeatMode.ALL,
      media3RepeatModeToPlaybackRepeatMode(Player.REPEAT_MODE_ALL),
    )
    assertEquals(
      PlaybackRepeatMode.ONE,
      media3RepeatModeToPlaybackRepeatMode(Player.REPEAT_MODE_ONE),
    )
    assertEquals(PlaybackRepeatMode.OFF, media3RepeatModeToPlaybackRepeatMode(Int.MIN_VALUE))
  }

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
  fun emptyQueue_mapsToDisabledPlayActionEvenWhenPlayPauseIsAvailable() {
    val state =
      snapshot(
          playWhenReady = false,
          isEnded = false,
          canPlayPause = true,
          canSeekToDefaultPosition = true,
          hasCurrentMediaItem = false,
        )
        .toPlaybackState()

    assertEquals(PlaybackToggleAction.PLAY, state.toggleAction)
    assertFalse(state.canTogglePlayPause)
  }

  @Test
  fun idleCurrentItem_mapsToEnabledPlayActionWhenPrepareIsAvailable() {
    val state =
      snapshot(
          playWhenReady = true,
          isEnded = false,
          canPlayPause = true,
          canSeekToDefaultPosition = false,
          isIdle = true,
          canPrepare = true,
        )
        .toPlaybackState()

    assertEquals(PlaybackToggleAction.PLAY, state.toggleAction)
    assertTrue(state.canTogglePlayPause)
  }

  @Test
  fun idleCurrentItem_disablesPlayActionWhenPrepareIsUnavailable() {
    val state =
      snapshot(
          playWhenReady = true,
          isEnded = false,
          canPlayPause = true,
          canSeekToDefaultPosition = true,
          isIdle = true,
          canPrepare = false,
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
          hasCurrentMediaItem = true,
          isIdle = false,
          isEnded = false,
          positionMs = 250,
          durationMs = 1_000,
          queueMediaIds = listOf("demo:morning-pulse", "", "  ", "demo:city-walk"),
          shuffleEnabled = true,
          repeatMode = PlaybackRepeatMode.OFF,
          queuePersistenceLimited = false,
          canPlayPause = false,
          canPrepare = false,
          canSeekToDefaultPosition = false,
          canSeek = true,
          canPrevious = true,
          canNext = false,
          canSetRepeatMode = false,
          canSetShuffle = false,
          canChangeQueue = false,
          canSkipToQueueItem = false,
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
          hasCurrentMediaItem = false,
          isIdle = false,
          isEnded = false,
          positionMs = -1,
          durationMs = C.TIME_UNSET,
          queueMediaIds = emptyList(),
          shuffleEnabled = false,
          repeatMode = PlaybackRepeatMode.OFF,
          queuePersistenceLimited = false,
          canPlayPause = false,
          canPrepare = false,
          canSeekToDefaultPosition = false,
          canSeek = false,
          canPrevious = false,
          canNext = false,
          canSetRepeatMode = false,
          canSetShuffle = false,
          canChangeQueue = false,
          canSkipToQueueItem = false,
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
          hasCurrentMediaItem = false,
          isIdle = false,
          isEnded = false,
          positionMs = 0,
          durationMs = 0,
          queueMediaIds = emptyList(),
          shuffleEnabled = false,
          repeatMode = PlaybackRepeatMode.OFF,
          queuePersistenceLimited = false,
          canPlayPause = false,
          canPrepare = false,
          canSeekToDefaultPosition = false,
          canSeek = false,
          canPrevious = false,
          canNext = false,
          canSetRepeatMode = false,
          canSetShuffle = false,
          canChangeQueue = false,
          canSkipToQueueItem = false,
        )
        .toPlaybackState()

    assertEquals(C.INDEX_UNSET, state.currentIndex)
  }

  @Test
  fun snapshot_mapsRepeatPersistenceLimitAndExactCommandCapabilities() {
    val state =
      PlayerSnapshot(
          connection = PlaybackConnection.CONNECTED,
          currentMediaId = "demo:one",
          currentIndex = 0,
          isPlaying = false,
          playWhenReady = false,
          hasCurrentMediaItem = true,
          isIdle = false,
          isEnded = false,
          positionMs = 0,
          durationMs = 1_000,
          queueMediaIds = listOf("demo:one"),
          shuffleEnabled = true,
          repeatMode = PlaybackRepeatMode.ONE,
          queuePersistenceLimited = true,
          canPlayPause = true,
          canPrepare = true,
          canSeekToDefaultPosition = true,
          canSeek = true,
          canPrevious = false,
          canNext = false,
          canSetRepeatMode = true,
          canSetShuffle = false,
          canChangeQueue = true,
          canSkipToQueueItem = true,
        )
        .toPlaybackState()

    assertEquals(PlaybackRepeatMode.ONE, state.repeatMode)
    assertTrue(state.queuePersistenceLimited)
    assertTrue(state.canSetRepeatMode)
    assertFalse(state.canSetShuffle)
    assertFalse(state.canChangeQueue)
    assertTrue(state.canSkipToQueueItem)
    assertNull(state.playbackError)
    assertNull(state.connectionError)
  }

  @Test
  fun snapshotMapsOnlyTheDecisionOwnedTerminalError() {
    val terminalError =
      PlaybackError(PlaybackErrorType.SOURCE_UNAVAILABLE, 2005, TrackId("local:one"))

    assertNull(
      snapshot(
          playWhenReady = false,
          isEnded = false,
          canPlayPause = true,
          canSeekToDefaultPosition = true,
          terminalPlaybackError = null,
        )
        .toPlaybackState()
        .playbackError
    )
    assertEquals(
      terminalError,
      snapshot(
          playWhenReady = false,
          isEnded = false,
          canPlayPause = true,
          canSeekToDefaultPosition = true,
          terminalPlaybackError = terminalError,
        )
        .toPlaybackState()
        .playbackError,
    )
  }
}

private fun snapshot(
  playWhenReady: Boolean,
  isEnded: Boolean,
  canPlayPause: Boolean,
  canSeekToDefaultPosition: Boolean,
  hasCurrentMediaItem: Boolean = true,
  isIdle: Boolean = false,
  canPrepare: Boolean = true,
  terminalPlaybackError: PlaybackError? = null,
) =
  PlayerSnapshot(
    connection = PlaybackConnection.CONNECTED,
    currentMediaId = "local:one".takeIf { hasCurrentMediaItem },
    currentIndex = if (hasCurrentMediaItem) 0 else C.INDEX_UNSET,
    isPlaying = false,
    playWhenReady = playWhenReady,
    hasCurrentMediaItem = hasCurrentMediaItem,
    isIdle = isIdle,
    isEnded = isEnded,
    positionMs = 0,
    durationMs = 1_000,
    queueMediaIds = if (hasCurrentMediaItem) listOf("local:one") else emptyList(),
    shuffleEnabled = false,
    repeatMode = PlaybackRepeatMode.OFF,
    queuePersistenceLimited = false,
    canPlayPause = canPlayPause,
    canPrepare = canPrepare,
    canSeekToDefaultPosition = canSeekToDefaultPosition,
    canSeek = true,
    canPrevious = false,
    canNext = false,
    canSetRepeatMode = false,
    canSetShuffle = false,
    canChangeQueue = false,
    canSkipToQueueItem = false,
    terminalPlaybackError = terminalPlaybackError,
  )
