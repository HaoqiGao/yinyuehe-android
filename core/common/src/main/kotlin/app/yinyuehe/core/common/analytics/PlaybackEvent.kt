package app.yinyuehe.core.common.analytics

import app.yinyuehe.core.common.model.TrackId

enum class PlaybackEventName {
  PLAY_REQUESTED,
  PLAYBACK_STARTED,
  TRACK_CHANGED,
  PLAYBACK_COMPLETED,
  FAVORITE_CHANGED,
  FIRST_FRAME,
  PLAY_START_LATENCY,
}

data class PlaybackEvent(
  val name: PlaybackEventName,
  val occurredAtEpochMs: Long,
  val trackId: TrackId? = null,
  val durationMs: Long? = null,
)
