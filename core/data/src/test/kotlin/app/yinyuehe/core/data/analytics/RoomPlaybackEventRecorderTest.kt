package app.yinyuehe.core.data.analytics

import app.yinyuehe.core.common.analytics.PlaybackEvent
import app.yinyuehe.core.common.analytics.PlaybackEventName
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.data.local.db.RoomDatabaseRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomPlaybackEventRecorderTest {
  @get:Rule val databaseRule = RoomDatabaseRule()

  private val recorder
    get() = RoomPlaybackEventRecorder(databaseRule.database.playbackEventDao())

  @Test
  fun events_areExposedNewestFirstWithTypedValues() = runTest {
    recorder.record(
      PlaybackEvent(
        name = PlaybackEventName.PLAY_REQUESTED,
        trackId = TrackId("local:one"),
        occurredAtEpochMs = 10,
      )
    )
    recorder.record(
      PlaybackEvent(
        name = PlaybackEventName.PLAY_START_LATENCY,
        trackId = TrackId("local:one"),
        occurredAtEpochMs = 20,
        durationMs = 325,
      )
    )

    val events = recorder.observeEvents().first()

    assertEquals(
      listOf(PlaybackEventName.PLAY_START_LATENCY, PlaybackEventName.PLAY_REQUESTED),
      events.map { it.name },
    )
    assertEquals(TrackId("local:one"), events.first().trackId)
    assertEquals(325L, events.first().durationMs)
  }

  @Test
  fun recordingTheFiveHundredAndFirstEvent_trimsOnlyTheOldestRow() = runTest {
    repeat(501) { index ->
      recorder.record(
        PlaybackEvent(
          name = PlaybackEventName.TRACK_CHANGED,
          occurredAtEpochMs = index.toLong(),
        )
      )
    }

    val events = recorder.observeEvents().first()

    assertEquals(500, events.size)
    assertEquals(500L, events.first().occurredAtEpochMs)
    assertEquals(1L, events.last().occurredAtEpochMs)
  }
}
