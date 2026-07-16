package app.yinyuehe.core.player

import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ControllerConnectionCoordinatorTest {
  @Test
  fun awaitStartedDuringConnectedUpdateFollowsReplacementController() = runTest {
    val connector = RecordingConnector<Any>()
    lateinit var coordinator: ControllerConnectionCoordinator<Any>
    lateinit var publishingWaiter: Deferred<Any?>
    lateinit var disconnectedDuringPublish: Any
    coordinator =
      ControllerConnectionCoordinator(
        scope = backgroundScope,
        callbackExecutor = MoreExecutors.directExecutor(),
        connector = connector,
        releaseConnected = {},
        releasePending = {},
        onUpdate = { update ->
          if (
            update is ControllerConnectionUpdate.Connected &&
              update.controller === disconnectedDuringPublish
          ) {
            publishingWaiter =
              async(Dispatchers.Unconfined) {
                coordinator.awaitConnected(startNewRoundIfExhausted = false)
              }
            connector.disconnectCallbacks[0].invoke(update.controller)
          }
        },
      )
    coordinator.start()
    disconnectedDuringPublish = Any()

    connector.futures.single().set(disconnectedDuringPublish)
    runCurrent()

    assertFalse(publishingWaiter.isCompleted)
    assertEquals(2, connector.futures.size)

    val recovered = Any()
    connector.futures[1].set(recovered)
    runCurrent()

    assertSame(recovered, publishingWaiter.await())
  }

  @Test
  fun delayedAwaitContinuationFollowsRoundCreatedAfterLiveDisconnect() = runTest {
    val connector = RecordingConnector<Any>()
    val releasedConnected = mutableListOf<Any>()
    val coordinator =
      ControllerConnectionCoordinator(
        scope = backgroundScope,
        callbackExecutor = MoreExecutors.directExecutor(),
        connector = connector,
        releaseConnected = releasedConnected::add,
        releasePending = {},
        onUpdate = {},
      )
    coordinator.start()
    val waiterDispatcher = QueuedCoroutineDispatcher()
    val delayedWaiter =
      async(waiterDispatcher) {
        coordinator.awaitConnected(startNewRoundIfExhausted = false)
      }
    waiterDispatcher.runNext()

    val disconnectedBeforeWaiterResumed = Any()
    connector.futures.single().set(disconnectedBeforeWaiterResumed)
    runCurrent()
    assertEquals(1, waiterDispatcher.size)

    connector.disconnectCallbacks.single().invoke(disconnectedBeforeWaiterResumed)
    runCurrent()
    assertEquals(listOf(disconnectedBeforeWaiterResumed), releasedConnected)
    assertEquals(2, connector.futures.size)

    waiterDispatcher.runNext()
    assertFalse(delayedWaiter.isCompleted)

    val recovered = Any()
    connector.futures[1].set(recovered)
    runCurrent()
    waiterDispatcher.runAll()

    assertSame(recovered, delayedWaiter.await())
  }

  @Test
  fun exhaustedUpdateReentryCompletesOriginalRoundAndKeepsRestartedRoundWaiting() = runTest {
    val connector = RecordingConnector<String>()
    lateinit var coordinator: ControllerConnectionCoordinator<String>
    lateinit var restartedWaiter: Deferred<String?>
    coordinator =
      ControllerConnectionCoordinator(
        scope = backgroundScope,
        callbackExecutor = MoreExecutors.directExecutor(),
        connector = connector,
        releaseConnected = {},
        releasePending = {},
        retryDelaysMs = listOf(250, 500, 1_000, 2_000),
        onUpdate = { update ->
          if (update == ControllerConnectionUpdate.Exhausted) {
            restartedWaiter =
              async(Dispatchers.Unconfined) {
                coordinator.awaitConnected(startNewRoundIfExhausted = true)
              }
          }
        },
      )
    coordinator.start()
    val originalWaiter = async { coordinator.awaitConnected(startNewRoundIfExhausted = false) }
    runCurrent()

    val retryDelays = listOf(250L, 500L, 1_000L, 2_000L)
    repeat(5) { index ->
      connector.futures[index].setException(IllegalStateException("attempt-${index + 1}"))
      runCurrent()
      if (index < retryDelays.size) {
        advanceTimeBy(retryDelays[index])
        runCurrent()
      }
    }

    assertEquals(6, connector.futures.size)
    assertTrue(originalWaiter.isCompleted)
    assertNull(originalWaiter.await())
    assertFalse(restartedWaiter.isCompleted)

    connector.futures[5].set("recovered")
    runCurrent()
    assertEquals("recovered", restartedWaiter.await())
  }

  @Test
  fun disconnectDuringConnectedUpdateDoesNotCompleteDeadControllerOrReplaceRound() = runTest {
    val connector = RecordingConnector<Any>()
    val releasedConnected = mutableListOf<Any>()
    val updates = mutableListOf<ControllerConnectionUpdate<Any>>()
    lateinit var disconnectedDuringPublish: Any
    val coordinator =
      ControllerConnectionCoordinator(
        scope = backgroundScope,
        callbackExecutor = MoreExecutors.directExecutor(),
        connector = connector,
        releaseConnected = releasedConnected::add,
        releasePending = {},
        onUpdate = { update ->
          updates += update
          if (
            update is ControllerConnectionUpdate.Connected &&
              update.controller === disconnectedDuringPublish
          ) {
            connector.disconnectCallbacks[0].invoke(update.controller)
          }
        },
      )
    coordinator.start()
    val waiting = async { coordinator.awaitConnected(startNewRoundIfExhausted = false) }
    runCurrent()

    disconnectedDuringPublish = Any()
    connector.futures.single().set(disconnectedDuringPublish)
    runCurrent()

    assertFalse(waiting.isCompleted)
    assertEquals(listOf(disconnectedDuringPublish), releasedConnected)
    assertEquals(2, connector.futures.size)

    val recovered = Any()
    connector.futures[1].set(recovered)
    runCurrent()

    assertSame(recovered, waiting.await())
    assertEquals(listOf(disconnectedDuringPublish), releasedConnected)
  }

  @Test
  fun differentPendingDisconnectIdentitiesInvalidateAmbiguousAttempt() = runTest {
    val connector = RecordingConnector<Any>()
    val releasedPending = mutableListOf<Any>()
    val coordinator =
      ControllerConnectionCoordinator(
        scope = backgroundScope,
        callbackExecutor = MoreExecutors.directExecutor(),
        connector = connector,
        releaseConnected = {},
        releasePending = { future -> releasedPending += future.get() },
        onUpdate = {},
      )
    coordinator.start()
    val waiting = async { coordinator.awaitConnected(startNewRoundIfExhausted = false) }
    runCurrent()

    connector.disconnectCallbacks.single().invoke(Any())
    val completedController = Any()
    connector.disconnectCallbacks.single().invoke(completedController)
    runCurrent()
    assertFalse(waiting.isCompleted)

    connector.futures.single().set(completedController)
    runCurrent()

    assertFalse(waiting.isCompleted)
    assertEquals(listOf(completedController), releasedPending)
    assertEquals(2, connector.futures.size)

    val recovered = Any()
    connector.futures[1].set(recovered)
    runCurrent()
    assertSame(recovered, waiting.await())
  }

  @Test
  fun disconnectDuringNoticeFlushDoesNotCompleteDeadControllerOrReplaceRound() = runTest {
    val gates = mutableListOf<ControllerAttemptEventGate<Any, Any>>()
    val connector =
      RecordingConnector<Any>(
        onAttemptCreated = { attempt ->
          val gate =
            ControllerAttemptEventGate<Any, Any>(
              onEvent = { controller -> attempt.onDisconnected(controller) }
            )
          gates += gate
          attempt.attachLifecycle(
            onActivated = { controller -> gate.activate(controller) },
            onRetired = gate::invalidate,
          )
        }
      )
    val releasedConnected = mutableListOf<Any>()
    val coordinator =
      ControllerConnectionCoordinator(
        scope = backgroundScope,
        callbackExecutor = MoreExecutors.directExecutor(),
        connector = connector,
        releaseConnected = releasedConnected::add,
        releasePending = {},
        onUpdate = {},
      )
    coordinator.start()
    val waiting = async { coordinator.awaitConnected(startNewRoundIfExhausted = false) }
    runCurrent()

    val disconnectedDuringFlush = Any()
    assertTrue(gates.single().offer(disconnectedDuringFlush, disconnectedDuringFlush))
    connector.futures.single().set(disconnectedDuringFlush)
    runCurrent()

    assertFalse(waiting.isCompleted)
    assertEquals(listOf(disconnectedDuringFlush), releasedConnected)
    assertEquals(2, connector.futures.size)

    val recovered = Any()
    connector.futures[1].set(recovered)
    runCurrent()
    assertSame(recovered, waiting.await())
  }

  @Test
  fun eventGatePreservesFifoAcrossReentryAndContainsSinkFailure() {
    val controller = Any()
    val delivered = mutableListOf<String>()
    lateinit var gate: ControllerAttemptEventGate<Any, String>
    gate =
      ControllerAttemptEventGate(
        onEvent = { event ->
          delivered += event
          if (event == "first") assertTrue(gate.offer(controller, "reentrant"))
          if (event == "second") error("sink failure")
        }
      )
    assertTrue(gate.offer(controller, "first"))
    assertTrue(gate.offer(controller, "second"))

    assertTrue(gate.activate(controller))

    assertEquals(listOf("first", "second", "reentrant"), delivered)
    assertTrue(gate.offer(controller, "after-flush"))
    assertEquals(listOf("first", "second", "reentrant", "after-flush"), delivered)
  }

  @Test
  fun duplicateLiveDisconnectReleasesAndRebuildsOnlyOnce() = runTest {
    val connector = RecordingConnector<Any>()
    val released = mutableListOf<Any>()
    val coordinator =
      ControllerConnectionCoordinator(
        scope = backgroundScope,
        callbackExecutor = MoreExecutors.directExecutor(),
        connector = connector,
        releaseConnected = released::add,
        releasePending = {},
        onUpdate = {},
      )
    coordinator.start()
    val connected = Any()
    connector.futures.single().set(connected)
    runCurrent()

    connector.disconnectCallbacks.single().invoke(connected)
    connector.disconnectCallbacks.single().invoke(connected)
    runCurrent()

    assertEquals(listOf(connected), released)
    assertEquals(2, connector.futures.size)
  }

  @Test
  fun fiveConsecutiveEarlyDisconnectsExhaustOneBoundedRound() = runTest {
    val callbackExecutor = QueuedExecutor()
    val connector = RecordingConnector<Any>()
    val released = mutableListOf<Any>()
    val updates = mutableListOf<ControllerConnectionUpdate<Any>>()
    val coordinator =
      ControllerConnectionCoordinator(
        scope = backgroundScope,
        callbackExecutor = callbackExecutor,
        connector = connector,
        releaseConnected = {},
        releasePending = { future -> released += future.get() },
        onUpdate = updates::add,
      )
    coordinator.start()
    val disconnectedControllers = List(5) { Any() }

    disconnectedControllers.forEachIndexed { index, disconnected ->
      connector.futures[index].set(disconnected)
      connector.disconnectCallbacks[index].invoke(disconnected)
      runCurrent()
      assertEquals(minOf(index + 2, 5), connector.futures.size)
    }

    assertEquals(disconnectedControllers, released)
    assertEquals(ControllerConnectionUpdate.Exhausted, updates.last())
    assertNull(coordinator.awaitConnected(startNewRoundIfExhausted = false))

    callbackExecutor.runAll()
    runCurrent()
    assertEquals(disconnectedControllers, released)
    assertEquals(5, connector.futures.size)
  }

  @Test
  fun duplicateEarlyDisconnectReleasesAndRebuildsOnlyOnce() = runTest {
    val callbackExecutor = QueuedExecutor()
    val connector = RecordingConnector<Any>()
    val released = mutableListOf<Any>()
    val coordinator =
      ControllerConnectionCoordinator(
        scope = backgroundScope,
        callbackExecutor = callbackExecutor,
        connector = connector,
        releaseConnected = {},
        releasePending = { future -> released += future.get() },
        onUpdate = {},
      )
    coordinator.start()
    val disconnected = Any()
    connector.futures.single().set(disconnected)

    connector.disconnectCallbacks.single().invoke(disconnected)
    connector.disconnectCallbacks.single().invoke(disconnected)
    runCurrent()

    assertEquals(2, connector.futures.size)
    assertEquals(listOf(disconnected), released)
    callbackExecutor.runAll()
    runCurrent()
    assertEquals(2, connector.futures.size)
    assertEquals(listOf(disconnected), released)
  }

  @Test
  fun attemptLifecycleFlushesPrepublishEventsFifoBeforeWaiterAndRetiresOnDisconnect() = runTest {
    val events = mutableListOf<String>()
    val gates = mutableListOf<ControllerAttemptEventGate<Any, String>>()
    val connector =
      RecordingConnector<Any>(
        onAttemptCreated = { attempt ->
          val gate = ControllerAttemptEventGate<Any, String>(events::add)
          gates += gate
          attempt.attachLifecycle(
            onActivated = { controller -> gate.activate(controller) },
            onRetired = gate::invalidate,
          )
        }
      )
    val coordinator =
      ControllerConnectionCoordinator(
        scope = backgroundScope,
        callbackExecutor = MoreExecutors.directExecutor(),
        connector = connector,
        releaseConnected = {},
        releasePending = {},
        onUpdate = { update ->
          if (update is ControllerConnectionUpdate.Connected) events += "connected-update"
        },
      )
    coordinator.start()
    val controller = Any()
    assertTrue(gates.single().offer(controller, "prepublish-1"))
    assertTrue(gates.single().offer(controller, "prepublish-2"))
    val waiting =
      async(Dispatchers.Unconfined) {
        coordinator.awaitConnected(startNewRoundIfExhausted = false)
        events += "waiter"
      }

    connector.futures.single().set(controller)
    runCurrent()
    waiting.await()

    assertEquals(
      listOf("connected-update", "prepublish-1", "prepublish-2", "waiter"),
      events,
    )
    assertTrue(gates[0].offer(controller, "active"))
    assertEquals("active", events.last())

    connector.disconnectCallbacks.single().invoke(controller)

    assertFalse(gates[0].offer(controller, "stale-after-disconnect"))
    runCurrent()
    assertEquals(2, connector.futures.size)
    assertFalse(events.contains("stale-after-disconnect"))
  }

  @Test
  fun updatesPrecedeDifferentDispatcherWaitersForConnectedAndExhaustedResults() = runTest {
    val connectedEvents = mutableListOf<String>()
    val connectedConnector = RecordingConnector<String>()
    val connectedCoordinator =
      ControllerConnectionCoordinator(
        scope = backgroundScope,
        callbackExecutor = MoreExecutors.directExecutor(),
        connector = connectedConnector,
        releaseConnected = {},
        releasePending = {},
        onUpdate = { update ->
          if (update is ControllerConnectionUpdate.Connected) connectedEvents += "update"
        },
      )
    connectedCoordinator.start()
    val connectedWaiter =
      async(Dispatchers.Unconfined) {
        connectedCoordinator.awaitConnected(startNewRoundIfExhausted = false)
        connectedEvents += "waiter"
      }

    connectedConnector.futures.single().set("connected")
    runCurrent()
    connectedWaiter.await()

    assertEquals(listOf("update", "waiter"), connectedEvents)

    val exhaustedEvents = mutableListOf<String>()
    val exhaustedConnector = RecordingConnector<String>()
    val exhaustedCoordinator =
      ControllerConnectionCoordinator(
        scope = backgroundScope,
        callbackExecutor = MoreExecutors.directExecutor(),
        connector = exhaustedConnector,
        releaseConnected = {},
        releasePending = {},
        retryDelaysMs = emptyList(),
        onUpdate = { update ->
          if (update is ControllerConnectionUpdate.Exhausted) exhaustedEvents += "update"
        },
      )
    exhaustedCoordinator.start()
    val exhaustedWaiter =
      async(Dispatchers.Unconfined) {
        exhaustedCoordinator.awaitConnected(startNewRoundIfExhausted = false)
        exhaustedEvents += "waiter"
      }

    exhaustedConnector.futures.single().setException(IllegalStateException("failed"))
    runCurrent()
    exhaustedWaiter.await()

    assertEquals(listOf("update", "waiter"), exhaustedEvents)
  }

  @Test
  fun cancellingOneOfMultipleAwaitersDoesNotCancelSharedRoundOrFuture() = runTest {
    val connector = RecordingConnector<String>()
    val coordinator = coordinator(connector, mutableListOf(), backgroundScope)
    coordinator.start()
    val cancelledWaiter =
      async { coordinator.awaitConnected(startNewRoundIfExhausted = false) }
    val survivingWaiter =
      async { coordinator.awaitConnected(startNewRoundIfExhausted = false) }
    runCurrent()

    cancelledWaiter.cancelAndJoin()

    assertFalse(connector.futures.single().isCancelled)
    assertFalse(survivingWaiter.isCompleted)
    val connected = "shared-controller"
    connector.futures.single().set(connected)
    runCurrent()

    assertEquals(connected, survivingWaiter.await())
    assertEquals(1, connector.futures.size)
  }

  @Test
  fun currentAttemptBuffersBeforeAcceptanceThenEmitsExactlyOnceAndContinuesLive() {
    val events = mutableListOf<String>()
    val controller = Any()
    val gate = ControllerAttemptEventGate<Any, String>(events::add)

    assertTrue(gate.offer(controller, "before-connected"))
    assertEquals(emptyList<String>(), events)

    assertTrue(gate.activate(controller))
    assertEquals(listOf("before-connected"), events)
    assertTrue(gate.activate(controller))
    assertEquals(listOf("before-connected"), events)

    assertTrue(gate.offer(controller, "after-connected"))
    assertEquals(listOf("before-connected", "after-connected"), events)
  }

  @Test
  fun pendingAttemptBufferIsBoundedAndRejectsOverflowWithoutDroppingOlderEvent() {
    val events = mutableListOf<String>()
    val controller = Any()
    val gate =
      ControllerAttemptEventGate<Any, String>(
        maxPendingEvents = 1,
        onEvent = events::add,
      )

    assertTrue(gate.offer(controller, "kept"))
    assertFalse(gate.offer(controller, "overflow"))
    assertEquals(emptyList<String>(), events)

    assertTrue(gate.activate(controller))
    assertEquals(listOf("kept"), events)
  }

  @Test
  fun invalidatedOrMismatchedAttemptDropsPendingAndRejectsStaleEvents() {
    val events = mutableListOf<String>()
    val currentController = Any()
    val staleController = Any()
    val invalidatedGate = ControllerAttemptEventGate<Any, String>(events::add)
    assertTrue(invalidatedGate.offer(staleController, "buffered-stale"))

    invalidatedGate.invalidate()

    assertFalse(invalidatedGate.activate(staleController))
    assertFalse(invalidatedGate.offer(staleController, "late-stale"))
    assertEquals(emptyList<String>(), events)

    val mismatchedGate = ControllerAttemptEventGate<Any, String>(events::add)
    assertTrue(mismatchedGate.offer(staleController, "wrong-owner"))
    assertFalse(mismatchedGate.activate(currentController))
    assertFalse(mismatchedGate.offer(staleController, "after-mismatch"))
    assertEquals(emptyList<String>(), events)
  }

  @Test
  fun failedAndClosedAttemptsInvalidateTheirExactFuture() = runTest {
    val retired = mutableListOf<ControllerConnectionAttempt<String>>()
    val delivered = mutableListOf<String>()
    val gates = mutableListOf<ControllerAttemptEventGate<String, String>>()
    val connector =
      RecordingConnector<String>(
        onAttemptCreated = { attempt ->
          val gate = ControllerAttemptEventGate<String, String>(delivered::add)
          gates += gate
          attempt.attachLifecycle(
            onActivated = { controller -> gate.activate(controller) },
            onRetired = {
              gate.invalidate()
              retired += attempt
            },
          )
        }
      )
    val released = mutableListOf<ListenableFuture<String>>()
    val coordinator =
      ControllerConnectionCoordinator(
        scope = backgroundScope,
        callbackExecutor = MoreExecutors.directExecutor(),
        connector = connector,
        releaseConnected = {},
        releasePending = released::add,
        retryDelaysMs = listOf(250),
        onUpdate = {},
      )
    coordinator.start()
    val failed = connector.futures.single()
    assertTrue(gates.single().offer("failed-controller", "failed-buffer"))

    failed.setException(IllegalStateException("failed"))
    runCurrent()
    assertEquals(listOf(connector.attempts[0]), retired)
    assertFalse(gates[0].offer("failed-controller", "failed-stale"))
    advanceTimeBy(250)
    runCurrent()
    val pendingAtClose = connector.futures[1]
    assertTrue(gates[1].offer("close-controller", "close-buffer"))

    coordinator.close()
    runCurrent()

    assertEquals(connector.attempts, retired)
    assertEquals(listOf(pendingAtClose), released)
    assertFalse(gates[1].offer("close-controller", "close-stale"))
    assertEquals(emptyList<String>(), delivered)
  }

  @Test
  fun disconnectBeforeFutureCompletesIsMatchedByIdentityWithoutLosingRound() = runTest {
    val connector = RecordingConnector<Any>()
    val releasedPendingControllers = mutableListOf<Any>()
    val updates = mutableListOf<ControllerConnectionUpdate<Any>>()
    val coordinator =
      ControllerConnectionCoordinator(
        scope = backgroundScope,
        callbackExecutor = MoreExecutors.directExecutor(),
        connector = connector,
        releaseConnected = {},
        releasePending = { future -> releasedPendingControllers += future.get() },
        onUpdate = updates::add,
      )
    coordinator.start()
    val waiting = async { coordinator.awaitConnected(startNewRoundIfExhausted = false) }
    runCurrent()

    val disconnectedBeforeCompletion = Any()
    connector.disconnectCallbacks.single().invoke(disconnectedBeforeCompletion)
    runCurrent()
    assertEquals(1, connector.futures.size)
    assertFalse(waiting.isCompleted)

    connector.futures.single().set(disconnectedBeforeCompletion)
    runCurrent()

    assertEquals(2, connector.futures.size)
    assertEquals(listOf(disconnectedBeforeCompletion), releasedPendingControllers)
    assertFalse(waiting.isCompleted)
    assertFalse(
      updates.any {
        it is ControllerConnectionUpdate.Connected && it.controller === disconnectedBeforeCompletion
      }
    )

    val recovered = Any()
    connector.futures[1].set(recovered)
    runCurrent()

    assertSame(recovered, waiting.await())
    assertEquals(
      listOf(recovered),
      updates.filterIsInstance<ControllerConnectionUpdate.Connected<Any>>().map { it.controller },
    )
    assertEquals(listOf(disconnectedBeforeCompletion), releasedPendingControllers)
  }

  @Test
  fun disconnectBeforeQueuedCompletionReleasesAttemptAndKeepsOriginalRoundWaiting() = runTest {
    val callbackExecutor = QueuedExecutor()
    val connector = RecordingConnector<Any>()
    val releasedPendingControllers = mutableListOf<Any>()
    val releasedConnectedControllers = mutableListOf<Any>()
    val updates = mutableListOf<ControllerConnectionUpdate<Any>>()
    val coordinator =
      ControllerConnectionCoordinator(
        scope = backgroundScope,
        callbackExecutor = callbackExecutor,
        connector = connector,
        releaseConnected = releasedConnectedControllers::add,
        releasePending = { future -> releasedPendingControllers += future.get() },
        onUpdate = updates::add,
      )
    coordinator.start()
    val waiting = async { coordinator.awaitConnected(startNewRoundIfExhausted = false) }
    runCurrent()

    val disconnectedBeforePublish = Any()
    connector.futures.single().set(disconnectedBeforePublish)
    assertEquals(1, callbackExecutor.size)
    connector.disconnectCallbacks.single().invoke(disconnectedBeforePublish)
    runCurrent()

    assertEquals(2, connector.futures.size)
    assertEquals(listOf(disconnectedBeforePublish), releasedPendingControllers)
    assertFalse(waiting.isCompleted)
    assertFalse(
      updates.any {
        it is ControllerConnectionUpdate.Connected && it.controller === disconnectedBeforePublish
      }
    )

    callbackExecutor.runNext()
    runCurrent()
    assertEquals(listOf(disconnectedBeforePublish), releasedPendingControllers)
    assertFalse(waiting.isCompleted)

    val recovered = Any()
    connector.futures[1].set(recovered)
    callbackExecutor.runNext()
    runCurrent()

    assertSame(recovered, waiting.await())
    assertEquals(
      listOf(recovered),
      updates.filterIsInstance<ControllerConnectionUpdate.Connected<Any>>().map { it.controller },
    )
    coordinator.close()
    assertEquals(listOf(recovered), releasedConnectedControllers)
    assertEquals(listOf(disconnectedBeforePublish), releasedPendingControllers)
  }

  @Test
  fun connectedUpdatePublishesBeforeAwaiterResumes() = runTest {
    val dispatcher = UnconfinedTestDispatcher(testScheduler)
    val connector = RecordingConnector<String>()
    val events = mutableListOf<String>()
    val coordinator =
      ControllerConnectionCoordinator(
        scope = CoroutineScope(dispatcher),
        callbackExecutor = MoreExecutors.directExecutor(),
        connector = connector,
        releaseConnected = {},
        releasePending = {},
        onUpdate = { update ->
          if (update is ControllerConnectionUpdate.Connected) events += "connected"
        },
      )
    coordinator.start()
    val waiting =
      async(dispatcher) {
        coordinator.awaitConnected(startNewRoundIfExhausted = false)
        events += "awaited"
      }

    connector.futures.single().set("controller")
    waiting.await()

    assertEquals(listOf("connected", "awaited"), events)
  }

  @Test
  fun oneImmediateBuildAndFourExactDelaysThenExhausted() = runTest {
    val connector = RecordingConnector<String>()
    val updates = mutableListOf<ControllerConnectionUpdate<String>>()
    val coordinator = coordinator(connector, updates, backgroundScope)

    coordinator.start()
    assertEquals(1, connector.futures.size)
    connector.futures[0].setException(IllegalStateException("attempt-1"))
    runCurrent()
    advanceTimeBy(249)
    assertEquals(1, connector.futures.size)
    advanceTimeBy(1)
    runCurrent()
    assertEquals(2, connector.futures.size)

    connector.futures[1].setException(IllegalStateException("attempt-2"))
    runCurrent()
    advanceTimeBy(500)
    runCurrent()
    assertEquals(3, connector.futures.size)
    connector.futures[2].setException(IllegalStateException("attempt-3"))
    runCurrent()
    advanceTimeBy(1_000)
    runCurrent()
    assertEquals(4, connector.futures.size)
    connector.futures[3].setException(IllegalStateException("attempt-4"))
    runCurrent()
    advanceTimeBy(2_000)
    runCurrent()
    assertEquals(5, connector.futures.size)
    connector.futures[4].setException(IllegalStateException("attempt-5"))
    runCurrent()

    assertEquals(1, updates.count { it is ControllerConnectionUpdate.Connecting })
    assertEquals(ControllerConnectionUpdate.Exhausted, updates.last())
    assertNull(coordinator.awaitConnected(startNewRoundIfExhausted = false))

    val restarted = async { coordinator.awaitConnected(startNewRoundIfExhausted = true) }
    runCurrent()
    assertEquals(6, connector.futures.size)
    connector.futures[5].set("recovered")
    runCurrent()
    assertEquals("recovered", restarted.await())
    assertEquals(2, updates.count { it is ControllerConnectionUpdate.Connecting })
  }

  @Test
  fun successResetsRound_disconnectBuildsImmediately_andStaleDisconnectIsIgnored() = runTest {
    val connector = RecordingConnector<Any>()
    val released = mutableListOf<Any>()
    val coordinator =
      ControllerConnectionCoordinator(
        scope = backgroundScope,
        callbackExecutor = MoreExecutors.directExecutor(),
        connector = connector,
        releaseConnected = released::add,
        releasePending = {},
        onUpdate = {},
      )
    coordinator.start()
    val first = Any()
    connector.futures.single().set(first)
    runCurrent()
    assertSame(first, coordinator.awaitConnected(false))

    connector.disconnectCallbacks.single().invoke(first)
    runCurrent()
    assertEquals(2, connector.futures.size)
    val second = Any()
    connector.futures[1].set(second)
    runCurrent()
    connector.disconnectCallbacks[0].invoke(first)
    runCurrent()

    assertSame(second, coordinator.awaitConnected(false))
    assertEquals(listOf(first), released)
    coordinator.close()
    assertNull(coordinator.awaitConnected(false))
    assertEquals(listOf(first, second), released)
  }

  @Test
  fun closeReleasesPendingFutureAndLateSuccessCannotPublish() = runTest {
    val connector = RecordingConnector<String>()
    val releasedFutures = mutableListOf<ListenableFuture<String>>()
    val updates = mutableListOf<ControllerConnectionUpdate<String>>()
    val coordinator =
      ControllerConnectionCoordinator(
        scope = backgroundScope,
        callbackExecutor = MoreExecutors.directExecutor(),
        connector = connector,
        releaseConnected = {},
        releasePending = releasedFutures::add,
        onUpdate = updates::add,
      )
    coordinator.start()
    val pending = connector.futures.single()

    coordinator.close()
    pending.set("late")
    runCurrent()

    assertEquals(listOf(pending), releasedFutures)
    assertFalse(updates.any { it is ControllerConnectionUpdate.Connected })
  }

  private fun coordinator(
    connector: RecordingConnector<String>,
    updates: MutableList<ControllerConnectionUpdate<String>>,
    scope: kotlinx.coroutines.CoroutineScope,
  ) =
    ControllerConnectionCoordinator(
      scope = scope,
      callbackExecutor = MoreExecutors.directExecutor(),
      connector = connector,
      releaseConnected = {},
      releasePending = {},
      retryDelaysMs = listOf(250, 500, 1_000, 2_000),
      onUpdate = updates::add,
    )
}

private class RecordingConnector<T : Any>(
  private val onAttemptCreated: (ControllerConnectionAttempt<T>) -> Unit = {},
) : ControllerConnector<T> {
  val attempts = mutableListOf<ControllerConnectionAttempt<T>>()
  val futures = mutableListOf<SettableFuture<T>>()
  val disconnectCallbacks = mutableListOf<(T) -> Unit>()

  override fun connect(attempt: ControllerConnectionAttempt<T>): ListenableFuture<T> =
    SettableFuture.create<T>().also {
      attempts += attempt
      onAttemptCreated(attempt)
      futures += it
      disconnectCallbacks += attempt::onDisconnected
    }
}

private class QueuedExecutor : Executor {
  private val commands = ArrayDeque<Runnable>()

  val size: Int
    get() = commands.size

  override fun execute(command: Runnable) {
    commands.addLast(command)
  }

  fun runNext() {
    commands.removeFirst().run()
  }

  fun runAll() {
    while (commands.isNotEmpty()) runNext()
  }
}

private class QueuedCoroutineDispatcher : CoroutineDispatcher() {
  private val commands = ArrayDeque<Runnable>()

  val size: Int
    get() = commands.size

  override fun dispatch(context: CoroutineContext, block: Runnable) {
    commands.addLast(block)
  }

  fun runNext() {
    commands.removeFirst().run()
  }

  fun runAll() {
    while (commands.isNotEmpty()) runNext()
  }
}
