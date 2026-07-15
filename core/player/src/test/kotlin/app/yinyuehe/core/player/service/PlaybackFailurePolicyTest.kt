package app.yinyuehe.core.player.service

import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackError
import app.yinyuehe.core.common.playback.PlaybackErrorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFailurePolicyTest {
  private val error =
    PlaybackError(PlaybackErrorType.SOURCE_UNAVAILABLE, 2005, TrackId("local:duplicate"))

  @Test
  fun duplicateTrackIdsAreAttemptedByOccurrenceTokenAndThenStopBoundedly() {
    val first = occurrence(0, 1, "local:duplicate")
    val second = occurrence(1, 2, "local:duplicate")
    val policy = PlaybackFailurePolicy()

    assertEquals(
      FailureDecision.Skip(
        error,
        targetIndex = 1,
        targetToken = second.token,
        resumePlayback = true,
      ),
      policy.onFailure(error, first, listOf(second), playIntent = true),
    )
    assertEquals(
      FailureDecision.Stop(error),
      policy.onFailure(error, second, listOf(first), playIntent = true),
    )
  }

  @Test
  fun stablePlaybackOnTheSkippedTargetStartsANewFailureRound() {
    val first = occurrence(0, 1, "local:one")
    val second = occurrence(1, 2, "local:two")
    val policy = PlaybackFailurePolicy()

    assertEquals(
      FailureDecision.Skip(
        error,
        targetIndex = 1,
        targetToken = second.token,
        resumePlayback = true,
      ),
      policy.onFailure(error, first, listOf(second), playIntent = true),
    )
    policy.onStablePlayback(second.token)
    assertEquals(
      FailureDecision.Skip(
        error,
        targetIndex = 0,
        targetToken = first.token,
        resumePlayback = true,
      ),
      policy.onFailure(error, second, listOf(first), playIntent = true),
    )
  }

  @Test
  fun naturalEndAndExplicitRetryEachClearTheAttemptedOccurrences() {
    val first = occurrence(0, 1, "local:one")
    val second = occurrence(1, 2, "local:two")
    val policy = PlaybackFailurePolicy()

    policy.onFailure(error, first, listOf(second), playIntent = true)
    policy.onPlaybackEnded()
    assertTrue(policy.onFailure(error, second, listOf(first), true) is FailureDecision.Skip)

    policy.onFailure(error, first, listOf(second), playIntent = true)
    policy.onUserRetry()
    assertTrue(policy.onFailure(error, second, listOf(first), true) is FailureDecision.Skip)
  }
}

private fun occurrence(index: Int, token: Long, id: String) =
  QueueOccurrence(index, PlaybackOccurrenceToken(token), TrackId(id))
