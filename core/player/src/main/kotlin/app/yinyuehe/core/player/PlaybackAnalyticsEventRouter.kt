package app.yinyuehe.core.player

import app.yinyuehe.core.common.model.TrackId

internal class PlaybackAnalyticsEventRouter(
  private val analytics: PlaybackRequestAnalytics,
) {
  fun onEvents(
    isPlayingChanged: Boolean,
    isPlaying: Boolean,
    trackId: TrackId?,
    playerErrorChanged: Boolean,
    hasPlayerError: Boolean,
  ) {
    if (playerErrorChanged && hasPlayerError) {
      analytics.onPlaybackFailure()
    }
    if (isPlayingChanged && isPlaying) {
      analytics.onPlaybackStartBoundary(trackId)
    }
  }
}
