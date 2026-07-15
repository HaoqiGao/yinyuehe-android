package app.yinyuehe.core.player

import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun interface ControllerConnector<T : Any> {
  fun connect(onDisconnected: (T) -> Unit): ListenableFuture<T>
}

internal sealed interface ControllerConnectionUpdate<out T> {
  data object Connecting : ControllerConnectionUpdate<Nothing>

  data class Connected<T : Any>(val controller: T) : ControllerConnectionUpdate<T>

  data object Exhausted : ControllerConnectionUpdate<Nothing>
}

internal class ControllerConnectionCoordinator<T : Any>(
  private val scope: CoroutineScope,
  private val callbackExecutor: Executor,
  private val connector: ControllerConnector<T>,
  private val releaseConnected: (T) -> Unit,
  private val releasePending: (ListenableFuture<T>) -> Unit,
  private val retryDelaysMs: List<Long> = listOf(250, 500, 1_000, 2_000),
  private val onUpdate: (ControllerConnectionUpdate<T>) -> Unit,
) {
  private val releasedFutures =
    Collections.newSetFromMap(IdentityHashMap<ListenableFuture<T>, Boolean>())
  private var generation = 0L
  private var connectedGeneration = -1L
  private var currentFuture: ListenableFuture<T>? = null
  private var currentController: T? = null
  private var retryJob: Job? = null
  private var retryIndex = 0
  private var started = false
  private var exhausted = false
  private var closed = false
  private var roundResult = completedNullResult<T>()

  fun start() {
    if (started || closed) return
    started = true
    beginRound()
  }

  suspend fun awaitConnected(startNewRoundIfExhausted: Boolean): T? {
    currentController?.let { return it }
    if (!started) start()
    if (exhausted && startNewRoundIfExhausted && !closed) beginRound()
    return roundResult.await()
  }

  fun close() {
    if (closed) return
    closed = true
    generation += 1
    retryJob?.cancel()
    retryJob = null
    currentFuture?.let(::releaseFutureOnce)
    currentFuture = null
    currentController?.let(releaseConnected)
    currentController = null
    roundResult.complete(null)
    roundResult = completedNullResult()
  }

  private fun beginRound() {
    retryJob?.cancel()
    retryIndex = 0
    exhausted = false
    roundResult = CompletableDeferred()
    onUpdate(ControllerConnectionUpdate.Connecting)
    buildAttempt()
  }

  private fun buildAttempt() {
    if (closed || currentFuture != null || currentController != null) return
    val attemptGeneration = ++generation
    val future =
      try {
        connector.connect { controller ->
          scope.launch { handleDisconnected(attemptGeneration, controller) }
        }
      } catch (error: Exception) {
        Futures.immediateFailedFuture(error)
      }
    currentFuture = future
    future.addListener(
      { scope.launch { handleCompletion(attemptGeneration, future) } },
      callbackExecutor,
    )
  }

  private fun handleCompletion(attemptGeneration: Long, future: ListenableFuture<T>) {
    if (closed || attemptGeneration != generation || currentFuture !== future) {
      releaseFutureOnce(future)
      return
    }
    currentFuture = null
    val controller = runCatching { future.get() }.getOrNull()
    if (controller != null) {
      currentController = controller
      connectedGeneration = attemptGeneration
      retryIndex = 0
      roundResult.complete(controller)
      onUpdate(ControllerConnectionUpdate.Connected(controller))
      return
    }
    if (retryIndex >= retryDelaysMs.size) {
      exhausted = true
      roundResult.complete(null)
      onUpdate(ControllerConnectionUpdate.Exhausted)
      return
    }
    val retryDelayMs = retryDelaysMs[retryIndex++]
    retryJob =
      scope.launch {
        delay(retryDelayMs)
        retryJob = null
        if (!closed && currentFuture == null && currentController == null) buildAttempt()
      }
  }

  private fun handleDisconnected(attemptGeneration: Long, controller: T) {
    if (
      closed ||
        attemptGeneration != connectedGeneration ||
        currentController !== controller
    ) {
      return
    }
    currentController = null
    releaseConnected(controller)
    beginRound()
  }

  private fun releaseFutureOnce(future: ListenableFuture<T>) {
    if (releasedFutures.add(future)) releasePending(future)
  }
}

private fun <T : Any> completedNullResult(): CompletableDeferred<T?> =
  CompletableDeferred<T?>().apply { complete(null) }
