package app.yinyuehe.core.player

import app.yinyuehe.core.common.model.Track
import kotlinx.coroutines.flow.StateFlow

interface PlaybackController {
  val state: StateFlow<PlaybackState>
  suspend fun play(tracks: List<Track>, startIndex: Int, shuffle: Boolean = false): Boolean
  fun togglePlayPause()
  fun seekTo(positionMs: Long)
  fun seekToPrevious()
  fun seekToNext()
  fun addToQueue(track: Track)
  fun removeQueueItem(index: Int)
  fun skipToQueueItem(index: Int)
  fun setShuffleEnabled(enabled: Boolean)
}
