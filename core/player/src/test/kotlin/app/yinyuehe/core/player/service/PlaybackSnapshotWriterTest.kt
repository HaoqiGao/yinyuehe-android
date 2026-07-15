package app.yinyuehe.core.player.service

import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackSnapshot
import app.yinyuehe.core.common.playback.PlaybackSnapshotReadResult
import app.yinyuehe.core.common.playback.PlaybackSnapshotStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSnapshotWriterTest {
  @Test
  fun coalescedWritesKeepOnlyLatestAndNeverDelayPast250MsFromTheFirstValue() = runTest {
    val store = RecordingStore()
    val writer = writer(store, StandardTestDispatcher(testScheduler))

    writer.submit(snapshot("one"), SnapshotWriteUrgency.COALESCED)
    advanceTimeBy(249)
    runCurrent()
    assertTrue(store.writes.isEmpty())

    writer.submit(snapshot("two"), SnapshotWriteUrgency.COALESCED)
    runCurrent()
    assertTrue(store.writes.isEmpty())
    advanceTimeBy(1)
    runCurrent()

    assertEquals(listOf(snapshot("two")), store.writes)
    writer.close(null).join()
  }

  @Test
  fun staleSignalAfterLatestPendingIsTakenDoesNotEndTheOriginalCoalescingWindow() = runTest {
    val signalReceived = CompletableDeferred<Unit>()
    val allowPendingTake = CompletableDeferred<Unit>()
    var pauseBeforeTake = true
    val store = RecordingStore()
    val writer =
      writer(
        store = store,
        dispatcher = StandardTestDispatcher(testScheduler),
        beforePendingTake = {
          if (pauseBeforeTake) {
            pauseBeforeTake = false
            signalReceived.complete(Unit)
            allowPendingTake.await()
          }
        },
      )

    writer.submit(snapshot("one"), SnapshotWriteUrgency.COALESCED)
    runCurrent()
    assertTrue(signalReceived.isCompleted)
    writer.submit(snapshot("two"), SnapshotWriteUrgency.COALESCED)
    allowPendingTake.complete(Unit)
    runCurrent()

    assertTrue(store.writes.isEmpty())
    advanceTimeBy(249)
    runCurrent()
    assertTrue(store.writes.isEmpty())
    advanceTimeBy(1)
    runCurrent()

    assertEquals(listOf(snapshot("two")), store.writes)
    writer.close(null).join()
  }

  @Test
  fun immediateValueInterruptsAnOpenCoalescingWindow() = runTest {
    val store = RecordingStore()
    val writer = writer(store, StandardTestDispatcher(testScheduler))

    writer.submit(snapshot("coalesced"), SnapshotWriteUrgency.COALESCED)
    advanceTimeBy(100)
    writer.submit(snapshot("immediate"), SnapshotWriteUrgency.IMMEDIATE)
    runCurrent()

    assertEquals(listOf(snapshot("immediate")), store.writes)
    writer.close(null).join()
  }

  @Test
  fun inFlightWriteIsNotCancelledAndOnlyTheLatestPendingValueCommitsAfterIt() = runTest {
    val firstWriteGate = CompletableDeferred<Unit>()
    val store = RecordingStore(firstWriteGate)
    val writer = writer(store, StandardTestDispatcher(testScheduler))

    writer.submit(snapshot("one"), SnapshotWriteUrgency.IMMEDIATE)
    runCurrent()
    writer.submit(snapshot("two"), SnapshotWriteUrgency.IMMEDIATE)
    writer.submit(snapshot("three"), SnapshotWriteUrgency.IMMEDIATE)
    runCurrent()

    assertEquals(listOf(snapshot("one")), store.started)
    firstWriteGate.complete(Unit)
    advanceUntilIdle()

    assertEquals(listOf(snapshot("one"), snapshot("three")), store.writes)
    writer.close(null).join()
  }

  @Test
  fun failedWriteDoesNotDisableTheNextWrite() = runTest {
    val failure = IllegalStateException("synthetic write failure")
    val store = RecordingStore().apply { nextFailure = failure }
    val failures = mutableListOf<Exception>()
    val writer =
      writer(
        store = store,
        dispatcher = StandardTestDispatcher(testScheduler),
        onFailure = failures::add,
      )

    writer.submit(snapshot("one"), SnapshotWriteUrgency.IMMEDIATE)
    runCurrent()
    writer.submit(snapshot("two"), SnapshotWriteUrgency.IMMEDIATE)
    advanceUntilIdle()

    assertSame(failure, failures.single())
    assertEquals(listOf(snapshot("two")), store.writes)
    writer.close(null).join()
  }

  @Test
  fun cancellationFromTheStoreStopsTheActorWithoutBeingReportedAsAWriteFailure() = runTest {
    val cancellation = CancellationException("synthetic cancellation")
    val store = RecordingStore().apply { nextFailure = cancellation }
    val failures = mutableListOf<Exception>()
    val writer =
      writer(
        store = store,
        dispatcher = StandardTestDispatcher(testScheduler),
        onFailure = failures::add,
      )

    writer.submit(snapshot("cancelled"), SnapshotWriteUrgency.IMMEDIATE)
    runCurrent()
    writer.submit(snapshot("must-not-write"), SnapshotWriteUrgency.IMMEDIATE)
    runCurrent()

    assertTrue(failures.isEmpty())
    assertTrue(store.writes.isEmpty())
    writer.close(null).join()
  }

  @Test
  fun closeIsIdempotentPromotesFinalSnapshotAndRejectsLaterSubmissions() = runTest {
    val store = RecordingStore()
    val writer = writer(store, StandardTestDispatcher(testScheduler))
    writer.submit(snapshot("pending"), SnapshotWriteUrgency.COALESCED)

    val closeJob = writer.close(snapshot("final"))
    writer.submit(snapshot("too-late"), SnapshotWriteUrgency.IMMEDIATE)
    val duplicateCloseJob = writer.close(snapshot("other-final"))

    assertSame(closeJob, duplicateCloseJob)
    advanceUntilIdle()

    assertTrue(closeJob.isCompleted)
    assertEquals(listOf(snapshot("final")), store.writes)
  }

  @Test
  fun closeReturnsImmediatelyAndCancelsBlockedDrainAt1000Ms() = runTest {
    val never = CompletableDeferred<Unit>()
    val store = RecordingStore(never)
    val writer = writer(store, StandardTestDispatcher(testScheduler))
    writer.submit(snapshot("in-flight"), SnapshotWriteUrgency.IMMEDIATE)
    runCurrent()

    val closeJob = writer.close(snapshot("final"))
    assertFalse(closeJob.isCompleted)
    advanceTimeBy(999)
    runCurrent()
    assertFalse(closeJob.isCompleted)
    advanceTimeBy(1)
    runCurrent()

    assertTrue(closeJob.isCompleted)
    assertEquals(listOf(snapshot("in-flight")), store.started)
    assertTrue(store.writes.isEmpty())
  }

  private fun writer(
    store: PlaybackSnapshotStore,
    dispatcher: CoroutineDispatcher,
    onFailure: (Exception) -> Unit = {},
    beforePendingTake: suspend () -> Unit = {},
  ) =
    PlaybackSnapshotWriter(
      snapshotStore = store,
      dispatcher = dispatcher,
      coalesceWindowMs = 250,
      closeDrainTimeoutMs = 1_000,
      onFailure = onFailure,
      beforePendingTake = beforePendingTake,
    )
}

private class RecordingStore(
  private val firstWriteGate: CompletableDeferred<Unit>? = null,
) : PlaybackSnapshotStore {
  val started = mutableListOf<PlaybackSnapshot>()
  val writes = mutableListOf<PlaybackSnapshot>()
  var nextFailure: Exception? = null

  override suspend fun read(): PlaybackSnapshotReadResult =
    PlaybackSnapshotReadResult.Usable(PlaybackSnapshot.empty())

  override suspend fun write(snapshot: PlaybackSnapshot) {
    started += snapshot
    if (started.size == 1) firstWriteGate?.await()
    nextFailure?.also { failure ->
      nextFailure = null
      throw failure
    }
    writes += snapshot
  }
}

private fun snapshot(id: String) =
  PlaybackSnapshot(mediaIds = listOf(TrackId("demo:$id")), currentIndex = 0)
