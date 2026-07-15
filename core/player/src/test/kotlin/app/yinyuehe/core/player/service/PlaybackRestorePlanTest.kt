package app.yinyuehe.core.player.service

import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackQueueBlockReason
import app.yinyuehe.core.common.playback.PlaybackQueueItemResolution
import app.yinyuehe.core.common.playback.PlaybackQueueResolution
import app.yinyuehe.core.common.playback.PlaybackRepeatMode
import app.yinyuehe.core.common.playback.PlaybackSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRestorePlanTest {
  @Test
  fun currentOccurrenceSurvives_preservesDuplicatesAndClampsKnownDuration() {
    val one = track("local:one", 1_000)
    val two = track("local:two", 400)
    val snapshot =
      PlaybackSnapshot(
        mediaIds = listOf(one.id, two.id, one.id),
        currentIndex = 1,
        positionMs = 900,
        shuffleEnabled = true,
        repeatMode = PlaybackRepeatMode.ALL,
      )
    val resolution =
      PlaybackQueueResolution(
        items =
          listOf(
            resolved(0, one),
            resolved(1, two),
            resolved(2, one),
          )
      )

    val plan = buildPlaybackRestorePlan(snapshot, resolution)

    assertEquals(listOf(one.id, two.id, one.id), plan.tracks.map(Track::id))
    assertEquals(1, plan.currentIndex)
    assertEquals(400L, plan.positionMs)
    assertEquals(true, plan.shuffleEnabled)
    assertEquals(PlaybackRepeatMode.ALL, plan.repeatMode)
    assertEquals(
      PlaybackSnapshot(
        mediaIds = listOf(one.id, two.id, one.id),
        currentIndex = 1,
        positionMs = 400,
        shuffleEnabled = true,
        repeatMode = PlaybackRepeatMode.ALL,
      ),
      plan.normalizedSnapshot,
    )
  }

  @Test
  fun removedPredecessor_repairsCurrentIndexAndPreservesUnknownDurationPosition() {
    val missing = TrackId("local:missing")
    val current = track("local:current", durationMs = null)
    val snapshot =
      PlaybackSnapshot(
        mediaIds = listOf(missing, current.id),
        currentIndex = 1,
        positionMs = 700,
      )
    val resolution =
      PlaybackQueueResolution(
        items =
          listOf(
            PlaybackQueueItemResolution.PermanentlyMissing(0, missing),
            resolved(1, current),
          )
      )

    val plan = buildPlaybackRestorePlan(snapshot, resolution)

    assertEquals(listOf(current), plan.tracks)
    assertEquals(0, plan.currentIndex)
    assertEquals(700L, plan.positionMs)
    assertEquals(0, plan.normalizedSnapshot.currentIndex)
    assertEquals(700L, plan.normalizedSnapshot.positionMs)
  }

  @Test
  fun currentOccurrenceMissing_prefersNextSurvivorAndResetsPosition() {
    val previous = track("local:previous", 1_000)
    val missing = TrackId("local:missing")
    val next = track("local:next", null)
    val snapshot =
      PlaybackSnapshot(
        mediaIds = listOf(previous.id, missing, next.id),
        currentIndex = 1,
        positionMs = 700,
      )
    val resolution =
      PlaybackQueueResolution(
        items =
          listOf(
            resolved(0, previous),
            PlaybackQueueItemResolution.PermanentlyMissing(1, missing),
            resolved(2, next),
          )
      )

    val plan = buildPlaybackRestorePlan(snapshot, resolution)

    assertEquals(listOf(previous.id, next.id), plan.tracks.map(Track::id))
    assertEquals(1, plan.currentIndex)
    assertEquals(0L, plan.positionMs)
  }

  @Test
  fun currentOccurrenceMissing_withoutSuccessorFallsBackToPredecessorAndResetsPosition() {
    val previous = track("local:previous", 1_000)
    val missing = TrackId("local:missing")
    val snapshot =
      PlaybackSnapshot(
        mediaIds = listOf(previous.id, missing),
        currentIndex = 1,
        positionMs = 700,
      )
    val resolution =
      PlaybackQueueResolution(
        items =
          listOf(
            resolved(0, previous),
            PlaybackQueueItemResolution.PermanentlyMissing(1, missing),
          )
      )

    val plan = buildPlaybackRestorePlan(snapshot, resolution)

    assertEquals(listOf(previous), plan.tracks)
    assertEquals(0, plan.currentIndex)
    assertEquals(0L, plan.positionMs)
  }

  @Test
  fun permissionBlockedOccurrences_stillProduceAPlanForTheSafeResolvedSubset() {
    val safeBefore = track("demo:safe-before", 2_000)
    val blocked = TrackId("local:hidden")
    val safeAfter = track("demo:safe-after", 2_000)
    val snapshot =
      PlaybackSnapshot(
        mediaIds = listOf(safeBefore.id, blocked, safeAfter.id),
        currentIndex = 1,
        positionMs = 800,
        shuffleEnabled = true,
        repeatMode = PlaybackRepeatMode.ONE,
      )
    val resolution =
      PlaybackQueueResolution(
        items =
          listOf(
            resolved(0, safeBefore),
            PlaybackQueueItemResolution.TemporarilyBlocked(
              originalIndex = 1,
              trackId = blocked,
              reason = PlaybackQueueBlockReason.PERMISSION_DENIED,
            ),
            resolved(2, safeAfter),
          ),
        temporaryBlockReason = PlaybackQueueBlockReason.PERMISSION_DENIED,
      )

    val plan = buildPlaybackRestorePlan(snapshot, resolution)

    assertEquals(listOf(safeBefore, safeAfter), plan.tracks)
    assertEquals(1, plan.currentIndex)
    assertEquals(0L, plan.positionMs)
    assertEquals(true, plan.shuffleEnabled)
    assertEquals(PlaybackRepeatMode.ONE, plan.repeatMode)
  }

  @Test
  fun everyOccurrenceMissing_producesCanonicalEmptySnapshot() {
    val first = TrackId("local:missing-one")
    val second = TrackId("local:missing-two")
    val plan =
      buildPlaybackRestorePlan(
        PlaybackSnapshot(
          mediaIds = listOf(first, second),
          currentIndex = 1,
          positionMs = 500,
          shuffleEnabled = true,
          repeatMode = PlaybackRepeatMode.ALL,
        ),
        PlaybackQueueResolution(
          items =
            listOf(
              PlaybackQueueItemResolution.PermanentlyMissing(0, first),
              PlaybackQueueItemResolution.PermanentlyMissing(1, second),
            )
        ),
      )

    assertEquals(emptyList<Track>(), plan.tracks)
    assertEquals(-1, plan.currentIndex)
    assertEquals(0L, plan.positionMs)
    assertEquals(false, plan.shuffleEnabled)
    assertEquals(PlaybackRepeatMode.OFF, plan.repeatMode)
    assertEquals(PlaybackSnapshot.empty(), plan.normalizedSnapshot)
  }
}

private fun resolved(index: Int, track: Track) =
  PlaybackQueueItemResolution.Resolved(index, track.id, track)

private fun track(id: String, durationMs: Long?) =
  Track(
    id = TrackId(id),
    title = id,
    artist = null,
    album = null,
    durationMs = durationMs,
    artworkUri = null,
    sourceUri = "content://media/$id",
    isDemo = id.startsWith("demo:"),
  )
