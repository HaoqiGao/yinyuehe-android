package app.yinyuehe

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.getSystemService
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.player.service.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class M3AControllerSnapshot(
  val mediaIds: List<String>,
  val currentIndex: Int,
  val positionMs: Long,
  val playWhenReady: Boolean,
  val isPlaying: Boolean,
  val shuffleEnabled: Boolean,
  val repeatMode: Int,
  val queuePersistenceLimited: Boolean,
)

class M3AMediaControllerHarness(private val context: Context) {
  private val controllers = mutableListOf<MediaController>()

  suspend fun connect(): MediaController =
    withContext(Dispatchers.Main.immediate) {
      val future =
        MediaController.Builder(
          context,
          SessionToken(context, ComponentName(context, PlaybackService::class.java)),
        )
        .buildAsync()
      try {
        withTimeout(DEFAULT_TIMEOUT_MS) { future.await() }.also(controllers::add)
      } catch (failure: Throwable) {
        MediaController.releaseFuture(future)
        throw failure
      }
    }

  suspend fun replaceQueue(
    controller: MediaController,
    tracks: List<Track>,
    startIndex: Int,
    startPositionMs: Long = 0,
    shuffle: Boolean = false,
    repeatMode: Int = Player.REPEAT_MODE_OFF,
    play: Boolean = false,
  ) {
    withContext(Dispatchers.Main.immediate) {
      controller.setMediaItems(tracks.map(::mediaItem), startIndex, startPositionMs)
      controller.shuffleModeEnabled = shuffle
      controller.repeatMode = repeatMode
      controller.prepare()
      if (play) controller.play() else controller.pause()
    }
  }

  suspend fun add(controller: MediaController, track: Track) {
    withContext(Dispatchers.Main.immediate) { controller.addMediaItem(mediaItem(track)) }
  }

  suspend fun remove(controller: MediaController, index: Int) {
    withContext(Dispatchers.Main.immediate) { controller.removeMediaItem(index) }
  }

  suspend fun move(controller: MediaController, fromIndex: Int, toIndex: Int) {
    withContext(Dispatchers.Main.immediate) { controller.moveMediaItem(fromIndex, toIndex) }
  }

  suspend fun awaitSnapshot(
    controller: MediaController,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    predicate: (M3AControllerSnapshot) -> Boolean,
  ): M3AControllerSnapshot =
    withTimeout(timeoutMs) {
      while (true) {
        val snapshot = snapshot(controller)
        if (predicate(snapshot)) return@withTimeout snapshot
        delay(POLL_INTERVAL_MS)
      }
      error("unreachable")
    }

  suspend fun snapshot(controller: MediaController): M3AControllerSnapshot =
    withContext(Dispatchers.Main.immediate) {
      M3AControllerSnapshot(
        mediaIds = List(controller.mediaItemCount) { controller.getMediaItemAt(it).mediaId },
        currentIndex = controller.currentMediaItemIndex,
        positionMs = controller.currentPosition,
        playWhenReady = controller.playWhenReady,
        isPlaying = controller.isPlaying,
        shuffleEnabled = controller.shuffleModeEnabled,
        repeatMode = controller.repeatMode,
        queuePersistenceLimited =
          controller.sessionExtras.getBoolean("app.yinyuehe.extra.QUEUE_PERSISTENCE_LIMITED"),
      )
    }

  suspend fun sessionReportsActivePlayback(): Boolean {
    val independentController = connect()
    return snapshot(independentController).let { it.playWhenReady || it.isPlaying }
  }

  fun mediaNotificationIsAbsentOrOffersPlayNotPause(): Boolean {
    val notifications = context.getSystemService<NotificationManager>()!!.activeNotifications
    val mediaNotifications =
      notifications.filter { status ->
        status.packageName == context.packageName &&
          status.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
      }
    return mediaNotifications.all { status ->
      @Suppress("DEPRECATION")
      val actionResourceNames =
        status.notification.actions.orEmpty().mapNotNull { action ->
          runCatching { context.resources.getResourceEntryName(action.icon) }.getOrNull()
        }
      "media3_icon_play" in actionResourceNames && "media3_icon_pause" !in actionResourceNames
    }
  }

  suspend fun close() {
    withContext(Dispatchers.Main.immediate) {
      controllers.toList().forEach(MediaController::release)
      controllers.clear()
    }
  }

  private fun mediaItem(track: Track): MediaItem =
    MediaItem.Builder()
      .setMediaId(track.id.value)
      .setUri(track.sourceUri)
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle(track.title ?: track.displayName)
          .setArtist(track.artist)
          .setAlbumTitle(track.album)
          .setArtworkUri(track.artworkUri?.let(Uri::parse))
          .setDurationMs(track.durationMs)
          .setIsPlayable(true)
          .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
          .build()
      )
      .build()

  private companion object {
    const val DEFAULT_TIMEOUT_MS = 15_000L
    const val POLL_INTERVAL_MS = 50L
  }
}
