package app.yinyuehe.core.common.playback

import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import java.util.Collections

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

class PlaybackQueueResolution(
  items: List<PlaybackQueueItemResolution>,
  val temporaryBlockReason: PlaybackQueueBlockReason? = null,
) {
  val items: List<PlaybackQueueItemResolution> = items.toImmutableSnapshot()

  init {
    require(this.items.map { it.originalIndex } == this.items.indices.toList()) {
      "Queue resolution must preserve one ordered result per occurrence"
    }
    require(
      this.items
        .filterIsInstance<PlaybackQueueItemResolution.Resolved>()
        .all { item -> item.track.id == item.trackId }
    ) { "Resolved track identity must match its occurrence" }
    val blocked = this.items.filterIsInstance<PlaybackQueueItemResolution.TemporarilyBlocked>()
    require(
      if (temporaryBlockReason == null) {
        blocked.isEmpty()
      } else {
        blocked.isNotEmpty() && blocked.all { item -> item.reason == temporaryBlockReason }
      }
    ) { "Temporary block reason must describe every blocked occurrence" }
  }

  operator fun component1(): List<PlaybackQueueItemResolution> = items

  operator fun component2(): PlaybackQueueBlockReason? = temporaryBlockReason

  fun copy(
    items: List<PlaybackQueueItemResolution> = this.items,
    temporaryBlockReason: PlaybackQueueBlockReason? = this.temporaryBlockReason,
  ): PlaybackQueueResolution =
    PlaybackQueueResolution(
      items = items,
      temporaryBlockReason = temporaryBlockReason,
    )

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is PlaybackQueueResolution) return false

    return items == other.items && temporaryBlockReason == other.temporaryBlockReason
  }

  override fun hashCode(): Int =
    31 * items.hashCode() + (temporaryBlockReason?.hashCode() ?: 0)

  override fun toString(): String =
    "PlaybackQueueResolution(" +
      "items=$items, " +
      "temporaryBlockReason=$temporaryBlockReason)"
}

private fun <T> List<T>.toImmutableSnapshot(): List<T> =
  Collections.unmodifiableList(ArrayList(this))

interface PlaybackQueueResolver {
  suspend fun resolve(mediaIds: List<TrackId>): PlaybackQueueResolution
}
