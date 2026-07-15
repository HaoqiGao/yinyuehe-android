package app.yinyuehe.core.player.service

import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackQueueBlockReason
import app.yinyuehe.core.common.playback.PlaybackQueueItemResolution
import app.yinyuehe.core.common.playback.PlaybackQueueResolution
import app.yinyuehe.core.common.playback.PlaybackQueueResolver
import app.yinyuehe.core.common.playback.PlaybackSnapshot
import app.yinyuehe.core.common.playback.PlaybackSnapshotReadResult
import app.yinyuehe.core.common.playback.PlaybackSnapshotStore
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRestoreCoordinatorTest {
  @Test
  fun slowReadCannotOverwriteConfirmedUserQueue() = runTest {
    val read = CompletableDeferred<PlaybackSnapshotReadResult>()
    val store = SuspendingSnapshotStore(read)
    val stored = coordinatorTrack("demo:stored")
    val gate = gate()
    val player = RecordingRestorablePlayer()
    val failures = mutableListOf<Exception>()
    val coordinator =
      coordinator(
        store,
        FixedResolver(resolutionOf(stored)),
        gate,
        player,
        failures = failures,
      )
    coordinator.start()
    runCurrent()

    gate.onConfirmedTimeline(
      PlayerQueueFingerprint(listOf("user"), listOf("demo:user"), currentIndex = 0)
    )
    read.complete(usableSnapshot(stored.id.value))
    runCurrent()

    assertTrue(player.plans.isEmpty())
    assertTrue(failures.isEmpty())
    assertEquals(RestoreGateStatus.SUPERSEDED, gate.status)
    assertEquals(1L, gate.mutationGeneration)
  }

  @Test
  fun slowResolutionCannotOverwriteConfirmedUserQueue() = runTest {
    val stored = coordinatorTrack("demo:stored")
    val resolution = CompletableDeferred<PlaybackQueueResolution>()
    val gate = gate()
    val player = RecordingRestorablePlayer()
    val coordinator =
      coordinator(
        ImmediateSnapshotStore(usableSnapshot(stored.id.value)),
        SuspendingResolver(resolution),
        gate,
        player,
      )
    coordinator.start()
    runCurrent()

    gate.onConfirmedTimeline(
      PlayerQueueFingerprint(listOf("user"), listOf("demo:user"), currentIndex = 0)
    )
    resolution.complete(resolutionOf(stored))
    runCurrent()

    assertTrue(player.plans.isEmpty())
    assertEquals(RestoreGateStatus.SUPERSEDED, gate.status)
  }

  @Test
  fun readAndResolutionUseIoDispatcher_thenApplicationReturnsToPlayerScope() = runTest {
    val lane = ThreadLocal<String?>()
    val playerDispatcher = LaneDispatcher("player", testScheduler, lane)
    val ioDispatcher = LaneDispatcher("io", testScheduler, lane)
    val observed = mutableListOf<String?>()
    val track = coordinatorTrack("demo:stored")
    val store =
      InspectingSnapshotStore {
        observed += lane.get()
        usableSnapshot(track.id.value)
      }
    val resolver =
      InspectingResolver {
        observed += lane.get()
        resolutionOf(track)
      }
    val player = RecordingRestorablePlayer(onApply = { observed += lane.get() })
    val normalized = mutableListOf<PlaybackSnapshot>()
    val gate = gate()
    val coordinator =
      PlaybackRestoreCoordinator(
        snapshotStore = store,
        queueResolver = resolver,
        gate = gate,
        player = player,
        scope = CoroutineScope(backgroundScope.coroutineContext + playerDispatcher),
        ioDispatcher = ioDispatcher,
        onNormalizedSnapshot = normalized::add,
        onGateChanged = {},
        onFailure = { throw AssertionError(it) },
      )

    coordinator.start()
    runCurrent()

    assertEquals(listOf("io", "io", "player"), observed)
    assertEquals(RestoreGateStatus.APPLIED, gate.status)
    assertEquals(listOf(track.id), normalized.single().mediaIds)
  }

  @Test
  fun permissionLimitedResolutionAppliesSafeSubsetWithoutOpeningWrites() = runTest {
    val stored = TrackId("local:hidden")
    val demo = coordinatorTrack("demo:safe")
    val snapshot =
      PlaybackSnapshot(mediaIds = listOf(demo.id, stored), currentIndex = 0)
    val resolution =
      PlaybackQueueResolution(
        items =
          listOf(
            PlaybackQueueItemResolution.Resolved(0, demo.id, demo),
            PlaybackQueueItemResolution.TemporarilyBlocked(
              1,
              stored,
              PlaybackQueueBlockReason.PERMISSION_DENIED,
            ),
          ),
        temporaryBlockReason = PlaybackQueueBlockReason.PERMISSION_DENIED,
      )
    val gate = gate()
    val player = RecordingRestorablePlayer()
    val normalized = mutableListOf<PlaybackSnapshot>()
    var gateChanges = 0
    val coordinator =
      coordinator(
        store = ImmediateSnapshotStore(PlaybackSnapshotReadResult.Usable(snapshot)),
        resolver = FixedResolver(resolution),
        gate = gate,
        player = player,
        normalized = normalized,
        onGateChanged = { gateChanges += 1 },
      )

    coordinator.start()
    runCurrent()

    assertEquals(listOf(demo.id), player.plans.single().tracks.map(Track::id))
    assertEquals(RestoreGateStatus.FAILED, gate.status)
    assertEquals(RestoreFailureReason.PERMISSION_DENIED, gate.failureReason)
    assertTrue(gate.queuePersistenceLimited)
    assertFalse(gate.canPersist)
    assertTrue(normalized.isEmpty())
    assertEquals(1, gateChanges)
  }

  @Test
  fun everyPermanentlyMissingItem_appliesAndNormalizesCanonicalEmptySnapshot() = runTest {
    val missing = TrackId("local:missing")
    val snapshot =
      PlaybackSnapshot(mediaIds = listOf(missing), currentIndex = 0, positionMs = 900)
    val resolution =
      PlaybackQueueResolution(
        listOf(PlaybackQueueItemResolution.PermanentlyMissing(0, missing))
      )
    val gate = gate()
    val player = RecordingRestorablePlayer()
    val normalized = mutableListOf<PlaybackSnapshot>()

    coordinator(
        store = ImmediateSnapshotStore(PlaybackSnapshotReadResult.Usable(snapshot)),
        resolver = FixedResolver(resolution),
        gate = gate,
        player = player,
        normalized = normalized,
      )
      .start()
    runCurrent()

    assertEquals(emptyList<Track>(), player.plans.single().tracks)
    assertEquals(PlaybackSnapshot.empty(), normalized.single())
    assertEquals(RestoreGateStatus.APPLIED, gate.status)
    assertTrue(gate.canPersist)
  }

  @Test
  fun incompatibleSnapshotSuppressesApplyAndNormalization() = runTest {
    val gate = gate()
    val player = RecordingRestorablePlayer()
    val normalized = mutableListOf<PlaybackSnapshot>()
    var gateChanges = 0

    coordinator(
        store =
          ImmediateSnapshotStore(PlaybackSnapshotReadResult.IncompatibleVersion(version = 99)),
        resolver = FailingIfCalledResolver(),
        gate = gate,
        player = player,
        normalized = normalized,
        onGateChanged = { gateChanges += 1 },
      )
      .start()
    runCurrent()

    assertEquals(RestoreGateStatus.INCOMPATIBLE, gate.status)
    assertFalse(gate.canPersist)
    assertTrue(player.plans.isEmpty())
    assertTrue(normalized.isEmpty())
    assertEquals(1, gateChanges)
  }

  @Test
  fun storeFailureEndsTransientWithoutApplyOrNormalization() = runTest {
    assertTransientFailureLeavesPlayerUntouched(
      store = ThrowingSnapshotStore(IllegalStateException("synthetic read failure")),
      resolver = FixedResolver(),
    )
  }

  @Test
  fun resolverFailureEndsTransientWithoutApplyOrNormalization() = runTest {
    assertTransientFailureLeavesPlayerUntouched(
      store = ImmediateSnapshotStore(usableSnapshot("demo:stored")),
      resolver = ThrowingResolver(IllegalStateException("synthetic resolver failure")),
    )
  }

  @Test
  fun playerApplyFailureClearsCriticalSectionAndEndsTransient() = runTest {
    val failure = IllegalStateException("synthetic apply failure")
    val track = coordinatorTrack("demo:stored")
    val gate = gate()
    val player = RecordingRestorablePlayer(applyFailure = failure)
    val failures = mutableListOf<Exception>()
    var gateChanges = 0

    coordinator(
        store = ImmediateSnapshotStore(usableSnapshot(track.id.value)),
        resolver = FixedResolver(resolutionOf(track)),
        gate = gate,
        player = player,
        failures = failures,
        onGateChanged = { gateChanges += 1 },
      )
      .start()
    runCurrent()

    assertEquals(RestoreGateStatus.FAILED, gate.status)
    assertEquals(RestoreFailureReason.TRANSIENT, gate.failureReason)
    assertFalse(gate.isApplyingRestore)
    assertFalse(gate.canPersist)
    assertSame(failure, failures.single())
    assertEquals(1, gateChanges)
  }

  @Test
  fun cancellationInsideApplyClearsCriticalSectionAndPropagatesTheSameException() = runTest {
    val cancellation = CancellationException("synthetic apply cancellation")
    val track = coordinatorTrack("demo:stored")
    val gate = gate()
    val player = RecordingRestorablePlayer(applyFailure = cancellation)
    val failures = mutableListOf<Exception>()
    val normalized = mutableListOf<PlaybackSnapshot>()
    var gateChanges = 0
    val job =
      coordinator(
          store = ImmediateSnapshotStore(usableSnapshot(track.id.value)),
          resolver = FixedResolver(resolutionOf(track)),
          gate = gate,
          player = player,
          normalized = normalized,
          failures = failures,
          onGateChanged = { gateChanges += 1 },
        )
        .start()
    val completion = CompletableDeferred<Throwable?>()
    job.invokeOnCompletion(completion::complete)

    runCurrent()

    assertSame(cancellation, completion.await())
    assertTrue(job.isCancelled)
    assertFalse(gate.isApplyingRestore)
    assertEquals(RestoreGateStatus.FAILED, gate.status)
    assertEquals(RestoreFailureReason.TRANSIENT, gate.failureReason)
    assertTrue(normalized.isEmpty())
    assertTrue(failures.isEmpty())
    assertEquals(1, gateChanges)
  }

  @Test
  fun startIsIdempotentAndCancelOnlyCancelsTheOwnedRestoreJob() = runTest {
    val read = CompletableDeferred<PlaybackSnapshotReadResult>()
    val store = CountingSuspendingSnapshotStore(read)
    val gate = gate()
    val coordinator = coordinator(store, FixedResolver(), gate, RecordingRestorablePlayer())

    val first = coordinator.start()
    val duplicateStart = coordinator.start()
    runCurrent()

    assertSame(first, duplicateStart)
    assertEquals(1, store.readCount)

    coordinator.cancel()
    runCurrent()

    assertTrue(first.isCancelled)
    assertEquals(RestoreGateStatus.RESTORE_PENDING, gate.status)
    assertFalse(gate.isApplyingRestore)

    val restarted = coordinator.start()
    runCurrent()
    assertNotSame(first, restarted)
    assertEquals(2, store.readCount)

    coordinator.cancel()
    runCurrent()
    assertTrue(restarted.isCancelled)
  }

  private suspend fun TestScope.assertTransientFailureLeavesPlayerUntouched(
    store: PlaybackSnapshotStore,
    resolver: PlaybackQueueResolver,
  ) {
    val gate = gate()
    val player = RecordingRestorablePlayer()
    val normalized = mutableListOf<PlaybackSnapshot>()
    val failures = mutableListOf<Exception>()
    var gateChanges = 0
    coordinator(
        store = store,
        resolver = resolver,
        gate = gate,
        player = player,
        normalized = normalized,
        failures = failures,
        onGateChanged = { gateChanges += 1 },
      )
      .start()
    runCurrent()

    assertEquals(RestoreGateStatus.FAILED, gate.status)
    assertEquals(RestoreFailureReason.TRANSIENT, gate.failureReason)
    assertFalse(gate.canPersist)
    assertTrue(player.plans.isEmpty())
    assertTrue(normalized.isEmpty())
    assertEquals(1, failures.size)
    assertEquals(1, gateChanges)
  }

  private fun TestScope.coordinator(
    store: PlaybackSnapshotStore,
    resolver: PlaybackQueueResolver,
    gate: RestorePersistenceGate,
    player: RestorablePlayer,
    normalized: MutableList<PlaybackSnapshot> = mutableListOf(),
    failures: MutableList<Exception> = mutableListOf(),
    onGateChanged: () -> Unit = {},
  ) =
    PlaybackRestoreCoordinator(
      snapshotStore = store,
      queueResolver = resolver,
      gate = gate,
      player = player,
      scope = backgroundScope,
      ioDispatcher = StandardTestDispatcher(testScheduler),
      onNormalizedSnapshot = normalized::add,
      onGateChanged = onGateChanged,
      onFailure = failures::add,
    )

  private fun gate() = RestorePersistenceGate(ControllerIdentity("app.yinyuehe", 1))
}

private class ImmediateSnapshotStore(
  private val result: PlaybackSnapshotReadResult,
) : PlaybackSnapshotStore {
  override suspend fun read(): PlaybackSnapshotReadResult = result

  override suspend fun write(snapshot: PlaybackSnapshot) = Unit
}

private class SuspendingSnapshotStore(
  private val result: CompletableDeferred<PlaybackSnapshotReadResult>,
) : PlaybackSnapshotStore {
  override suspend fun read(): PlaybackSnapshotReadResult = result.await()

  override suspend fun write(snapshot: PlaybackSnapshot) = Unit
}

private class CountingSuspendingSnapshotStore(
  private val result: CompletableDeferred<PlaybackSnapshotReadResult>,
) : PlaybackSnapshotStore {
  var readCount = 0
    private set

  override suspend fun read(): PlaybackSnapshotReadResult {
    readCount += 1
    return result.await()
  }

  override suspend fun write(snapshot: PlaybackSnapshot) = Unit
}

private class InspectingSnapshotStore(
  private val readResult: () -> PlaybackSnapshotReadResult,
) : PlaybackSnapshotStore {
  override suspend fun read(): PlaybackSnapshotReadResult = readResult()

  override suspend fun write(snapshot: PlaybackSnapshot) = Unit
}

private class ThrowingSnapshotStore(
  private val failure: Exception,
) : PlaybackSnapshotStore {
  override suspend fun read(): PlaybackSnapshotReadResult = throw failure

  override suspend fun write(snapshot: PlaybackSnapshot) = Unit
}

private class FixedResolver(
  private val result: PlaybackQueueResolution = PlaybackQueueResolution(emptyList()),
) : PlaybackQueueResolver {
  override suspend fun resolve(mediaIds: List<TrackId>): PlaybackQueueResolution = result
}

private class SuspendingResolver(
  private val result: CompletableDeferred<PlaybackQueueResolution>,
) : PlaybackQueueResolver {
  override suspend fun resolve(mediaIds: List<TrackId>): PlaybackQueueResolution = result.await()
}

private class InspectingResolver(
  private val resolution: () -> PlaybackQueueResolution,
) : PlaybackQueueResolver {
  override suspend fun resolve(mediaIds: List<TrackId>): PlaybackQueueResolution = resolution()
}

private class ThrowingResolver(
  private val failure: Exception,
) : PlaybackQueueResolver {
  override suspend fun resolve(mediaIds: List<TrackId>): PlaybackQueueResolution = throw failure
}

private class FailingIfCalledResolver : PlaybackQueueResolver {
  override suspend fun resolve(mediaIds: List<TrackId>): PlaybackQueueResolution =
    throw AssertionError("Incompatible snapshots must not be resolved")
}

private class RecordingRestorablePlayer(
  private val applyFailure: Throwable? = null,
  private val onApply: () -> Unit = {},
) : RestorablePlayer {
  val plans = mutableListOf<PlaybackRestorePlan>()

  override val isQueueEmpty: Boolean = true

  override fun apply(plan: PlaybackRestorePlan) {
    onApply()
    applyFailure?.let { failure -> throw failure }
    plans += plan
  }

  override fun queueFingerprint(): PlayerQueueFingerprint =
    plans.lastOrNull()?.let { plan ->
      PlayerQueueFingerprint(
        occurrenceKeys = plan.tracks.indices.map { index -> "restored-$index" },
        mediaIds = plan.tracks.map { track -> track.id.value },
        currentIndex = plan.currentIndex,
      )
    } ?: PlayerQueueFingerprint.EMPTY
}

private class LaneDispatcher(
  private val name: String,
  scheduler: TestCoroutineScheduler,
  private val lane: ThreadLocal<String?>,
) : CoroutineDispatcher() {
  private val delegate = StandardTestDispatcher(scheduler, name)

  override fun dispatch(context: CoroutineContext, block: Runnable) {
    delegate.dispatch(
      context,
      Runnable {
        val previous = lane.get()
        lane.set(name)
        try {
          block.run()
        } finally {
          lane.set(previous)
        }
      },
    )
  }
}

private fun usableSnapshot(id: String) =
  PlaybackSnapshotReadResult.Usable(
    PlaybackSnapshot(mediaIds = listOf(TrackId(id)), currentIndex = 0)
  )

private fun resolutionOf(track: Track) =
  PlaybackQueueResolution(
    listOf(PlaybackQueueItemResolution.Resolved(0, track.id, track))
  )

private fun coordinatorTrack(id: String) =
  Track(
    id = TrackId(id),
    title = id,
    artist = null,
    album = null,
    durationMs = 3_000,
    artworkUri = null,
    sourceUri = "android.resource://app/$id",
    isDemo = true,
  )
