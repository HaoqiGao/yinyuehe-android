package app.yinyuehe.core.player

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.yinyuehe.core.common.analytics.PlaybackEventRecorder
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.player.service.PlaybackService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Media3PlaybackController @Inject constructor(
  @ApplicationContext private val context: Context,
  playbackEventRecorder: PlaybackEventRecorder,
) : PlaybackController {
  private val _state = MutableStateFlow(PlaybackState())
  override val state: StateFlow<PlaybackState> = _state.asStateFlow()

  private val mainExecutor = ContextCompat.getMainExecutor(context)
  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val requestAnalytics =
    PlaybackRequestAnalytics(
      recorder = playbackEventRecorder,
      scope = applicationScope,
      onRecordingFailure = { error ->
        Log.w(TAG, "Playback request analytics recording failed", error)
      },
    )
  private var controller: MediaController? = null
  private var positionTickerJob: Job? = null

  private val playerListener =
    object : Player.Listener {
      override fun onEvents(player: Player, events: Player.Events) {
        applicationScope.launch {
          if (controller !== player) return@launch
          publishSnapshot(player)
          requestAnalytics.onPlayerCallback(
            isPlaying = player.isPlaying,
            trackId = player.currentMediaItem?.mediaId?.toTrackIdOrNull(),
          )
        }
      }
    }

  private val controllerListener =
    object : MediaController.Listener {
      override fun onDisconnected(disconnectedController: MediaController) {
        applicationScope.launch {
          if (controller !== disconnectedController) return@launch
          controller = null
          stopPositionTicker()
          _state.value = PlaybackState(connection = PlaybackConnection.DISCONNECTED)
          rebuildController()
        }
      }
    }

  private var controllerFuture: ListenableFuture<MediaController> = buildController()

  init {
    observeConnection(controllerFuture)
  }

  private fun buildController(): ListenableFuture<MediaController> =
    MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java)),
      )
      .setApplicationLooper(Looper.getMainLooper())
      .setListener(controllerListener)
      .buildAsync()

  private fun observeConnection(future: ListenableFuture<MediaController>) {
    future.addListener(
      {
        applicationScope.launch {
          if (controllerFuture !== future) return@launch
          runCatching { future.get() }
            .onSuccess {
              controller = it
              it.addListener(playerListener)
              publishSnapshot(it)
            }
            .onFailure {
              controller = null
              stopPositionTicker()
              _state.value = PlaybackState(connection = PlaybackConnection.DISCONNECTED)
            }
        }
      },
      mainExecutor,
    )
  }

  private fun rebuildController(): ListenableFuture<MediaController> =
    buildController().also {
      controllerFuture = it
      observeConnection(it)
    }

  override suspend fun play(tracks: List<Track>, startIndex: Int, shuffle: Boolean): Boolean {
    require(tracks.isNotEmpty()) { "Playback queue must not be empty" }
    require(startIndex in tracks.indices) { "startIndex must reference the queue" }
    return withContext(Dispatchers.Main.immediate) {
      val future = selectControllerFuture()
      val mediaController =
        try {
          Futures.nonCancellationPropagating(future).await()
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (_: Exception) {
          handleConnectionFailure(future)
          return@withContext false
        }
      val dispatcher = PlaybackCommandDispatcher(mediaController)
      if (!dispatcher.canPlayQueue()) return@withContext false
      requestAnalytics.onPlayRequested(tracks[startIndex].id)
      var dispatched = false
      try {
        dispatched = dispatcher.playQueue(tracks, startIndex, shuffle)
        dispatched
      } finally {
        if (!dispatched) requestAnalytics.onPlayDispatchRejected()
      }
    }
  }

  private fun selectControllerFuture(): ListenableFuture<MediaController> {
    if (!controllerFuture.hasFailed()) return controllerFuture
    _state.value = PlaybackState(connection = PlaybackConnection.CONNECTING)
    return rebuildController()
  }

  private fun handleConnectionFailure(future: ListenableFuture<MediaController>) {
    if (controllerFuture !== future) return
    controller = null
    stopPositionTicker()
    _state.value = PlaybackState(connection = PlaybackConnection.DISCONNECTED)
  }

  override fun togglePlayPause() {
    dispatch(PlaybackCommandDispatcher::togglePlayPause)
  }

  override fun seekTo(positionMs: Long) {
    dispatch { seekTo(positionMs) }
  }

  override fun seekToPrevious() {
    dispatch(PlaybackCommandDispatcher::seekToPrevious)
  }

  override fun seekToNext() {
    dispatch(PlaybackCommandDispatcher::seekToNext)
  }

  override fun addToQueue(track: Track) {
    dispatch { addToQueue(track) }
  }

  override fun removeQueueItem(index: Int) {
    dispatch { removeQueueItem(index) }
  }

  override fun skipToQueueItem(index: Int) {
    dispatch { skipToQueueItem(index) }
  }

  override fun setShuffleEnabled(enabled: Boolean) {
    dispatch { setShuffleEnabled(enabled) }
  }

  private fun dispatch(command: PlaybackCommandDispatcher.() -> Unit) {
    applicationScope.launch {
      controller?.let { mediaController -> PlaybackCommandDispatcher(mediaController).command() }
    }
  }

  private fun publishSnapshot(player: Player) {
    _state.value = player.snapshot(PlaybackConnection.CONNECTED).toPlaybackState()
    if (player.isPlaying) startPositionTicker() else stopPositionTicker()
  }

  private fun startPositionTicker() {
    if (positionTickerJob?.isActive == true) return
    positionTickerJob =
      applicationScope.launch {
        while (isActive) {
          delay(POSITION_UPDATE_INTERVAL_MS)
          val mediaController = controller
          if (mediaController == null || !mediaController.isPlaying) {
            stopPositionTicker()
            break
          }
          _state.value =
            mediaController.snapshot(PlaybackConnection.CONNECTED).toPlaybackState()
        }
      }
  }

  private fun stopPositionTicker() {
    positionTickerJob?.cancel()
    positionTickerJob = null
  }
}

internal fun shouldPauseForToggle(playWhenReady: Boolean): Boolean = playWhenReady

private fun Player.snapshot(connection: PlaybackConnection): PlayerSnapshot {
  val itemCount = mediaItemCount
  val queueMediaIds = List(itemCount) { getMediaItemAt(it).mediaId }
  val index = currentMediaItemIndex.takeIf { it in queueMediaIds.indices } ?: C.INDEX_UNSET
  return PlayerSnapshot(
    connection = connection,
    currentMediaId = currentMediaItem?.mediaId,
    currentIndex = index,
    isPlaying = isPlaying,
    positionMs = currentPosition,
    durationMs = duration.takeUnless { it == C.TIME_UNSET } ?: 0,
    queueMediaIds = queueMediaIds,
    shuffleEnabled = shuffleModeEnabled,
    canSeek = isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
    canPrevious =
      isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) && hasPreviousMediaItem(),
    canNext = isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) && hasNextMediaItem(),
  )
}

private const val POSITION_UPDATE_INTERVAL_MS = 500L
private const val TAG = "PlaybackController"

private fun String.toTrackIdOrNull(): TrackId? =
  takeIf(String::isNotBlank)?.let(::TrackId)
