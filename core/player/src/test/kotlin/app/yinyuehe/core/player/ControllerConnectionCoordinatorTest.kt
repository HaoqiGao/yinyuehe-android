package app.yinyuehe.core.player

import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ControllerConnectionCoordinatorTest {
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

private class RecordingConnector<T : Any> : ControllerConnector<T> {
  val futures = mutableListOf<SettableFuture<T>>()
  val disconnectCallbacks = mutableListOf<(T) -> Unit>()

  override fun connect(onDisconnected: (T) -> Unit): ListenableFuture<T> =
    SettableFuture.create<T>().also {
      futures += it
      disconnectCallbacks += onDisconnected
    }
}
