package app.yinyuehe.core.testing

import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.player.PlaybackController
import app.yinyuehe.core.player.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakePlaybackController : PlaybackController {
  data class PlayRequest(val tracks: List<Track>, val startIndex: Int, val shuffle: Boolean)

  private val mutableState = MutableStateFlow(PlaybackState())
  override val state: StateFlow<PlaybackState> = mutableState
  val playRequests = mutableListOf<PlayRequest>()
  val seekPositions = mutableListOf<Long>()
  val queuedTracks = mutableListOf<Track>()
  val removedQueueIndices = mutableListOf<Int>()
  val skippedQueueIndices = mutableListOf<Int>()
  val shuffleUpdates = mutableListOf<Boolean>()
  var toggleCount = 0
  var previousCount = 0
  var nextCount = 0
  var playResult = true
  var playFailure: Throwable? = null
  var playHandler: suspend (PlayRequest) -> Boolean = {
    playFailure?.let { failure -> throw failure }
    playResult
  }

  override suspend fun play(tracks: List<Track>, startIndex: Int, shuffle: Boolean): Boolean {
    val request = PlayRequest(tracks, startIndex, shuffle)
    playRequests += request
    return playHandler(request)
  }

  override fun togglePlayPause() {
    toggleCount += 1
  }

  override fun seekTo(positionMs: Long) {
    seekPositions += positionMs
  }

  override fun seekToPrevious() {
    previousCount += 1
  }

  override fun seekToNext() {
    nextCount += 1
  }

  override fun addToQueue(track: Track) {
    queuedTracks += track
  }

  override fun removeQueueItem(index: Int) {
    removedQueueIndices += index
  }

  override fun skipToQueueItem(index: Int) {
    skippedQueueIndices += index
  }

  override fun setShuffleEnabled(enabled: Boolean) {
    shuffleUpdates += enabled
  }
}
