package app.yinyuehe.core.player.service

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import app.yinyuehe.core.common.playback.PlaybackNotice
import app.yinyuehe.core.player.playbackError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class PlaybackFailureCoordinator(
  private val player: Player,
  private val tokens: PlaybackOccurrenceTokens,
  private val policy: PlaybackFailurePolicy,
  private val scope: CoroutineScope,
  private val onNotice: (PlaybackNotice.TrackSkipped) -> Unit,
  private val sampleIntervalMs: Long = 250,
  private val onStablePlayback: (PlaybackOccurrenceToken) -> Unit = policy::onStablePlayback,
) {
  private var stabilityJob: Job? = null

  fun onPlayerError(exception: PlaybackException) {
    stabilityJob?.cancel()
    val failed = player.currentOccurrence(tokens) ?: return
    val error = playbackError(exception.errorCode, failed.trackId)
    when (
      val decision =
        policy.onFailure(error, failed, player.failureCandidates(tokens), player.playWhenReady)
    ) {
      is FailureDecision.Skip -> {
        player.seekToDefaultPosition(decision.targetIndex)
        player.prepare()
        if (decision.resumePlayback) player.play() else player.pause()
        onNotice(PlaybackNotice.TrackSkipped(decision.error))
        trackStableTarget(decision.targetToken)
      }
      is FailureDecision.Stop -> player.pause()
    }
  }

  fun onPlaybackEnded() {
    stabilityJob?.cancel()
    policy.onPlaybackEnded()
  }

  fun onUserRetry() {
    stabilityJob?.cancel()
    policy.onUserRetry()
  }

  fun close() {
    stabilityJob?.cancel()
    stabilityJob = null
  }

  internal fun trackStableTarget(targetToken: PlaybackOccurrenceToken) {
    val progress =
      StablePlaybackProgress(
        targetToken = targetToken,
        stableThresholdMs = STABLE_PLAYBACK_MS,
        maxForwardStepMs = sampleIntervalMs,
      )
    stabilityJob =
      scope.launch {
        while (isActive) {
          delay(sampleIntervalMs)
          when (
            progress.sample(
              occurrence = player.currentOccurrence(tokens),
              positionMs = player.currentPosition,
              isPlaying = player.isPlaying,
            )
          ) {
            StableProgressResult.TRACKING -> Unit
            StableProgressResult.TARGET_CHANGED -> return@launch
            StableProgressResult.STABLE -> {
              onStablePlayback(targetToken)
              return@launch
            }
          }
        }
      }
  }

  private companion object {
    const val STABLE_PLAYBACK_MS = 1_000L
  }
}
