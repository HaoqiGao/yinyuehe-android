package app.yinyuehe.core.testing

import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.player.PlaybackController
import app.yinyuehe.core.player.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakePlaybackController : PlaybackController {
  data class PlayRequest(val tracks: List<Track>, val startIndex: Int)

  private val mutableState = MutableStateFlow(PlaybackState())
  override val state: StateFlow<PlaybackState> = mutableState
  val playRequests = mutableListOf<PlayRequest>()
  var toggleCount = 0

  override suspend fun play(tracks: List<Track>, startIndex: Int) {
    playRequests += PlayRequest(tracks, startIndex)
  }

  override fun togglePlayPause() {
    toggleCount += 1
  }
}
