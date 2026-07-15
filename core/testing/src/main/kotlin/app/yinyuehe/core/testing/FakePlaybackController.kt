package app.yinyuehe.core.testing

import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.playback.PlaybackNotice
import app.yinyuehe.core.common.playback.PlaybackRepeatMode
import app.yinyuehe.core.player.PlaybackController
import app.yinyuehe.core.player.PlaybackState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakePlaybackController : PlaybackController {
  data class PlayRequest(val tracks: List<Track>, val startIndex: Int, val shuffle: Boolean)

  private val mutableState = MutableStateFlow(PlaybackState())
  override val state: StateFlow<PlaybackState> = mutableState
  private val mutableNotices = MutableSharedFlow<PlaybackNotice>(extraBufferCapacity = 8)
  override val notices: Flow<PlaybackNotice> = mutableNotices
  val playRequests = mutableListOf<PlayRequest>()
  val seekPositions = mutableListOf<Long>()
  val queuedTracks = mutableListOf<Track>()
  val removedQueueIndices = mutableListOf<Int>()
  val skippedQueueIndices = mutableListOf<Int>()
  val shuffleUpdates = mutableListOf<Boolean>()
  val repeatUpdates = mutableListOf<PlaybackRepeatMode>()
  val movedQueueItems = mutableListOf<Pair<Int, Int>>()
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

  override fun setRepeatMode(mode: PlaybackRepeatMode) {
    repeatUpdates += mode
  }

  override fun moveQueueItem(fromIndex: Int, toIndex: Int) {
    movedQueueItems += fromIndex to toIndex
  }
}
