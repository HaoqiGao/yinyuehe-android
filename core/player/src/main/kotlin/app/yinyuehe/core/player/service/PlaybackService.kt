package app.yinyuehe.core.player.service

import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import app.yinyuehe.core.common.analytics.PlaybackEventRecorder
import app.yinyuehe.core.common.analytics.PlaybackHistoryRecorder
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {
  @Inject lateinit var playbackEventRecorder: PlaybackEventRecorder
  @Inject lateinit var playbackHistoryRecorder: PlaybackHistoryRecorder

  private val eventTracker = PlaybackServiceEventTracker()
  private var recordingQueue: PlaybackServiceRecordingQueue? = null
  private var session: MediaLibrarySession? = null
  private var player: ExoPlayer? = null
  private var playerListener: Player.Listener? = null

  override fun onCreate() {
    super.onCreate()
    recordingQueue =
      PlaybackServiceRecordingQueue(
        eventRecorder = playbackEventRecorder,
        historyRecorder = playbackHistoryRecorder,
        onRecordingFailure = { error ->
          Log.w(TAG, "Playback metadata recording failed", error)
        },
      )
    val player =
      ExoPlayer.Builder(this)
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build(),
          true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
    val listener =
      object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
          recordUpdate(
            eventTracker.onMediaItemTransition(
              mediaId = mediaItem?.mediaId,
              isPlaying = player.isPlaying,
            )
          )
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
          recordUpdate(
            eventTracker.onIsPlayingChanged(
              isPlaying = isPlaying,
              mediaId = player.currentMediaItem?.mediaId,
            )
          )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
          if (playbackState == Player.STATE_ENDED) {
            recordUpdate(eventTracker.onPlaybackEnded(player.currentMediaItem?.mediaId))
          }
        }
      }
    player.addListener(listener)
    val callback =
      object : MediaLibrarySession.Callback {
        @UnstableApi
        override fun onConnect(
          mediaSession: MediaSession,
          controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
          if (controller.isTrusted) {
            super.onConnect(mediaSession, controller)
          } else {
            MediaSession.ConnectionResult.reject()
          }

        override fun onAddMediaItems(
          mediaSession: MediaSession,
          controller: MediaSession.ControllerInfo,
          mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = Futures.immediateFuture(mediaItems)
      }
    this.player = player
    playerListener = listener
    session = MediaLibrarySession.Builder(this, player, callback).build()
  }

  private fun recordUpdate(update: PlaybackServiceUpdate?) {
    recordingQueue?.record(update)
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
    session

  override fun onDestroy() {
    playerListener?.let { listener -> player?.removeListener(listener) }
    session?.release()
    player?.release()
    playerListener = null
    player = null
    session = null
    recordingQueue?.close()
    recordingQueue = null
    super.onDestroy()
  }

  private companion object {
    const val TAG = "PlaybackService"
  }
}
