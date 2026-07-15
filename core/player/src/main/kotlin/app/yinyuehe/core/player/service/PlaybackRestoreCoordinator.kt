package app.yinyuehe.core.player.service

import app.yinyuehe.core.common.playback.PlaybackQueueBlockReason
import app.yinyuehe.core.common.playback.PlaybackQueueResolver
import app.yinyuehe.core.common.playback.PlaybackSnapshot
import app.yinyuehe.core.common.playback.PlaybackSnapshotReadResult
import app.yinyuehe.core.common.playback.PlaybackSnapshotStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PlaybackRestoreCoordinator(
  private val snapshotStore: PlaybackSnapshotStore,
  private val queueResolver: PlaybackQueueResolver,
  private val gate: RestorePersistenceGate,
  private val player: RestorablePlayer,
  private val scope: CoroutineScope,
  private val ioDispatcher: CoroutineDispatcher,
  private val onNormalizedSnapshot: (PlaybackSnapshot) -> Unit,
  private val onGateChanged: () -> Unit,
  private val onFailure: (Exception) -> Unit,
) {
  private var restoreJob: Job? = null

  fun start(): Job {
    restoreJob?.let { job -> return job }
    val expectedGeneration = gate.mutationGeneration
    return scope
      .launch {
        try {
          when (val read = withContext(ioDispatcher) { snapshotStore.read() }) {
            is PlaybackSnapshotReadResult.IncompatibleVersion -> {
              if (gate.finishIncompatible(expectedGeneration)) onGateChanged()
            }
            is PlaybackSnapshotReadResult.Usable -> {
              restoreUsable(read.snapshot, expectedGeneration)
            }
          }
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (error: Exception) {
          if (gate.finishFailed(expectedGeneration, RestoreFailureReason.TRANSIENT)) {
            onGateChanged()
          }
          onFailure(error)
        }
      }
      .also { job -> restoreJob = job }
  }

  fun cancel() {
    restoreJob?.cancel()
    restoreJob = null
  }

  private suspend fun restoreUsable(
    snapshot: PlaybackSnapshot,
    expectedGeneration: Long,
  ) {
    val resolution = withContext(ioDispatcher) { queueResolver.resolve(snapshot.mediaIds) }
    val plan = buildPlaybackRestorePlan(snapshot, resolution)
    if (!gate.tryBeginRestoreApply(expectedGeneration, player.isQueueEmpty)) return

    try {
      player.apply(plan)
      val fingerprint = player.queueFingerprint()
      if (resolution.temporaryBlockReason == PlaybackQueueBlockReason.PERMISSION_DENIED) {
        if (
          gate.finishFailed(
            expectedGeneration = expectedGeneration,
            reason = RestoreFailureReason.PERMISSION_DENIED,
            appliedFingerprint = fingerprint,
          )
        ) {
          onGateChanged()
        }
      } else if (gate.finishApplied(expectedGeneration, fingerprint)) {
        onNormalizedSnapshot(plan.normalizedSnapshot)
        onGateChanged()
      }
    } catch (cancellation: CancellationException) {
      if (gate.abortApply(expectedGeneration)) onGateChanged()
      throw cancellation
    } catch (error: Exception) {
      if (gate.abortApply(expectedGeneration)) onGateChanged()
      onFailure(error)
    }
  }
}
