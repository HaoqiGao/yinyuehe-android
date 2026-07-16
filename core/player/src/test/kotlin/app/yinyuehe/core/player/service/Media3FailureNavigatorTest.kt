package app.yinyuehe.core.player.service

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class Media3FailureNavigatorTest {
  @Test
  fun candidateTraversalKeepsShuffleOrderAndStopsBeforeASecondWrap() {
    val next = mapOf(0 to 2, 2 to 1, 1 to 0)

    assertEquals(
      listOf(2, 1),
      boundedCandidateIndices(startIndex = 0, itemCount = 3) { index ->
        next[index] ?: C.INDEX_UNSET
      },
    )
  }

  @Test
  fun noSuccessorStopsWithoutReturningTheFailedOccurrence() {
    assertEquals(
      emptyList<Int>(),
      boundedCandidateIndices(startIndex = 0, itemCount = 3) { C.INDEX_UNSET },
    )
  }
}
