package app.yinyuehe.core.player.service

import androidx.media3.common.Player
import androidx.media3.common.Timeline

internal class PlaybackPersistencePlayerListener(
  private val player: Player,
  private val tokens: PlaybackOccurrenceTokens,
  private val gate: RestorePersistenceGate,
  private val persistence: PlaybackPersistenceCoordinator,
  private val onGateChanged: () -> Unit,
) : Player.Listener {
  override fun onTimelineChanged(timeline: Timeline, reason: Int) {
    val opened = gate.onConfirmedTimeline(player.queueFingerprint(tokens))
    if (opened) {
      onGateChanged()
      persistence.onGateOpened()
    } else {
      persistence.onCoalescedChange()
    }
  }

  override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
    persistence.onCoalescedChange()
  }

  override fun onRepeatModeChanged(repeatMode: Int) = persistence.onCoalescedChange()

  override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) =
    persistence.onCoalescedChange()

  override fun onPositionDiscontinuity(
    oldPosition: Player.PositionInfo,
    newPosition: Player.PositionInfo,
    reason: Int,
  ) {
    if (
      reason == Player.DISCONTINUITY_REASON_SEEK ||
        reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
    ) {
      persistence.onImmediateChange()
    }
  }

  override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
    if (!playWhenReady) persistence.onImmediateChange()
  }

  override fun onIsPlayingChanged(isPlaying: Boolean) =
    persistence.onIsPlayingChanged(isPlaying)

  override fun onPlaybackStateChanged(playbackState: Int) {
    if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
      persistence.onImmediateChange()
    }
  }
}
