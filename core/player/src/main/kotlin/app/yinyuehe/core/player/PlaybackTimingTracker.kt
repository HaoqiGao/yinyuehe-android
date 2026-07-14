package app.yinyuehe.core.player

import android.os.SystemClock
import app.yinyuehe.core.common.model.TrackId

internal class PlaybackTimingTracker(
  private val monotonicTimeMs: () -> Long = SystemClock::elapsedRealtime,
) {
  private var pending: PendingRequest? = null

  fun onPlayRequested(trackId: TrackId) {
    pending = PendingRequest(trackId, monotonicTimeMs())
  }

  fun clearPendingRequest() {
    pending = null
  }

  fun onPlaybackStarted(trackId: TrackId): Long? {
    val request = pending?.takeIf { it.trackId == trackId } ?: return null
    pending = null
    return (monotonicTimeMs() - request.requestedAtMs).coerceAtLeast(0)
  }

  private data class PendingRequest(val trackId: TrackId, val requestedAtMs: Long)
}
