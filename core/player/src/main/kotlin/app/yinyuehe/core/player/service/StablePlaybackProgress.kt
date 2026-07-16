package app.yinyuehe.core.player.service

import kotlin.math.min

internal enum class StableProgressResult {
  TRACKING,
  STABLE,
  TARGET_CHANGED,
}

internal class StablePlaybackProgress(
  private val targetToken: PlaybackOccurrenceToken,
  private val stableThresholdMs: Long = 1_000,
  private val maxForwardStepMs: Long = 250,
) {
  private var previousPositionMs: Long? = null
  private var accumulatedMs: Long = 0

  fun sample(
    occurrence: QueueOccurrence?,
    positionMs: Long,
    isPlaying: Boolean,
  ): StableProgressResult {
    if (occurrence?.token != targetToken) return StableProgressResult.TARGET_CHANGED
    val previous = previousPositionMs
    previousPositionMs = positionMs
    if (isPlaying && previous != null) {
      accumulatedMs += min((positionMs - previous).coerceAtLeast(0), maxForwardStepMs)
    }
    return if (accumulatedMs >= stableThresholdMs) {
      StableProgressResult.STABLE
    } else {
      StableProgressResult.TRACKING
    }
  }
}
