package app.yinyuehe.core.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.player.service.PlaybackService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await

class Media3PlaybackController @Inject constructor(
  @ApplicationContext context: Context,
) : PlaybackController {
  private val _state = MutableStateFlow(PlaybackState())
  override val state: StateFlow<PlaybackState> = _state.asStateFlow()

  private val controllerFuture =
    MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java)),
      )
      .buildAsync()
  private var controller: MediaController? = null

  private val listener =
    object : Player.Listener {
      override fun onEvents(player: Player, events: Player.Events) {
        _state.value = player.snapshot(PlaybackConnection.CONNECTED).toPlaybackState()
      }
    }

  init {
    controllerFuture.addListener(
      {
        runCatching { controllerFuture.get() }
          .onSuccess {
            controller = it
            it.addListener(listener)
            _state.value = it.snapshot(PlaybackConnection.CONNECTED).toPlaybackState()
          }
          .onFailure {
            _state.value = PlaybackState(connection = PlaybackConnection.DISCONNECTED)
          }
      },
      ContextCompat.getMainExecutor(context),
    )
  }

  override suspend fun play(tracks: List<Track>, startIndex: Int) {
    require(tracks.isNotEmpty()) { "Playback queue must not be empty" }
    require(startIndex in tracks.indices) { "startIndex must reference the queue" }
    val mediaController = controllerFuture.await()
    mediaController.setMediaItems(tracks.map { it.toMediaItem() }, startIndex, 0)
    mediaController.prepare()
    mediaController.play()
  }

  override fun togglePlayPause() {
    controller?.let { if (it.isPlaying) it.pause() else it.play() }
  }
}

private fun Player.snapshot(connection: PlaybackConnection): PlayerSnapshot =
  PlayerSnapshot(
    connection = connection,
    currentMediaId = currentMediaItem?.mediaId,
    isPlaying = isPlaying,
    positionMs = currentPosition,
    durationMs = duration.takeUnless { it == C.TIME_UNSET } ?: 0,
    queueMediaIds = List(mediaItemCount) { getMediaItemAt(it).mediaId },
  )
