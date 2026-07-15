package app.yinyuehe.core.common.playback

import app.yinyuehe.core.common.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackSnapshotTest {
  @Test
  fun emptySnapshot_hasCurrentSchemaAndNoCurrentItem() {
    assertEquals(
      PlaybackSnapshot(
        schemaVersion = 1,
        mediaIds = emptyList(),
        currentIndex = -1,
        positionMs = 0,
        shuffleEnabled = false,
        repeatMode = PlaybackRepeatMode.OFF,
      ),
      PlaybackSnapshot.empty(),
    )
  }

  @Test
  fun duplicateTrackIds_areValidOccurrences() {
    val id = TrackId("demo:morning-pulse")

    val snapshot =
      PlaybackSnapshot(
        mediaIds = listOf(id, id),
        currentIndex = 1,
        positionMs = 250,
        shuffleEnabled = true,
        repeatMode = PlaybackRepeatMode.ALL,
      )

    assertEquals(listOf(id, id), snapshot.mediaIds)
    assertEquals(1, snapshot.currentIndex)
  }

  @Test
  fun emptyQueue_rejectsAnyCurrentIndexOtherThanMinusOne() {
    assertThrows(IllegalArgumentException::class.java) {
      PlaybackSnapshot(mediaIds = emptyList(), currentIndex = 0)
    }
  }

  @Test
  fun nonEmptyQueue_rejectsOutOfBoundsCurrentIndex() {
    assertThrows(IllegalArgumentException::class.java) {
      PlaybackSnapshot(
        mediaIds = listOf(TrackId("local:v1:ZXh0ZXJuYWw:1")),
        currentIndex = 1,
      )
    }
  }

  @Test
  fun negativePosition_isRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      PlaybackSnapshot(
        mediaIds = listOf(TrackId("demo:city-walk")),
        currentIndex = 0,
        positionMs = -1,
      )
    }
  }

  @Test
  fun nonCurrentSchema_isRejectedFromUsableDomainSnapshot() {
    assertThrows(IllegalArgumentException::class.java) {
      PlaybackSnapshot(schemaVersion = 2)
    }
  }
}
