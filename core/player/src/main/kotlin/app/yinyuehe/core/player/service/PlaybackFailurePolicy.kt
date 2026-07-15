package app.yinyuehe.core.player.service

import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackError

internal data class QueueOccurrence(
  val index: Int,
  val token: PlaybackOccurrenceToken,
  val trackId: TrackId?,
)

internal sealed interface FailureDecision {
  data class Skip(
    val error: PlaybackError,
    val targetIndex: Int,
    val targetToken: PlaybackOccurrenceToken,
    val resumePlayback: Boolean,
  ) : FailureDecision

  data class Stop(val error: PlaybackError) : FailureDecision
}

internal class PlaybackFailurePolicy {
  private val attempted = linkedSetOf<PlaybackOccurrenceToken>()

  fun onFailure(
    error: PlaybackError,
    failed: QueueOccurrence,
    candidatesInPlaybackOrder: List<QueueOccurrence>,
    playIntent: Boolean,
  ): FailureDecision {
    attempted += failed.token
    val target = candidatesInPlaybackOrder.firstOrNull { it.token !in attempted }
    return target?.let { FailureDecision.Skip(error, it.index, it.token, playIntent) }
      ?: FailureDecision.Stop(error)
  }

  fun onStablePlayback(token: PlaybackOccurrenceToken) {
    if (token !in attempted) attempted.clear()
  }

  fun onPlaybackEnded() = attempted.clear()

  fun onUserRetry() = attempted.clear()
}
