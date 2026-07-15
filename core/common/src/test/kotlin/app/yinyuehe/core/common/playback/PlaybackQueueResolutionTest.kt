package app.yinyuehe.core.common.playback

import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackQueueResolutionTest {
  private val demoTrack =
    Track(
      id = TrackId("demo:morning-pulse"),
      title = "Morning Pulse",
      artist = "Demo Band",
      album = "Compose Sessions",
      durationMs = 3_200,
      artworkUri = null,
      sourceUri = "android.resource://app.yinyuehe/1",
      isDemo = true,
    )

  @Test
  fun resolution_preservesOneOrderedResultPerOccurrence() {
    val localId = TrackId("local:v1:ZXh0ZXJuYWw:1")
    val resolution =
      PlaybackQueueResolution(
        items =
          listOf(
            PlaybackQueueItemResolution.Resolved(0, demoTrack.id, demoTrack),
            PlaybackQueueItemResolution.TemporarilyBlocked(
              originalIndex = 1,
              trackId = localId,
              reason = PlaybackQueueBlockReason.PERMISSION_DENIED,
            ),
            PlaybackQueueItemResolution.Resolved(2, demoTrack.id, demoTrack),
          ),
        temporaryBlockReason = PlaybackQueueBlockReason.PERMISSION_DENIED,
      )

    assertEquals(listOf(0, 1, 2), resolution.items.map { it.originalIndex })
    assertEquals(
      listOf(demoTrack.id, localId, demoTrack.id),
      resolution.items.map { it.trackId },
    )
  }

  @Test
  fun nonContiguousOriginalIndexes_areRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      PlaybackQueueResolution(
        items =
          listOf(
            PlaybackQueueItemResolution.Resolved(1, demoTrack.id, demoTrack),
          )
      )
    }
  }

  @Test
  fun resolvedTrackIdMustMatchOccurrenceTrackId() {
    assertThrows(IllegalArgumentException::class.java) {
      PlaybackQueueResolution(
        items =
          listOf(
            PlaybackQueueItemResolution.Resolved(
              originalIndex = 0,
              trackId = TrackId("demo:different"),
              track = demoTrack,
            )
          )
      )
    }
  }

  @Test
  fun blockedItemsRequireTheMatchingAggregateReason() {
    assertThrows(IllegalArgumentException::class.java) {
      PlaybackQueueResolution(
        items =
          listOf(
            PlaybackQueueItemResolution.TemporarilyBlocked(
              originalIndex = 0,
              trackId = TrackId("local:v1:ZXh0ZXJuYWw:1"),
              reason = PlaybackQueueBlockReason.PERMISSION_DENIED,
            )
          ),
        temporaryBlockReason = null,
      )
    }
  }
}
