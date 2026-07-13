package app.yinyuehe.core.player.service

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class PlaybackService : MediaLibraryService() {
  private var session: MediaLibrarySession? = null

  override fun onCreate() {
    super.onCreate()
    val player =
      ExoPlayer.Builder(this)
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build(),
          true,
        )
        .build()
    val callback =
      object : MediaLibrarySession.Callback {
        override fun onAddMediaItems(
          mediaSession: MediaSession,
          controller: MediaSession.ControllerInfo,
          mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = Futures.immediateFuture(mediaItems)
      }
    session = MediaLibrarySession.Builder(this, player, callback).build()
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
    session

  override fun onDestroy() {
    session?.run {
      player.release()
      release()
    }
    session = null
    super.onDestroy()
  }
}
