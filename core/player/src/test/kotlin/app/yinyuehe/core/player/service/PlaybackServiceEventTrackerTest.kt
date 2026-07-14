package app.yinyuehe.core.player.service

import androidx.media3.common.Player
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
      listOf(
        PlaybackServiceUpdate(
          eventNames = listOf(PlaybackEventName.TRACK_CHANGED),
          trackId = trackId,
        )
      ),
      tracker.onMediaItemTransition(
        trackId.value,
        isPlaying = false,
        reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
      ),
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
  fun repeatTransition_completesTheOldOccurrenceBeforeStartingTheNewOne() {
    val tracker = PlaybackServiceEventTracker()
    val mediaId = "local:repeat"

    tracker.onMediaItemTransition(
      mediaId,
      isPlaying = false,
      reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
    )
    tracker.onIsPlayingChanged(isPlaying = true, mediaId = mediaId)

    val transition =
      tracker.onMediaItemTransition(
        mediaId,
        isPlaying = true,
        reason = Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
      )
    val duplicateStarted = tracker.onIsPlayingChanged(isPlaying = true, mediaId = mediaId)
    val completed = tracker.onPlaybackEnded(mediaId)

    assertEquals(
      listOf(
        PlaybackServiceUpdate(
          eventNames = listOf(PlaybackEventName.PLAYBACK_COMPLETED),
          trackId = TrackId(mediaId),
        ),
        PlaybackServiceUpdate(
          eventNames = listOf(PlaybackEventName.TRACK_CHANGED, PlaybackEventName.PLAYBACK_STARTED),
          trackId = TrackId(mediaId),
          recordHistory = true,
        ),
      ),
      transition,
    )
    assertNull(duplicateStarted)
    assertEquals(listOf(PlaybackEventName.PLAYBACK_COMPLETED), completed?.eventNames)
  }

  @Test
  fun autoTransition_completesPreviousTrackBeforeStartingTheNextTrack() {
    val tracker = PlaybackServiceEventTracker()
    val previous = TrackId("local:previous")
    val next = TrackId("local:next")
    tracker.onMediaItemTransition(
      previous.value,
      isPlaying = false,
      reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
    )
    tracker.onIsPlayingChanged(isPlaying = true, mediaId = previous.value)

    val updates =
      tracker.onMediaItemTransition(
        next.value,
        isPlaying = true,
        reason = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
      )

    assertEquals(
      listOf(
        PlaybackServiceUpdate(
          eventNames = listOf(PlaybackEventName.PLAYBACK_COMPLETED),
          trackId = previous,
        ),
        PlaybackServiceUpdate(
          eventNames = listOf(PlaybackEventName.TRACK_CHANGED, PlaybackEventName.PLAYBACK_STARTED),
          trackId = next,
          recordHistory = true,
        ),
      ),
      updates,
    )
  }

  @Test
  fun manualTransitions_doNotCompleteThePreviousTrack() {
    listOf(
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK to "local:previous",
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED to "local:previous",
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK to "local:next",
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED to null,
      )
      .forEach { (reason, nextMediaId) ->
        val tracker = PlaybackServiceEventTracker()
        val previous = TrackId("local:previous")
        tracker.onMediaItemTransition(
          previous.value,
          isPlaying = false,
          reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
        )
        tracker.onIsPlayingChanged(isPlaying = true, mediaId = previous.value)

        assertEquals(
          nextMediaId?.let { mediaId ->
            listOf(
              PlaybackServiceUpdate(
                eventNames =
                  listOf(PlaybackEventName.TRACK_CHANGED, PlaybackEventName.PLAYBACK_STARTED),
                trackId = TrackId(mediaId),
                recordHistory = true,
              )
            )
          } ?: emptyList<PlaybackServiceUpdate>(),
          tracker.onMediaItemTransition(nextMediaId, isPlaying = true, reason = reason),
        )
      }
  }

  @Test
  fun naturalTransition_doesNotCompleteAnOccurrenceThatNeverStarted() {
    val tracker = PlaybackServiceEventTracker()
    tracker.onMediaItemTransition(
      mediaId = "local:previous",
      isPlaying = false,
      reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
    )

    assertEquals(
      listOf(
        PlaybackServiceUpdate(
          eventNames = listOf(PlaybackEventName.TRACK_CHANGED, PlaybackEventName.PLAYBACK_STARTED),
          trackId = TrackId("local:next"),
          recordHistory = true,
        )
      ),
      tracker.onMediaItemTransition(
        mediaId = "local:next",
        isPlaying = true,
        reason = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
      ),
    )
  }

  @Test
  fun playbackEnded_doesNotCompleteAnOccurrenceThatNeverStarted() {
    val tracker = PlaybackServiceEventTracker()
    tracker.onMediaItemTransition(
      mediaId = "local:not-started",
      isPlaying = false,
      reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
    )

    assertNull(tracker.onPlaybackEnded("local:not-started"))
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
    tracker.onMediaItemTransition(
      trackId.value,
      isPlaying = false,
      reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
    )
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
