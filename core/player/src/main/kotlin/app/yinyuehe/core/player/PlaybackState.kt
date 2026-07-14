package app.yinyuehe.core.player

import app.yinyuehe.core.common.model.TrackId

enum class PlaybackConnection { CONNECTING, CONNECTED, DISCONNECTED }

data class PlaybackState(
  val connection: PlaybackConnection = PlaybackConnection.CONNECTING,
  val currentTrackId: TrackId? = null,
  val currentIndex: Int = -1,
  val isPlaying: Boolean = false,
  val positionMs: Long = 0,
  val durationMs: Long = 0,
  val queueTrackIds: List<TrackId> = emptyList(),
  val shuffleEnabled: Boolean = false,
  val canSeek: Boolean = false,
  val canPrevious: Boolean = false,
  val canNext: Boolean = false,
)
