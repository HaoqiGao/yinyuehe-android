package app.yinyuehe.core.player.service

import app.yinyuehe.core.common.analytics.PlaybackEvent
import app.yinyuehe.core.common.analytics.PlaybackEventRecorder
import app.yinyuehe.core.common.analytics.PlaybackHistoryRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class PlaybackServiceRecordingQueue(
  private val eventRecorder: PlaybackEventRecorder,
  private val historyRecorder: PlaybackHistoryRecorder,
  dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
  private val epochTimeMs: () -> Long = System::currentTimeMillis,
  private val onRecordingFailure: (Exception) -> Unit = {},
) {
  private val queueJob = SupervisorJob()
  private val queueScope = CoroutineScope(queueJob + dispatcher)
  private val mutex = Mutex()

  fun record(update: PlaybackServiceUpdate?) {
    if (update == null) return
    val occurredAtEpochMs = epochTimeMs()
    queueScope.launch {
      mutex.withLock {
        update.eventNames.forEach { name ->
          recordSafely {
            eventRecorder.record(
              PlaybackEvent(
                name = name,
                trackId = update.trackId,
                occurredAtEpochMs = occurredAtEpochMs,
              )
            )
          }
        }
        if (update.recordHistory) {
          recordSafely { historyRecorder.recordRecent(update.trackId) }
        }
      }
    }
  }

  fun close() {
    queueJob.complete()
  }

  private suspend fun recordSafely(block: suspend () -> Unit) {
    try {
      block()
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (error: Exception) {
      onRecordingFailure(error)
    }
  }
}
