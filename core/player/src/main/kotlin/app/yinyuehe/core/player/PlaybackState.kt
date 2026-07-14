package app.yinyuehe.core.player

import app.yinyuehe.core.common.model.TrackId

enum class PlaybackConnection { CONNECTING, CONNECTED, DISCONNECTED }

enum class PlaybackToggleAction { PLAY, PAUSE }

data class PlaybackState(
  val connection: PlaybackConnection = PlaybackConnection.CONNECTING,
  val currentTrackId: TrackId? = null,
  val currentIndex: Int = -1,
  val isPlaying: Boolean = false,
  val toggleAction: PlaybackToggleAction = PlaybackToggleAction.PLAY,
  val canTogglePlayPause: Boolean = false,
  val positionMs: Long = 0,
  val durationMs: Long = 0,
  val queueTrackIds: List<TrackId> = emptyList(),
  val shuffleEnabled: Boolean = false,
  val canSeek: Boolean = false,
  val canPrevious: Boolean = false,
  val canNext: Boolean = false,
)

internal data class PlaybackToggleDecision(
  val action: PlaybackToggleAction,
  val canDispatch: Boolean,
)

internal fun playbackToggleDecision(
  playWhenReady: Boolean,
  isEnded: Boolean,
  canPlayPause: Boolean,
  canSeekToDefaultPosition: Boolean,
): PlaybackToggleDecision =
  PlaybackToggleDecision(
    action =
      if (isEnded || !shouldPauseForToggle(playWhenReady)) {
        PlaybackToggleAction.PLAY
      } else {
        PlaybackToggleAction.PAUSE
      },
    canDispatch = canPlayPause && (!isEnded || canSeekToDefaultPosition),
  )
