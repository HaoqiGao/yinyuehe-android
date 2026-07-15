package app.yinyuehe.core.player.service

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

internal class PlaybackLibrarySessionCallback(
  private val tokens: PlaybackOccurrenceTokens,
  private val gate: RestorePersistenceGate,
) : MediaLibrarySession.Callback {
  @UnstableApi
  override fun onConnect(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
  ): MediaSession.ConnectionResult =
    if (controller.isTrusted) super.onConnect(mediaSession, controller)
    else MediaSession.ConnectionResult.reject()

  override fun onAddMediaItems(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    mediaItems: List<MediaItem>,
  ): ListenableFuture<List<MediaItem>> =
    Futures.immediateFuture(mediaItems.map(tokens::decorate))

  @UnstableApi
  override fun onSetMediaItems(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
    gate.recordSetMediaItems(
      caller = ControllerIdentity(controller.packageName, controller.uid),
      expectedMediaIds = mediaItems.map(MediaItem::mediaId),
      startIndex = startIndex,
    )
    return Futures.immediateFuture(
      MediaSession.MediaItemsWithStartPosition(
        mediaItems.map(tokens::decorate),
        startIndex,
        startPositionMs,
      )
    )
  }
}
