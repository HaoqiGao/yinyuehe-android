package app.yinyuehe.core.player

import app.yinyuehe.core.common.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackTimingTrackerTest {
  @Test
  fun requestedTrack_emitsLatencyOnlyForItsFirstPlayingCallback() {
    var nowMs = 100L
    val tracker = PlaybackTimingTracker { nowMs }
    val requested = TrackId("local:requested")

    tracker.onPlayRequested(requested)
    nowMs = 460L

    assertEquals(360L, tracker.onPlaybackStarted(requested))
    assertNull(tracker.onPlaybackStarted(requested))
  }

  @Test
  fun anotherTrackPlaying_doesNotConsumeThePendingRequest() {
    var nowMs = 1_000L
    val tracker = PlaybackTimingTracker { nowMs }
    val requested = TrackId("local:requested")

    tracker.onPlayRequested(requested)
    nowMs = 1_100L
    assertNull(tracker.onPlaybackStarted(TrackId("local:other")))
    nowMs = 1_250L

    assertEquals(250L, tracker.onPlaybackStarted(requested))
  }

  @Test
  fun newerRequest_replacesTheOlderPendingMeasurement() {
    var nowMs = 10L
    val tracker = PlaybackTimingTracker { nowMs }
    val older = TrackId("local:older")
    val newer = TrackId("local:newer")

    tracker.onPlayRequested(older)
    nowMs = 20L
    tracker.onPlayRequested(newer)
    nowMs = 45L

    assertNull(tracker.onPlaybackStarted(older))
    assertEquals(25L, tracker.onPlaybackStarted(newer))
  }
}
