package app.yinyuehe.core.player

import app.yinyuehe.core.common.analytics.PlaybackEvent
import app.yinyuehe.core.common.analytics.PlaybackEventName
import app.yinyuehe.core.common.analytics.PlaybackEventRecorder
import app.yinyuehe.core.common.model.TrackId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class PlaybackRequestAnalytics(
  private val recorder: PlaybackEventRecorder,
  private val scope: CoroutineScope,
  private val timingTracker: PlaybackTimingTracker = PlaybackTimingTracker(),
  private val epochTimeMs: () -> Long = System::currentTimeMillis,
  private val onRecordingFailure: (Exception) -> Unit = {},
) {
  fun onPlayRequested(trackId: TrackId) {
    timingTracker.onPlayRequested(trackId)
    recordAsync(
      PlaybackEvent(
        name = PlaybackEventName.PLAY_REQUESTED,
        trackId = trackId,
        occurredAtEpochMs = epochTimeMs(),
      )
    )
  }

  fun onPlayerCallback(isPlaying: Boolean, trackId: TrackId?) {
    if (!isPlaying || trackId == null) return
    val latencyMs = timingTracker.onPlaybackStarted(trackId) ?: return
    recordAsync(
      PlaybackEvent(
        name = PlaybackEventName.PLAY_START_LATENCY,
        trackId = trackId,
        occurredAtEpochMs = epochTimeMs(),
        durationMs = latencyMs,
      )
    )
  }

  fun onPlaybackStartBoundary(trackId: TrackId?) {
    if (trackId == null) {
      timingTracker.clearPendingRequest()
      return
    }
    onPlayerCallback(isPlaying = true, trackId = trackId)
  }

  fun onPlayDispatchRejected() {
    onPlaybackFailure()
  }

  fun onPlaybackFailure() {
    timingTracker.clearPendingRequest()
  }

  private fun recordAsync(event: PlaybackEvent) {
    scope.launch {
      try {
        recorder.record(event)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Exception) {
        onRecordingFailure(error)
      }
    }
  }
}
