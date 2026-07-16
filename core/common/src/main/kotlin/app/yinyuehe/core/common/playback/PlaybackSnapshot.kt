package app.yinyuehe.core.common.playback

import app.yinyuehe.core.common.model.TrackId
import java.util.Collections

enum class PlaybackRepeatMode { OFF, ALL, ONE }

class PlaybackSnapshot(
  val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
  mediaIds: List<TrackId> = emptyList(),
  val currentIndex: Int = -1,
  val positionMs: Long = 0,
  val shuffleEnabled: Boolean = false,
  val repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.OFF,
) {
  val mediaIds: List<TrackId> = mediaIds.toImmutableSnapshot()

  init {
    require(schemaVersion == CURRENT_SCHEMA_VERSION) {
      "Usable playback snapshots must use schema $CURRENT_SCHEMA_VERSION"
    }
    require(positionMs >= 0) { "Playback position must not be negative" }
    require(
      if (this.mediaIds.isEmpty()) {
        currentIndex == -1
      } else {
        currentIndex in this.mediaIds.indices
      }
    ) { "Playback current index must match the queue" }
  }

  operator fun component1(): Int = schemaVersion

  operator fun component2(): List<TrackId> = mediaIds

  operator fun component3(): Int = currentIndex

  operator fun component4(): Long = positionMs

  operator fun component5(): Boolean = shuffleEnabled

  operator fun component6(): PlaybackRepeatMode = repeatMode

  fun copy(
    schemaVersion: Int = this.schemaVersion,
    mediaIds: List<TrackId> = this.mediaIds,
    currentIndex: Int = this.currentIndex,
    positionMs: Long = this.positionMs,
    shuffleEnabled: Boolean = this.shuffleEnabled,
    repeatMode: PlaybackRepeatMode = this.repeatMode,
  ): PlaybackSnapshot =
    PlaybackSnapshot(
      schemaVersion = schemaVersion,
      mediaIds = mediaIds,
      currentIndex = currentIndex,
      positionMs = positionMs,
      shuffleEnabled = shuffleEnabled,
      repeatMode = repeatMode,
    )

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is PlaybackSnapshot) return false

    return schemaVersion == other.schemaVersion &&
      mediaIds == other.mediaIds &&
      currentIndex == other.currentIndex &&
      positionMs == other.positionMs &&
      shuffleEnabled == other.shuffleEnabled &&
      repeatMode == other.repeatMode
  }

  override fun hashCode(): Int {
    var result = schemaVersion
    result = 31 * result + mediaIds.hashCode()
    result = 31 * result + currentIndex
    result = 31 * result + positionMs.hashCode()
    result = 31 * result + shuffleEnabled.hashCode()
    result = 31 * result + repeatMode.hashCode()
    return result
  }

  override fun toString(): String =
    "PlaybackSnapshot(" +
      "schemaVersion=$schemaVersion, " +
      "mediaIds=$mediaIds, " +
      "currentIndex=$currentIndex, " +
      "positionMs=$positionMs, " +
      "shuffleEnabled=$shuffleEnabled, " +
      "repeatMode=$repeatMode)"

  companion object {
    const val CURRENT_SCHEMA_VERSION: Int = 1

    fun empty(): PlaybackSnapshot = PlaybackSnapshot()
  }
}

private fun <T> List<T>.toImmutableSnapshot(): List<T> =
  Collections.unmodifiableList(ArrayList(this))

sealed interface PlaybackSnapshotReadResult {
  data class Usable(val snapshot: PlaybackSnapshot) : PlaybackSnapshotReadResult

  data class IncompatibleVersion(val version: Int) : PlaybackSnapshotReadResult
}

interface PlaybackSnapshotStore {
  suspend fun read(): PlaybackSnapshotReadResult

  suspend fun write(snapshot: PlaybackSnapshot)
}
