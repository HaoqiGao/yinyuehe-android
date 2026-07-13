package app.yinyuehe.core.player

import app.yinyuehe.core.common.model.TrackId

internal data class PlayerSnapshot(
  val connection: PlaybackConnection,
  val currentMediaId: String?,
  val isPlaying: Boolean,
  val positionMs: Long,
  val durationMs: Long,
  val queueMediaIds: List<String>,
)

internal fun PlayerSnapshot.toPlaybackState(): PlaybackState =
  PlaybackState(
    connection = connection,
    currentTrackId = currentMediaId?.takeIf(String::isNotBlank)?.let(::TrackId),
    isPlaying = isPlaying,
    positionMs = positionMs.coerceAtLeast(0),
    durationMs = durationMs.coerceAtLeast(0),
    queueTrackIds = queueMediaIds.filter(String::isNotBlank).map(::TrackId),
  )
