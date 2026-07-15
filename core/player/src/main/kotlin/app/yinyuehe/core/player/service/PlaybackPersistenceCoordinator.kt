package app.yinyuehe.core.player.service

import app.yinyuehe.core.common.playback.PlaybackSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class PlaybackPersistenceCoordinator(
  private val gate: RestorePersistenceGate,
  private val writer: PlaybackSnapshotWriter,
  private val scope: CoroutineScope,
  private val capture: () -> PlaybackSnapshot,
  private val positionSampleIntervalMs: Long = 5_000,
) {
  private var isPlaying = false
  private var tickerJob: Job? = null

  fun onCoalescedChange() {
    submit(SnapshotWriteUrgency.COALESCED)
  }

  fun onImmediateChange() {
    submit(SnapshotWriteUrgency.IMMEDIATE)
  }

  fun onIsPlayingChanged(isPlaying: Boolean) {
    this.isPlaying = isPlaying
    if (isPlaying && gate.canPersist) {
      startTicker()
    } else {
      stopTicker()
      if (!isPlaying) onImmediateChange()
    }
  }

  fun onGateOpened() {
    check(gate.canPersist)
    onImmediateChange()
    if (isPlaying) startTicker()
  }

  fun close(): Job {
    stopTicker()
    val finalSnapshot = if (gate.canPersist) capture() else null
    return writer.close(finalSnapshot)
  }

  private fun submit(urgency: SnapshotWriteUrgency) {
    if (!gate.canPersist) return
    writer.submit(capture(), urgency)
  }

  private fun startTicker() {
    if (tickerJob?.isActive == true) return
    tickerJob =
      scope.launch {
        while (isActive && isPlaying) {
          delay(positionSampleIntervalMs)
          if (isPlaying) onImmediateChange()
        }
      }
  }

  private fun stopTicker() {
    tickerJob?.cancel()
    tickerJob = null
  }
}
