package app.yinyuehe.core.player

import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.playback.PlaybackNotice
import app.yinyuehe.core.common.playback.PlaybackRepeatMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface PlaybackController {
  val state: StateFlow<PlaybackState>
  val notices: Flow<PlaybackNotice>
  suspend fun play(tracks: List<Track>, startIndex: Int, shuffle: Boolean = false): Boolean
  fun togglePlayPause()
  fun seekTo(positionMs: Long)
  fun seekToPrevious()
  fun seekToNext()
  fun addToQueue(track: Track)
  fun removeQueueItem(index: Int)
  fun skipToQueueItem(index: Int)
  fun setShuffleEnabled(enabled: Boolean)
  fun setRepeatMode(mode: PlaybackRepeatMode)
  fun moveQueueItem(fromIndex: Int, toIndex: Int)
}
