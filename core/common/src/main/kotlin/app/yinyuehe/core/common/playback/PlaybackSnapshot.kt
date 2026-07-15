package app.yinyuehe.core.common.playback

import app.yinyuehe.core.common.model.TrackId

enum class PlaybackRepeatMode { OFF, ALL, ONE }

data class PlaybackSnapshot(
  val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
  val mediaIds: List<TrackId> = emptyList(),
  val currentIndex: Int = -1,
  val positionMs: Long = 0,
  val shuffleEnabled: Boolean = false,
  val repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.OFF,
) {
  init {
    require(schemaVersion == CURRENT_SCHEMA_VERSION) {
      "Usable playback snapshots must use schema $CURRENT_SCHEMA_VERSION"
    }
    require(positionMs >= 0) { "Playback position must not be negative" }
    require(
      if (mediaIds.isEmpty()) {
        currentIndex == -1
      } else {
        currentIndex in mediaIds.indices
      }
    ) { "Playback current index must match the queue" }
  }

  companion object {
    const val CURRENT_SCHEMA_VERSION: Int = 1

    fun empty(): PlaybackSnapshot = PlaybackSnapshot()
  }
}

sealed interface PlaybackSnapshotReadResult {
  data class Usable(val snapshot: PlaybackSnapshot) : PlaybackSnapshotReadResult

  data class IncompatibleVersion(val version: Int) : PlaybackSnapshotReadResult
}

interface PlaybackSnapshotStore {
  suspend fun read(): PlaybackSnapshotReadResult

  suspend fun write(snapshot: PlaybackSnapshot)
}
