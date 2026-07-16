package app.yinyuehe.feature.library

import app.yinyuehe.core.common.playback.PlaybackErrorType

sealed interface MusicBoxEffect {
  data class TrackSkipped(val errorType: PlaybackErrorType) : MusicBoxEffect
}
