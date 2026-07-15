package app.yinyuehe.core.player.service

import app.yinyuehe.core.common.analytics.PlaybackEvent
import app.yinyuehe.core.common.analytics.PlaybackEventName
import app.yinyuehe.core.common.analytics.PlaybackEventRecorder
import app.yinyuehe.core.common.analytics.PlaybackHistoryRecorder
import app.yinyuehe.core.common.model.TrackId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackServiceRecordingQueueTest {
  @Test
  fun close_drainsUpdatesThatWereAlreadyQueued() = runTest {
    val firstWriteGate = CompletableDeferred<Unit>()
    val eventRecorder = SuspendingEventRecorder(firstWriteGate)
    val historyRecorder = RecordingHistoryRecorder()
    val queue =
      PlaybackServiceRecordingQueue(
        eventRecorder = eventRecorder,
        historyRecorder = historyRecorder,
        dispatcher = StandardTestDispatcher(testScheduler),
        epochTimeMs = { 1_234L },
      )
    val trackId = TrackId("local:one")
    queue.record(
      PlaybackServiceUpdate(
        eventNames =
          listOf(PlaybackEventName.TRACK_CHANGED, PlaybackEventName.PLAYBACK_STARTED),
        trackId = trackId,
        recordHistory = true,
      )
    )
    runCurrent()

    queue.close()
    firstWriteGate.complete(Unit)
    advanceUntilIdle()

    assertEquals(
      listOf(PlaybackEventName.TRACK_CHANGED, PlaybackEventName.PLAYBACK_STARTED),
      eventRecorder.events.map { it.name },
    )
    assertEquals(listOf(trackId), historyRecorder.trackIds)
    assertEquals(listOf(1_234L, 1_234L), eventRecorder.events.map { it.occurredAtEpochMs })
  }
}

private class SuspendingEventRecorder(
  private val firstWriteGate: CompletableDeferred<Unit>,
) : PlaybackEventRecorder {
  val events = mutableListOf<PlaybackEvent>()

  override suspend fun record(event: PlaybackEvent) {
    events += event
    if (events.size == 1) firstWriteGate.await()
  }
}

private class RecordingHistoryRecorder : PlaybackHistoryRecorder {
  val trackIds = mutableListOf<TrackId>()

  override suspend fun recordRecent(trackId: TrackId, positionMs: Long?): Boolean {
    trackIds += trackId
    return true
  }
}
