package app.yinyuehe.core.common.playback

import app.yinyuehe.core.common.model.TrackId

enum class PlaybackErrorType {
  SOURCE_UNAVAILABLE,
  UNSUPPORTED_FORMAT,
  DECODER,
  UNKNOWN,
}

data class PlaybackError(
  val type: PlaybackErrorType,
  val media3ErrorCode: Int,
  val trackId: TrackId?,
)

enum class PlaybackConnectionError { RETRIES_EXHAUSTED }

sealed interface PlaybackNotice {
  data class TrackSkipped(val error: PlaybackError) : PlaybackNotice
}
