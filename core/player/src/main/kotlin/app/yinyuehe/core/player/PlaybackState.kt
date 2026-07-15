package app.yinyuehe.core.player

import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackConnectionError
import app.yinyuehe.core.common.playback.PlaybackError
import app.yinyuehe.core.common.playback.PlaybackRepeatMode

enum class PlaybackConnection { CONNECTING, CONNECTED, DISCONNECTED }

enum class PlaybackToggleAction { PLAY, PAUSE }

internal enum class PlaybackTogglePreparation { NONE, PREPARE, SEEK_TO_DEFAULT_POSITION }

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
  val repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.OFF,
  val playbackError: PlaybackError? = null,
  val connectionError: PlaybackConnectionError? = null,
  val queuePersistenceLimited: Boolean = false,
  val canSeek: Boolean = false,
  val canPrevious: Boolean = false,
  val canNext: Boolean = false,
  val canSetRepeatMode: Boolean = false,
  val canSetShuffle: Boolean = false,
  val canChangeQueue: Boolean = false,
  val canSkipToQueueItem: Boolean = false,
)

internal data class PlaybackToggleDecision(
  val action: PlaybackToggleAction,
  val canDispatch: Boolean,
  val preparation: PlaybackTogglePreparation,
)

internal fun playbackToggleDecision(
  playWhenReady: Boolean,
  hasCurrentMediaItem: Boolean,
  isIdle: Boolean,
  isEnded: Boolean,
  canPlayPause: Boolean,
  canPrepare: Boolean,
  canSeekToDefaultPosition: Boolean,
): PlaybackToggleDecision {
  val preparation =
    when {
      !hasCurrentMediaItem -> PlaybackTogglePreparation.NONE
      isIdle -> PlaybackTogglePreparation.PREPARE
      isEnded -> PlaybackTogglePreparation.SEEK_TO_DEFAULT_POSITION
      else -> PlaybackTogglePreparation.NONE
    }
  val action =
    if (!hasCurrentMediaItem || isIdle || isEnded || !shouldPauseForToggle(playWhenReady)) {
      PlaybackToggleAction.PLAY
    } else {
      PlaybackToggleAction.PAUSE
    }
  val canPrepareForAction =
    when (preparation) {
      PlaybackTogglePreparation.NONE -> true
      PlaybackTogglePreparation.PREPARE -> canPrepare
      PlaybackTogglePreparation.SEEK_TO_DEFAULT_POSITION -> canSeekToDefaultPosition
    }
  return PlaybackToggleDecision(
    action = action,
    canDispatch = hasCurrentMediaItem && canPlayPause && canPrepareForAction,
    preparation = preparation,
  )
}
