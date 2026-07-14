package app.yinyuehe

import app.yinyuehe.core.common.analytics.PlaybackEvent
import app.yinyuehe.core.common.analytics.PlaybackEventName
import app.yinyuehe.core.common.analytics.PlaybackEventRecorder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessFirstFrameAnalyticsTest {
  @Test
  fun configurationRecreation_recordsOnlyTheFirstActualFrameInTheProcess() = runTest {
    val recorder = FirstFrameRecorder()
    val analytics = ProcessFirstFrameAnalytics(recorder, this)

    analytics.recordOnce(durationMs = 310L, occurredAtEpochMs = 1_000L)
    analytics.recordOnce(durationMs = 25L, occurredAtEpochMs = 2_000L)
    advanceUntilIdle()

    assertEquals(1, recorder.events.size)
    assertEquals(PlaybackEventName.FIRST_FRAME, recorder.events.single().name)
    assertEquals(310L, recorder.events.single().durationMs)
    assertEquals(1_000L, recorder.events.single().occurredAtEpochMs)
  }

  @Test
  fun processRestart_withANewScopedInstance_canRecordAgain() = runTest {
    val recorder = FirstFrameRecorder()

    ProcessFirstFrameAnalytics(recorder, this)
      .recordOnce(durationMs = 100L, occurredAtEpochMs = 1_000L)
    ProcessFirstFrameAnalytics(recorder, this)
      .recordOnce(durationMs = 200L, occurredAtEpochMs = 2_000L)
    advanceUntilIdle()

    assertEquals(listOf(100L, 200L), recorder.events.map(PlaybackEvent::durationMs))
  }

  @Test
  fun recorderFailure_isIsolatedFromTheLaunchPath() = runTest {
    val analytics =
      ProcessFirstFrameAnalytics(FirstFrameRecorder(failure = IllegalStateException()), this)

    analytics.recordOnce(durationMs = 100L, occurredAtEpochMs = 1_000L)
    advanceUntilIdle()
  }
}

private class FirstFrameRecorder(
  private val failure: Exception? = null,
) : PlaybackEventRecorder {
  val events = mutableListOf<PlaybackEvent>()

  override suspend fun record(event: PlaybackEvent) {
    failure?.let { throw it }
    events += event
  }
}
