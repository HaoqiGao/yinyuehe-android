package app.yinyuehe.core.player

import app.yinyuehe.core.common.model.TrackId

internal data class PlayerSnapshot(
  val connection: PlaybackConnection,
  val currentMediaId: String?,
  val currentIndex: Int,
  val isPlaying: Boolean,
  val positionMs: Long,
  val durationMs: Long,
  val queueMediaIds: List<String>,
  val shuffleEnabled: Boolean,
  val canSeek: Boolean,
  val canPrevious: Boolean,
  val canNext: Boolean,
)

internal fun PlayerSnapshot.toPlaybackState(): PlaybackState {
  val indexedTrackIds =
    queueMediaIds.mapIndexedNotNull { index, mediaId ->
      mediaId.takeIf(String::isNotBlank)?.let { index to TrackId(it) }
    }
  val mappedCurrentIndex = indexedTrackIds.indexOfFirst { (sourceIndex) -> sourceIndex == currentIndex }
  return PlaybackState(
    connection = connection,
    currentTrackId = currentMediaId?.takeIf(String::isNotBlank)?.let(::TrackId),
    currentIndex = mappedCurrentIndex,
    isPlaying = isPlaying,
    positionMs = positionMs.coerceAtLeast(0),
    durationMs = durationMs.coerceAtLeast(0),
    queueTrackIds = indexedTrackIds.map { (_, trackId) -> trackId },
    shuffleEnabled = shuffleEnabled,
    canSeek = canSeek,
    canPrevious = canPrevious,
    canNext = canNext,
  )
}
