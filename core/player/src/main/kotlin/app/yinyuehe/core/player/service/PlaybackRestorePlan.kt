package app.yinyuehe.core.player.service

import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.playback.PlaybackQueueItemResolution
import app.yinyuehe.core.common.playback.PlaybackQueueResolution
import app.yinyuehe.core.common.playback.PlaybackRepeatMode
import app.yinyuehe.core.common.playback.PlaybackSnapshot

internal data class PlaybackRestorePlan(
  val tracks: List<Track>,
  val currentIndex: Int,
  val positionMs: Long,
  val shuffleEnabled: Boolean,
  val repeatMode: PlaybackRepeatMode,
  val normalizedSnapshot: PlaybackSnapshot,
)

internal fun buildPlaybackRestorePlan(
  snapshot: PlaybackSnapshot,
  resolution: PlaybackQueueResolution,
): PlaybackRestorePlan {
  require(resolution.items.size == snapshot.mediaIds.size) {
    "Queue resolution size must match the playback snapshot"
  }
  require(
    resolution.items.indices.all { index ->
      resolution.items[index].trackId == snapshot.mediaIds[index]
    }
  ) {
    "Queue resolution identities must match every playback snapshot occurrence"
  }
  val resolved = resolution.items.filterIsInstance<PlaybackQueueItemResolution.Resolved>()
  if (resolved.isEmpty()) {
    val empty = PlaybackSnapshot.empty()
    return PlaybackRestorePlan(
      tracks = emptyList(),
      currentIndex = -1,
      positionMs = 0,
      shuffleEnabled = false,
      repeatMode = PlaybackRepeatMode.OFF,
      normalizedSnapshot = empty,
    )
  }

  val originalIndex = snapshot.currentIndex.coerceIn(snapshot.mediaIds.indices)
  val survivingCurrentIndex = resolved.indexOfFirst { item -> item.originalIndex == originalIndex }
  val selectedIndex =
    if (survivingCurrentIndex >= 0) {
      survivingCurrentIndex
    } else {
      val successor = resolved.indexOfFirst { item -> item.originalIndex > originalIndex }
      if (successor >= 0) successor else resolved.indexOfLast { item -> item.originalIndex < originalIndex }
    }.coerceIn(resolved.indices)
  val selected = resolved[selectedIndex]
  val positionMs =
    if (selected.originalIndex != originalIndex) {
      0
    } else {
      selected.track.durationMs
        ?.takeIf { duration -> duration > 0 }
        ?.let { duration -> snapshot.positionMs.coerceIn(0, duration) }
        ?: snapshot.positionMs
    }
  val normalizedSnapshot =
    PlaybackSnapshot(
      mediaIds = resolved.map(PlaybackQueueItemResolution.Resolved::trackId),
      currentIndex = selectedIndex,
      positionMs = positionMs,
      shuffleEnabled = snapshot.shuffleEnabled,
      repeatMode = snapshot.repeatMode,
    )

  return PlaybackRestorePlan(
    tracks = resolved.map(PlaybackQueueItemResolution.Resolved::track),
    currentIndex = selectedIndex,
    positionMs = positionMs,
    shuffleEnabled = snapshot.shuffleEnabled,
    repeatMode = snapshot.repeatMode,
    normalizedSnapshot = normalizedSnapshot,
  )
}
