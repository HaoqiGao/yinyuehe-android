package app.yinyuehe.core.player.service

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

internal class PlaybackLibrarySessionCallback(
  private val tokens: PlaybackOccurrenceTokens,
  private val gate: RestorePersistenceGate,
  private val onUserRetry: () -> Unit = {},
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

  @UnstableApi
  override fun onPlayerInteractionFinished(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    playerCommands: Player.Commands,
  ) {
    val explicitRetry =
      playerCommands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS) ||
        playerCommands.contains(Player.COMMAND_SEEK_TO_MEDIA_ITEM) ||
        playerCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) ||
        playerCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) ||
        playerCommands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) ||
        playerCommands.contains(Player.COMMAND_PREPARE) ||
        (playerCommands.contains(Player.COMMAND_PLAY_PAUSE) && mediaSession.player.playWhenReady)
    if (explicitRetry) onUserRetry()
  }
}
