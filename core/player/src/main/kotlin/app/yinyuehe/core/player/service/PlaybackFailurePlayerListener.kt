package app.yinyuehe.core.player.service

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player

internal class PlaybackFailurePlayerListener(
  private val coordinator: PlaybackFailureCoordinator,
) : Player.Listener {
  override fun onPlayerError(error: PlaybackException) = coordinator.onPlayerError(error)

  override fun onPlaybackStateChanged(playbackState: Int) {
    if (playbackState == Player.STATE_ENDED) coordinator.onPlaybackEnded()
  }
}
