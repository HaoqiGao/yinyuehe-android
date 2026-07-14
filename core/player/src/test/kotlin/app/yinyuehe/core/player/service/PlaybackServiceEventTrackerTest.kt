package app.yinyuehe.core.player.service

import app.yinyuehe.core.common.analytics.PlaybackEventName
import app.yinyuehe.core.common.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackServiceEventTrackerTest {
  @Test
  fun transitionStartAndEnd_eachProduceOnePerItemUpdate() {
    val tracker = PlaybackServiceEventTracker()
    val trackId = TrackId("local:one")

    assertEquals(
      PlaybackServiceUpdate(
        eventNames = listOf(PlaybackEventName.TRACK_CHANGED),
        trackId = trackId,
      ),
      tracker.onMediaItemTransition(trackId.value, isPlaying = false),
    )
    assertEquals(
      PlaybackServiceUpdate(
        eventNames = listOf(PlaybackEventName.PLAYBACK_STARTED),
        trackId = trackId,
        recordHistory = true,
      ),
      tracker.onIsPlayingChanged(isPlaying = true, mediaId = trackId.value),
    )
    assertNull(tracker.onIsPlayingChanged(isPlaying = false, mediaId = trackId.value))
    assertNull(tracker.onIsPlayingChanged(isPlaying = true, mediaId = trackId.value))
    assertEquals(
      PlaybackServiceUpdate(
        eventNames = listOf(PlaybackEventName.PLAYBACK_COMPLETED),
        trackId = trackId,
      ),
      tracker.onPlaybackEnded(trackId.value),
    )
    assertNull(tracker.onPlaybackEnded(trackId.value))
  }

  @Test
  fun repeatedTransitionToTheSameMediaId_startsANewItemLifecycle() {
    val tracker = PlaybackServiceEventTracker()
    val mediaId = "local:repeat"

    tracker.onMediaItemTransition(mediaId, isPlaying = false)
    tracker.onIsPlayingChanged(isPlaying = true, mediaId = mediaId)
    tracker.onPlaybackEnded(mediaId)

    val transition = tracker.onMediaItemTransition(mediaId, isPlaying = true)
    val duplicateStarted = tracker.onIsPlayingChanged(isPlaying = true, mediaId = mediaId)
    val completed = tracker.onPlaybackEnded(mediaId)

    assertEquals(
      listOf(PlaybackEventName.TRACK_CHANGED, PlaybackEventName.PLAYBACK_STARTED),
      transition?.eventNames,
    )
    assertEquals(true, transition?.recordHistory)
    assertNull(duplicateStarted)
    assertEquals(listOf(PlaybackEventName.PLAYBACK_COMPLETED), completed?.eventNames)
  }

  @Test
  fun firstPlayingCallback_withoutATransition_recordsHistoryAsAFallback() {
    val tracker = PlaybackServiceEventTracker()
    val trackId = TrackId("local:restored")

    assertEquals(
      PlaybackServiceUpdate(
        eventNames = listOf(PlaybackEventName.PLAYBACK_STARTED),
        trackId = trackId,
        recordHistory = true,
      ),
      tracker.onIsPlayingChanged(isPlaying = true, mediaId = trackId.value),
    )
    assertNull(tracker.onIsPlayingChanged(isPlaying = true, mediaId = trackId.value))
  }

  @Test
  fun playingTheSameItemAfterItEnded_startsANewOccurrence() {
    val tracker = PlaybackServiceEventTracker()
    val trackId = TrackId("local:replay")
    tracker.onMediaItemTransition(trackId.value, isPlaying = false)
    tracker.onIsPlayingChanged(isPlaying = true, mediaId = trackId.value)
    tracker.onPlaybackEnded(trackId.value)

    assertEquals(
      PlaybackServiceUpdate(
        eventNames = listOf(PlaybackEventName.PLAYBACK_STARTED),
        trackId = trackId,
        recordHistory = true,
      ),
      tracker.onIsPlayingChanged(isPlaying = true, mediaId = trackId.value),
    )
    assertNull(tracker.onIsPlayingChanged(isPlaying = true, mediaId = trackId.value))
  }
}
