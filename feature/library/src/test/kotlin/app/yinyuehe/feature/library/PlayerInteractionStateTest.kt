package app.yinyuehe.feature.library

import app.yinyuehe.core.common.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerInteractionStateTest {
  @Test
  fun playbackTicksDoNotMoveActiveDrag_andMultipleChangesCommitOnce() {
    var state = PlayerSeekState(displayedPositionMs = 100)

    state = state.onDrag(300)
    state = state.onDrag(700)
    state = state.onPlaybackPosition(150)

    assertTrue(state.isDragging)
    assertEquals(700, state.displayedPositionMs)

    val commit = state.finishDrag()
    assertEquals(700L, commit.positionMs)
    assertFalse(commit.state.isDragging)
    assertNull(commit.state.finishDrag().positionMs)
  }

  @Test
  fun queueKeysUsePerTrackOccurrenceAndSurviveRemovalOfDifferentPrecedingTrack() {
    val a = TrackId("a")
    val b = TrackId("b")

    val before = playerQueueEntries(listOf(b, a, a))
    val after = playerQueueEntries(listOf(a, a))

    assertEquals(listOf("b#0", "a#0", "a#1"), before.map { it.key })
    assertEquals(listOf("a#0", "a#1"), after.map { it.key })
    assertEquals(before.drop(1).map { it.key }, after.map { it.key })
  }
}
