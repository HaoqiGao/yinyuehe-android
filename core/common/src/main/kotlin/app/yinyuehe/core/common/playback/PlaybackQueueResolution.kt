package app.yinyuehe.core.common.playback

import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId

enum class PlaybackQueueBlockReason { PERMISSION_DENIED }

sealed interface PlaybackQueueItemResolution {
  val originalIndex: Int
  val trackId: TrackId

  data class Resolved(
    override val originalIndex: Int,
    override val trackId: TrackId,
    val track: Track,
  ) : PlaybackQueueItemResolution

  data class PermanentlyMissing(
    override val originalIndex: Int,
    override val trackId: TrackId,
  ) : PlaybackQueueItemResolution

  data class TemporarilyBlocked(
    override val originalIndex: Int,
    override val trackId: TrackId,
    val reason: PlaybackQueueBlockReason,
  ) : PlaybackQueueItemResolution
}

data class PlaybackQueueResolution(
  val items: List<PlaybackQueueItemResolution>,
  val temporaryBlockReason: PlaybackQueueBlockReason? = null,
) {
  init {
    require(items.map { it.originalIndex } == items.indices.toList()) {
      "Queue resolution must preserve one ordered result per occurrence"
    }
    require(
      items
        .filterIsInstance<PlaybackQueueItemResolution.Resolved>()
        .all { item -> item.track.id == item.trackId }
    ) { "Resolved track identity must match its occurrence" }
    val blocked = items.filterIsInstance<PlaybackQueueItemResolution.TemporarilyBlocked>()
    require(
      if (temporaryBlockReason == null) {
        blocked.isEmpty()
      } else {
        blocked.isNotEmpty() && blocked.all { item -> item.reason == temporaryBlockReason }
      }
    ) { "Temporary block reason must describe every blocked occurrence" }
  }
}

interface PlaybackQueueResolver {
  suspend fun resolve(mediaIds: List<TrackId>): PlaybackQueueResolution
}
