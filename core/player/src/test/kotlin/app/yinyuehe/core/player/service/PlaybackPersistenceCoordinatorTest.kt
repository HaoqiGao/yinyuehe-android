package app.yinyuehe.core.player.service

import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackSnapshot
import app.yinyuehe.core.common.playback.PlaybackSnapshotReadResult
import app.yinyuehe.core.common.playback.PlaybackSnapshotStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackPersistenceCoordinatorTest {
  @Test
  fun playingPositionIsSampledEvery5000MsAndPauseIsImmediate() = runTest {
    val gate = appliedGate()
    val store = SimpleRecordingStore()
    var position = 0L
    val coordinator =
      coordinator(
        gate = gate,
        store = store,
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
      ) { snapshotAt(position) }

    coordinator.onIsPlayingChanged(true)
    position = 4_999
    advanceTimeBy(4_999)
    runCurrent()
    assertTrue(store.writes.isEmpty())

    position = 5_000
    advanceTimeBy(1)
    runCurrent()
    assertEquals(listOf(5_000L), store.writes.map(PlaybackSnapshot::positionMs))

    position = 5_100
    coordinator.onIsPlayingChanged(false)
    runCurrent()
    assertEquals(listOf(5_000L, 5_100L), store.writes.map(PlaybackSnapshot::positionMs))

    advanceTimeBy(5_000)
    runCurrent()
    assertEquals(listOf(5_000L, 5_100L), store.writes.map(PlaybackSnapshot::positionMs))
    coordinator.close().join()
  }

  @Test
  fun openGateCallbacksCaptureSynchronouslyAndImmediateInterruptsCoalescing() = runTest {
    val gate = appliedGate()
    val store = SimpleRecordingStore()
    var position = 1L
    var captureCount = 0
    val coordinator =
      coordinator(
        gate = gate,
        store = store,
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
      ) {
        captureCount += 1
        snapshotAt(position)
      }

    coordinator.onCoalescedChange()
    assertEquals(1, captureCount)
    advanceTimeBy(100)
    position = 2
    coordinator.onImmediateChange()
    assertEquals(2, captureCount)
    runCurrent()

    assertEquals(listOf(2L), store.writes.map(PlaybackSnapshot::positionMs))
    coordinator.close().join()
  }

  @Test
  fun pendingGateSuppressesCallbacksTickerCaptureAndFinalSnapshot() = runTest {
    val gate = RestorePersistenceGate(ControllerIdentity("app.yinyuehe", 1))
    val store = SimpleRecordingStore()
    var captureCount = 0
    val coordinator =
      coordinator(
        gate = gate,
        store = store,
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
      ) {
        captureCount += 1
        snapshotAt(123)
      }

    coordinator.onCoalescedChange()
    coordinator.onImmediateChange()
    coordinator.onIsPlayingChanged(true)
    advanceTimeBy(5_000)
    val closeJob = coordinator.close()
    runCurrent()

    assertTrue(closeJob.isCompleted)
    assertTrue(store.writes.isEmpty())
    assertEquals(0, captureCount)
  }

  @Test
  fun incompatibleAndBothFailureGatesSuppressCallbacksCaptureAndFinalSnapshot() = runTest {
    val closedGates =
      listOf(
        incompatibleGate(),
        failedGate(RestoreFailureReason.TRANSIENT),
        failedGate(RestoreFailureReason.PERMISSION_DENIED),
      )

    closedGates.forEach { gate ->
      val store = SimpleRecordingStore()
      var captureCount = 0
      val coordinator =
        coordinator(
          gate = gate,
          store = store,
          scope = backgroundScope,
          dispatcher = StandardTestDispatcher(testScheduler),
        ) {
          captureCount += 1
          snapshotAt(456)
        }

      coordinator.onCoalescedChange()
      coordinator.onImmediateChange()
      coordinator.onIsPlayingChanged(true)
      advanceTimeBy(5_000)
      val closeJob = coordinator.close()
      runCurrent()

      assertTrue(closeJob.isCompleted)
      assertTrue(store.writes.isEmpty())
      assertEquals(0, captureCount)
    }
  }

  @Test
  fun openingGateWhilePlayingCapturesImmediatelyAndStartsOnlyOneTicker() = runTest {
    val gate = RestorePersistenceGate(ControllerIdentity("app.yinyuehe", 1))
    val store = SimpleRecordingStore()
    var position = 0L
    var captureCount = 0
    val coordinator =
      coordinator(
        gate = gate,
        store = store,
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
      ) {
        captureCount += 1
        snapshotAt(position)
      }

    coordinator.onIsPlayingChanged(true)
    advanceTimeBy(5_000)
    assertEquals(0, captureCount)

    check(gate.tryBeginRestoreApply(0, playerIsEmpty = true))
    check(finishApplied(gate))
    position = 10
    coordinator.onGateOpened()
    assertEquals(1, captureCount)
    runCurrent()
    assertEquals(listOf(10L), store.writes.map(PlaybackSnapshot::positionMs))

    coordinator.onIsPlayingChanged(true)
    coordinator.onIsPlayingChanged(true)
    position = 5_010
    advanceTimeBy(4_999)
    runCurrent()
    assertEquals(1, captureCount)
    advanceTimeBy(1)
    runCurrent()

    assertEquals(2, captureCount)
    assertEquals(listOf(10L, 5_010L), store.writes.map(PlaybackSnapshot::positionMs))
    coordinator.close().join()
  }

  @Test
  fun closeStopsTickerBeforeSynchronouslyCapturingTheFinalSnapshot() = runTest {
    val gate = appliedGate()
    val store = SimpleRecordingStore()
    var position = 0L
    var captureCount = 0
    val coordinator =
      coordinator(
        gate = gate,
        store = store,
        scope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
      ) {
        captureCount += 1
        snapshotAt(position)
      }

    coordinator.onIsPlayingChanged(true)
    advanceTimeBy(4_999)
    runCurrent()
    position = 4_999

    val closeJob = coordinator.close()
    assertEquals(1, captureCount)
    assertFalse(closeJob.isCompleted)
    runCurrent()

    assertTrue(closeJob.isCompleted)
    assertEquals(listOf(4_999L), store.writes.map(PlaybackSnapshot::positionMs))
    advanceTimeBy(5_000)
    runCurrent()
    assertEquals(1, captureCount)
  }

  private fun coordinator(
    gate: RestorePersistenceGate,
    store: PlaybackSnapshotStore,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    capture: () -> PlaybackSnapshot,
  ): PlaybackPersistenceCoordinator {
    val writer =
      PlaybackSnapshotWriter(
        snapshotStore = store,
        dispatcher = dispatcher,
        coalesceWindowMs = 250,
        closeDrainTimeoutMs = 1_000,
      )
    return PlaybackPersistenceCoordinator(
      gate = gate,
      writer = writer,
      scope = scope,
      capture = capture,
      positionSampleIntervalMs = 5_000,
    )
  }
}

private class SimpleRecordingStore : PlaybackSnapshotStore {
  val writes = mutableListOf<PlaybackSnapshot>()

  override suspend fun read(): PlaybackSnapshotReadResult =
    PlaybackSnapshotReadResult.Usable(PlaybackSnapshot.empty())

  override suspend fun write(snapshot: PlaybackSnapshot) {
    writes += snapshot
  }
}

private fun snapshotAt(positionMs: Long) =
  PlaybackSnapshot(
    mediaIds = listOf(TrackId("demo:one")),
    currentIndex = 0,
    positionMs = positionMs,
  )

private fun appliedGate(): RestorePersistenceGate =
  RestorePersistenceGate(ControllerIdentity("app.yinyuehe", 1)).apply {
    check(tryBeginRestoreApply(0, playerIsEmpty = true))
    check(finishApplied(0, PlayerQueueFingerprint(listOf("one"), listOf("demo:one"), 0)))
  }

private fun incompatibleGate(): RestorePersistenceGate =
  RestorePersistenceGate(ControllerIdentity("app.yinyuehe", 1)).apply {
    check(finishIncompatible(0))
  }

private fun failedGate(reason: RestoreFailureReason): RestorePersistenceGate =
  RestorePersistenceGate(ControllerIdentity("app.yinyuehe", 1)).apply {
    check(finishFailed(0, reason))
  }

private fun finishApplied(gate: RestorePersistenceGate): Boolean =
  gate.finishApplied(
    expectedGeneration = 0,
    appliedFingerprint = PlayerQueueFingerprint(listOf("one"), listOf("demo:one"), 0),
  )
