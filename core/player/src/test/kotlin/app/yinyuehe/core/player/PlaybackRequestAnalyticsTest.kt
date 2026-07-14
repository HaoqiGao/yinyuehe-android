package app.yinyuehe.core.player

import app.yinyuehe.core.common.analytics.PlaybackEvent
import app.yinyuehe.core.common.analytics.PlaybackEventName
import app.yinyuehe.core.common.analytics.PlaybackEventRecorder
import app.yinyuehe.core.common.model.TrackId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRequestAnalyticsTest {
  @Test
  fun unrelatedPlayerEvent_doesNotConsumePendingLatencyBeforeMatchingStart() = runTest {
    var elapsedMs = 100L
    val recorder = RecordingPlaybackEventRecorder()
    val analytics =
      PlaybackRequestAnalytics(
        recorder = recorder,
        scope = this,
        timingTracker = PlaybackTimingTracker { elapsedMs },
        epochTimeMs = { 1_000L },
      )
    val router = PlaybackAnalyticsEventRouter(analytics)
    val requested = TrackId("local:requested")

    analytics.onPlayRequested(requested)
    router.onEvents(
      isPlayingChanged = false,
      isPlaying = true,
      trackId = TrackId("local:unrelated"),
      playerErrorChanged = false,
      hasPlayerError = false,
    )
    elapsedMs = 250L
    router.onEvents(
      isPlayingChanged = true,
      isPlaying = true,
      trackId = requested,
      playerErrorChanged = false,
      hasPlayerError = false,
    )
    advanceUntilIdle()

    assertEquals(
      listOf(PlaybackEventName.PLAY_REQUESTED, PlaybackEventName.PLAY_START_LATENCY),
      recorder.events.map(PlaybackEvent::name),
    )
    assertEquals(150L, recorder.events.last().durationMs)
  }

  @Test
  fun actualStartOfAnotherTrack_invalidatesPendingLatency() = runTest {
    val recorder = RecordingPlaybackEventRecorder()
    val analytics =
      PlaybackRequestAnalytics(
        recorder = recorder,
        scope = this,
        timingTracker = PlaybackTimingTracker { 100L },
        epochTimeMs = { 1_000L },
      )
    val router = PlaybackAnalyticsEventRouter(analytics)
    val requested = TrackId("local:requested")

    analytics.onPlayRequested(requested)
    router.onEvents(
      isPlayingChanged = true,
      isPlaying = true,
      trackId = TrackId("local:other"),
      playerErrorChanged = false,
      hasPlayerError = false,
    )
    router.onEvents(
      isPlayingChanged = true,
      isPlaying = true,
      trackId = requested,
      playerErrorChanged = false,
      hasPlayerError = false,
    )
    advanceUntilIdle()

    assertEquals(listOf(PlaybackEventName.PLAY_REQUESTED), recorder.events.map(PlaybackEvent::name))
  }

  @Test
  fun actualStartWithoutTrackId_invalidatesPendingLatency() = runTest {
    val recorder = RecordingPlaybackEventRecorder()
    val analytics =
      PlaybackRequestAnalytics(
        recorder = recorder,
        scope = this,
        timingTracker = PlaybackTimingTracker { 100L },
        epochTimeMs = { 1_000L },
      )
    val router = PlaybackAnalyticsEventRouter(analytics)
    val requested = TrackId("local:requested")

    analytics.onPlayRequested(requested)
    router.onEvents(
      isPlayingChanged = true,
      isPlaying = true,
      trackId = null,
      playerErrorChanged = false,
      hasPlayerError = false,
    )
    router.onEvents(
      isPlayingChanged = true,
      isPlaying = true,
      trackId = requested,
      playerErrorChanged = false,
      hasPlayerError = false,
    )
    advanceUntilIdle()

    assertEquals(listOf(PlaybackEventName.PLAY_REQUESTED), recorder.events.map(PlaybackEvent::name))
  }

  @Test
  fun clearingAPriorPlayerError_doesNotClearANewerPendingRequest() = runTest {
    val recorder = RecordingPlaybackEventRecorder()
    val analytics =
      PlaybackRequestAnalytics(
        recorder = recorder,
        scope = this,
        timingTracker = PlaybackTimingTracker { 100L },
        epochTimeMs = { 1_000L },
      )
    val router = PlaybackAnalyticsEventRouter(analytics)
    val requested = TrackId("local:requested")

    router.onEvents(
      isPlayingChanged = false,
      isPlaying = false,
      trackId = null,
      playerErrorChanged = true,
      hasPlayerError = true,
    )
    analytics.onPlayRequested(requested)
    router.onEvents(
      isPlayingChanged = false,
      isPlaying = false,
      trackId = requested,
      playerErrorChanged = true,
      hasPlayerError = false,
    )
    router.onEvents(
      isPlayingChanged = true,
      isPlaying = true,
      trackId = requested,
      playerErrorChanged = false,
      hasPlayerError = false,
    )
    advanceUntilIdle()

    assertEquals(
      listOf(PlaybackEventName.PLAY_REQUESTED, PlaybackEventName.PLAY_START_LATENCY),
      recorder.events.map(PlaybackEvent::name),
    )
  }

  @Test
  fun nonNullPlayerError_clearsPendingLatency() = runTest {
    val recorder = RecordingPlaybackEventRecorder()
    val analytics =
      PlaybackRequestAnalytics(
        recorder = recorder,
        scope = this,
        timingTracker = PlaybackTimingTracker { 100L },
        epochTimeMs = { 1_000L },
      )
    val router = PlaybackAnalyticsEventRouter(analytics)
    val requested = TrackId("local:requested")

    analytics.onPlayRequested(requested)
    router.onEvents(
      isPlayingChanged = false,
      isPlaying = false,
      trackId = requested,
      playerErrorChanged = true,
      hasPlayerError = true,
    )
    router.onEvents(
      isPlayingChanged = true,
      isPlaying = true,
      trackId = requested,
      playerErrorChanged = false,
      hasPlayerError = false,
    )
    advanceUntilIdle()

    assertEquals(listOf(PlaybackEventName.PLAY_REQUESTED), recorder.events.map(PlaybackEvent::name))
  }

  @Test
  fun requestedTrack_recordsRequestThenOneLatencyFromItsFirstPlayingCallback() = runTest {
    var elapsedMs = 100L
    var epochMs = 1_000L
    val recorder = RecordingPlaybackEventRecorder()
    val analytics =
      PlaybackRequestAnalytics(
        recorder = recorder,
        scope = this,
        timingTracker = PlaybackTimingTracker { elapsedMs },
        epochTimeMs = { epochMs },
      )
    val requested = TrackId("local:requested")

    analytics.onPlayRequested(requested)
    elapsedMs = 250L
    epochMs = 1_150L
    analytics.onPlayerCallback(isPlaying = false, trackId = null)
    analytics.onPlayerCallback(isPlaying = false, trackId = requested)
    analytics.onPlayerCallback(isPlaying = true, trackId = requested)
    analytics.onPlayerCallback(isPlaying = true, trackId = requested)
    advanceUntilIdle()

    assertEquals(
      listOf(PlaybackEventName.PLAY_REQUESTED, PlaybackEventName.PLAY_START_LATENCY),
      recorder.events.map { it.name },
    )
    assertEquals(requested, recorder.events[0].trackId)
    assertEquals(1_000L, recorder.events[0].occurredAtEpochMs)
    assertEquals(requested, recorder.events[1].trackId)
    assertEquals(150L, recorder.events[1].durationMs)
    assertEquals(1_150L, recorder.events[1].occurredAtEpochMs)
  }

  @Test
  fun newerRequest_replacesPendingStateBeforeItsRecorderCanSuspend() = runTest {
    var elapsedMs = 100L
    val recorder = RecordingPlaybackEventRecorder()
    val analytics =
      PlaybackRequestAnalytics(
        recorder = recorder,
        scope = this,
        timingTracker = PlaybackTimingTracker { elapsedMs },
        epochTimeMs = { 1_000L },
      )
    val older = TrackId("local:older")
    val newer = TrackId("local:newer")
    analytics.onPlayRequested(older)
    runCurrent()

    val recordingGate = CompletableDeferred<Unit>()
    recorder.suspendNextRecordUntil = recordingGate
    elapsedMs = 200L
    analytics.onPlayRequested(newer)
    runCurrent()

    assertEquals(
      listOf(PlaybackEventName.PLAY_REQUESTED, PlaybackEventName.PLAY_REQUESTED),
      recorder.events.map { it.name },
    )

    recordingGate.complete(Unit)
    elapsedMs = 250L
    analytics.onPlayerCallback(isPlaying = true, trackId = newer)
    advanceUntilIdle()

    assertEquals(PlaybackEventName.PLAY_START_LATENCY, recorder.events.last().name)
    assertEquals(50L, recorder.events.last().durationMs)
  }

  @Test
  fun rejectedDispatch_clearsPendingLatency() = runTest {
    val recorder = RecordingPlaybackEventRecorder()
    val analytics =
      PlaybackRequestAnalytics(
        recorder = recorder,
        scope = this,
        timingTracker = PlaybackTimingTracker { 100L },
        epochTimeMs = { 1_000L },
      )
    val trackId = TrackId("local:rejected")

    analytics.onPlayRequested(trackId)
    analytics.onPlayDispatchRejected()
    analytics.onPlayerCallback(isPlaying = true, trackId = trackId)
    advanceUntilIdle()

    assertEquals(listOf(PlaybackEventName.PLAY_REQUESTED), recorder.events.map { it.name })
  }

  @Test
  fun playbackFailure_clearsPendingLatency() = runTest {
    val recorder = RecordingPlaybackEventRecorder()
    val analytics =
      PlaybackRequestAnalytics(
        recorder = recorder,
        scope = this,
        timingTracker = PlaybackTimingTracker { 100L },
        epochTimeMs = { 1_000L },
      )
    val trackId = TrackId("local:failed")

    analytics.onPlayRequested(trackId)
    analytics.onPlaybackFailure()
    analytics.onPlayerCallback(isPlaying = true, trackId = trackId)
    advanceUntilIdle()

    assertEquals(listOf(PlaybackEventName.PLAY_REQUESTED), recorder.events.map { it.name })
  }

  @Test
  fun recorderFailure_isIsolatedFromThePlaybackRequest() = runTest {
    val failure = IllegalStateException("database unavailable")
    val recorder = RecordingPlaybackEventRecorder().apply { failNextRecordWith = failure }
    var reportedFailure: Exception? = null
    val analytics =
      PlaybackRequestAnalytics(
        recorder = recorder,
        scope = this,
        timingTracker = PlaybackTimingTracker { 100L },
        epochTimeMs = { 1_000L },
        onRecordingFailure = { reportedFailure = it },
      )

    analytics.onPlayRequested(TrackId("local:one"))
    advanceUntilIdle()

    assertEquals(failure, reportedFailure)
  }
}

private class RecordingPlaybackEventRecorder : PlaybackEventRecorder {
  val events = mutableListOf<PlaybackEvent>()
  var suspendNextRecordUntil: CompletableDeferred<Unit>? = null
  var failNextRecordWith: Exception? = null

  override suspend fun record(event: PlaybackEvent) {
    events += event
    failNextRecordWith?.also { failure ->
      failNextRecordWith = null
      throw failure
    }
    suspendNextRecordUntil?.also { gate ->
      suspendNextRecordUntil = null
      gate.await()
    }
  }
}
