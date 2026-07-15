package app.yinyuehe.core.player.service

import app.yinyuehe.core.common.playback.PlaybackSnapshot
import app.yinyuehe.core.common.playback.PlaybackSnapshotStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

internal enum class SnapshotWriteUrgency { COALESCED, IMMEDIATE }

internal class PlaybackSnapshotWriter(
  private val snapshotStore: PlaybackSnapshotStore,
  dispatcher: CoroutineDispatcher,
  private val coalesceWindowMs: Long = 250,
  private val closeDrainTimeoutMs: Long = 1_000,
  private val onFailure: (Exception) -> Unit = {},
) {
  private data class Pending(
    val snapshot: PlaybackSnapshot,
    val urgency: SnapshotWriteUrgency,
  )

  private val writerJob = SupervisorJob()
  private val writerScope = CoroutineScope(writerJob + dispatcher)
  private val signal = Channel<Unit>(Channel.CONFLATED)
  private val lock = Any()
  private var pending: Pending? = null
  private var closing = false
  private var closeJob: Job? = null
  private val actor = writerScope.launch { actorLoop() }

  fun submit(snapshot: PlaybackSnapshot, urgency: SnapshotWriteUrgency) {
    synchronized(lock) {
      if (closing) return
      pending = merge(pending, Pending(snapshot, urgency))
    }
    signal.trySend(Unit)
  }

  fun close(finalSnapshot: PlaybackSnapshot?): Job =
    synchronized(lock) {
      closeJob
        ?: run {
          closing = true
          if (finalSnapshot != null) {
            pending = merge(pending, Pending(finalSnapshot, SnapshotWriteUrgency.IMMEDIATE))
          }
          signal.trySend(Unit)
          writerScope
            .launch {
              try {
                withTimeoutOrNull(closeDrainTimeoutMs) { actor.join() }
              } finally {
                writerJob.cancel()
              }
            }
            .also { job -> closeJob = job }
        }
    }

  private suspend fun actorLoop() {
    while (true) {
      signal.receive()
      var next = takePending()
      if (next == null) {
        if (isClosingAndEmpty()) return
        continue
      }
      next = awaitCoalescing(next)
      writeSafely(next.snapshot)
      if (isClosingAndEmpty()) return
    }
  }

  private suspend fun awaitCoalescing(initial: Pending): Pending = coroutineScope {
    var latest = initial
    if (latest.urgency == SnapshotWriteUrgency.IMMEDIATE) return@coroutineScope latest
    val window = async { delay(coalesceWindowMs) }
    try {
      while (latest.urgency == SnapshotWriteUrgency.COALESCED) {
        val replacement =
          select<Pending?> {
            window.onAwait { null }
            signal.onReceive { takePending() }
          }
        if (replacement == null) break
        latest = merge(latest, replacement)
      }
      latest
    } finally {
      window.cancel()
    }
  }

  private fun takePending(): Pending? = synchronized(lock) { pending.also { pending = null } }

  private fun isClosingAndEmpty(): Boolean = synchronized(lock) { closing && pending == null }

  private suspend fun writeSafely(snapshot: PlaybackSnapshot) {
    try {
      snapshotStore.write(snapshot)
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (error: Exception) {
      onFailure(error)
    }
  }

  private fun merge(current: Pending?, newer: Pending): Pending =
    Pending(
      snapshot = newer.snapshot,
      urgency =
        if (
          current?.urgency == SnapshotWriteUrgency.IMMEDIATE ||
            newer.urgency == SnapshotWriteUrgency.IMMEDIATE
        ) {
          SnapshotWriteUrgency.IMMEDIATE
        } else {
          SnapshotWriteUrgency.COALESCED
        },
    )
}
