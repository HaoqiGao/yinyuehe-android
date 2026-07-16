package app.yinyuehe.core.player.service

import org.junit.Assert.assertEquals
import org.junit.Test

class StablePlaybackProgressTest {
  private val target = stableOccurrence(1, 2, "local:target")

  @Test
  fun requiresOneThousandMillisecondsOfBoundedForwardProgressOnTheTarget() {
    val progress = StablePlaybackProgress(target.token)

    assertEquals(StableProgressResult.TRACKING, progress.sample(target, 0, true))
    assertEquals(StableProgressResult.TRACKING, progress.sample(target, 250, true))
    assertEquals(StableProgressResult.TRACKING, progress.sample(target, 500, true))
    assertEquals(StableProgressResult.TRACKING, progress.sample(target, 750, true))
    assertEquals(StableProgressResult.TRACKING, progress.sample(target, 999, true))
    assertEquals(StableProgressResult.STABLE, progress.sample(target, 1_000, true))
  }

  @Test
  fun pauseSeekAndDifferentOccurrenceCannotCreateAFalseStableReset() {
    val progress = StablePlaybackProgress(target.token)
    assertEquals(StableProgressResult.TRACKING, progress.sample(target, 0, true))
    assertEquals(StableProgressResult.TRACKING, progress.sample(target, 10_000, false))
    assertEquals(
      StableProgressResult.TARGET_CHANGED,
      progress.sample(stableOccurrence(0, 1, "local:old"), 10_250, true),
    )
  }
}

private fun stableOccurrence(index: Int, token: Long, id: String) =
  QueueOccurrence(
    index,
    PlaybackOccurrenceToken(token),
    app.yinyuehe.core.common.model.TrackId(id),
  )
