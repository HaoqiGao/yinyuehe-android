package app.yinyuehe.core.player

import app.yinyuehe.core.common.model.Track
import kotlinx.coroutines.flow.StateFlow

interface PlaybackController {
  val state: StateFlow<PlaybackState>
  suspend fun play(tracks: List<Track>, startIndex: Int): Boolean
  fun togglePlayPause()
}
