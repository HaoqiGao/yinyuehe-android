package app.yinyuehe

import app.yinyuehe.core.common.analytics.PlaybackEvent
import app.yinyuehe.core.common.analytics.PlaybackEventName
import app.yinyuehe.core.common.analytics.PlaybackEventRecorder
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Singleton
internal class ProcessFirstFrameAnalytics internal constructor(
  private val recorder: PlaybackEventRecorder,
  private val recordingScope: CoroutineScope,
) {
  @Inject
  constructor(
    recorder: PlaybackEventRecorder,
  ) : this(recorder, CoroutineScope(SupervisorJob() + Dispatchers.IO))

  private val recorded = AtomicBoolean(false)

  fun recordOnce(
    durationMs: Long,
    occurredAtEpochMs: Long = System.currentTimeMillis(),
  ) {
    if (!recorded.compareAndSet(false, true)) return
    recordingScope.launch {
      try {
        recorder.record(
          PlaybackEvent(
            name = PlaybackEventName.FIRST_FRAME,
            occurredAtEpochMs = occurredAtEpochMs,
            durationMs = durationMs,
          )
        )
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Exception) {
        // Startup analytics must never affect the user-visible launch path.
      }
    }
  }
}
