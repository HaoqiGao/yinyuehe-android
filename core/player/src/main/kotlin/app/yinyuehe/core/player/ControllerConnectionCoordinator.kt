package app.yinyuehe.core.player

import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun interface ControllerConnector<T : Any> {
  fun connect(attempt: ControllerConnectionAttempt<T>): ListenableFuture<T>
}

internal sealed interface ControllerConnectionUpdate<out T> {
  data object Connecting : ControllerConnectionUpdate<Nothing>

  data class Connected<T : Any>(val controller: T) : ControllerConnectionUpdate<T>

  data object Exhausted : ControllerConnectionUpdate<Nothing>
}

internal class ControllerAttemptEventGate<C : Any, E : Any>(
  private val onEvent: (E) -> Unit,
  private val maxPendingEvents: Int = DEFAULT_MAX_PENDING_EVENTS,
) {
  private val lock = Any()
  private var state = State.PENDING
  private var controller: C? = null
  private val pendingEvents = ArrayDeque<E>()

  init {
    require(maxPendingEvents > 0) { "maxPendingEvents must be positive" }
  }

  fun offer(controller: C, event: E): Boolean {
    var eventToDeliver: E? = null
    val accepted =
      synchronized(lock) {
        when (state) {
          State.PENDING,
          State.FLUSHING -> {
            val pendingController = this.controller
            if (pendingController != null && pendingController !== controller) {
              false
            } else if (pendingEvents.size >= maxPendingEvents) {
              false
            } else {
              this.controller = controller
              pendingEvents.addLast(event)
              true
            }
          }
          State.ACTIVE -> {
            if (this.controller !== controller) {
              false
            } else {
              eventToDeliver = event
              true
            }
          }
          State.INVALID -> false
        }
      }
    eventToDeliver?.let { pendingEvent -> runCatching { onEvent(pendingEvent) } }
    return accepted
  }

  fun activate(controller: C): Boolean {
    val canActivate =
      synchronized(lock) {
        when (state) {
          State.ACTIVE -> return this.controller === controller
          State.INVALID -> return false
          State.FLUSHING -> return this.controller === controller
          State.PENDING -> {
            val pendingController = this.controller
            if (pendingController != null && pendingController !== controller) {
              state = State.INVALID
              this.controller = null
              pendingEvents.clear()
              false
            } else {
              this.controller = controller
              state = State.FLUSHING
              true
            }
          }
        }
      }
    if (!canActivate) return false
    while (true) {
      val pendingEvent =
        synchronized(lock) {
          if (state != State.FLUSHING) return@synchronized null
          if (pendingEvents.isEmpty()) {
            state = State.ACTIVE
            null
          } else {
            pendingEvents.removeFirst()
          }
        }
      if (pendingEvent == null) return synchronized(lock) { state == State.ACTIVE }
      runCatching { onEvent(pendingEvent) }
    }
  }

  fun invalidate() {
    synchronized(lock) {
      state = State.INVALID
      controller = null
      pendingEvents.clear()
    }
  }

  private enum class State {
    PENDING,
    FLUSHING,
    ACTIVE,
    INVALID,
  }

  private companion object {
    const val DEFAULT_MAX_PENDING_EVENTS = 16
  }
}

internal class ControllerConnectionAttempt<T : Any> internal constructor(
  internal val generation: Long,
  private val dispatchDisconnected: (ControllerConnectionAttempt<T>, T) -> Unit,
) {
  private val lock = Any()
  private var state = State.PENDING
  private var acceptedController: T? = null
  private var future: ListenableFuture<T>? = null
  private var futureReleased = false
  private var onActivated: ((T) -> Unit)? = null
  private var onRetired: (() -> Unit)? = null

  internal fun attachLifecycle(
    onActivated: (T) -> Unit,
    onRetired: () -> Unit,
  ) {
    val retireImmediately =
      synchronized(lock) {
        check(this.onActivated == null && this.onRetired == null) {
          "Attempt lifecycle is already attached"
        }
        if (state == State.RETIRED) {
          true
        } else {
          this.onActivated = onActivated
          this.onRetired = onRetired
          false
        }
      }
    if (retireImmediately) runCatching(onRetired)
  }

  fun onDisconnected(controller: T) {
    var retirementCallback: (() -> Unit)? = null
    val shouldDispatch =
      synchronized(lock) {
        when (state) {
          State.PENDING -> {
            state = State.RETIRED
            retirementCallback = onRetired
            onRetired = null
            onActivated = null
            true
          }
          State.PUBLISHING -> {
            if (acceptedController !== controller) {
              false
            } else {
              state = State.RETIRED
              retirementCallback = onRetired
              onRetired = null
              onActivated = null
              true
            }
          }
          State.ACTIVE -> {
            if (acceptedController !== controller) {
              false
            } else {
              state = State.RETIRED
              retirementCallback = onRetired
              onRetired = null
              onActivated = null
              true
            }
          }
          State.RETIRED -> false
        }
      }
    retirementCallback?.let { callback -> runCatching(callback) }
    if (shouldDispatch) dispatchDisconnected(this, controller)
  }

  internal fun bindFuture(future: ListenableFuture<T>) {
    synchronized(lock) {
      check(this.future == null) { "Attempt Future is already bound" }
      this.future = future
    }
  }

  internal fun ownsFuture(future: ListenableFuture<T>): Boolean =
    synchronized(lock) { this.future === future }

  internal fun beginPublishing(controller: T): Boolean =
    synchronized(lock) {
      if (state != State.PENDING) {
        false
      } else {
        state = State.PUBLISHING
        acceptedController = controller
        true
      }
    }

  internal fun activate(): Boolean {
    var callback: ((T) -> Unit)? = null
    val controller =
      synchronized(lock) {
        if (state != State.PUBLISHING) return false
        state = State.ACTIVE
        callback = onActivated
        onActivated = null
        acceptedController
      } ?: return false
    callback?.let { onAccepted -> runCatching { onAccepted(controller) } }
    return synchronized(lock) { state == State.ACTIVE && acceptedController === controller }
  }

  internal fun ownsAcceptedController(controller: T): Boolean =
    synchronized(lock) { acceptedController === controller }

  internal fun ownsActiveController(controller: T): Boolean =
    synchronized(lock) { state == State.ACTIVE && acceptedController === controller }

  internal fun retire() {
    val callback =
      synchronized(lock) {
        if (state == State.RETIRED) return
        state = State.RETIRED
        onActivated = null
        onRetired.also { onRetired = null }
      }
    callback?.let { onAttemptRetired -> runCatching(onAttemptRetired) }
  }

  internal fun releaseFutureOnce(releasePending: (ListenableFuture<T>) -> Unit) {
    val futureToRelease =
      synchronized(lock) {
        val boundFuture = future
        if (futureReleased || boundFuture == null) {
          null
        } else {
          futureReleased = true
          boundFuture
        }
      }
    futureToRelease?.let(releasePending)
  }

  private enum class State {
    PENDING,
    PUBLISHING,
    ACTIVE,
    RETIRED,
  }
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
  private var generation = 0L
  private var currentAttempt: ControllerConnectionAttempt<T>? = null
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
    activeControllerOrNull()?.let { return it }
    if (!started) start()
    if (exhausted && startNewRoundIfExhausted && !closed) beginRound()
    var connectionRound = roundResult
    while (true) {
      val connectedController = connectionRound.await() ?: return null
      if (isCurrentActiveConnection(connectionRound, connectedController)) {
        return connectedController
      }
      val replacementRound = roundResult
      if (replacementRound === connectionRound) return null
      connectionRound = replacementRound
    }
  }

  fun close() {
    if (closed) return
    closed = true
    generation += 1
    retryJob?.cancel()
    retryJob = null
    val attempt = currentAttempt
    val future = currentFuture
    currentAttempt = null
    currentFuture = null
    attempt?.retire()
    if (future != null && attempt?.ownsFuture(future) == true) {
      attempt.releaseFutureOnce(releasePending)
    }
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
    val attempt =
      ControllerConnectionAttempt<T>(attemptGeneration) { disconnectedAttempt, controller ->
        scope.launch { handleDisconnected(disconnectedAttempt, controller) }
      }
    currentAttempt = attempt
    val future =
      try {
        connector.connect(attempt)
      } catch (error: Exception) {
        Futures.immediateFailedFuture(error)
      }
    attempt.bindFuture(future)
    currentFuture = future
    future.addListener(
      { scope.launch { handleCompletion(attempt, future) } },
      callbackExecutor,
    )
  }

  private fun handleCompletion(
    attempt: ControllerConnectionAttempt<T>,
    future: ListenableFuture<T>,
  ) {
    val connectionRound = roundResult
    if (
      closed ||
        attempt.generation != generation ||
        currentAttempt !== attempt ||
        currentFuture !== future ||
        !attempt.ownsFuture(future)
    ) {
      attempt.retire()
      attempt.releaseFutureOnce(releasePending)
      return
    }
    currentFuture = null
    val controller = runCatching { future.get() }.getOrNull()
    if (controller != null) {
      if (!attempt.beginPublishing(controller)) {
        currentAttempt = null
        attempt.retire()
        attempt.releaseFutureOnce(releasePending)
        continueAfterFailedAttempt(retryImmediately = true)
        return
      }
      currentController = controller
      onUpdate(ControllerConnectionUpdate.Connected(controller))
      val activated = attempt.activate()
      if (
        !activated ||
          closed ||
          currentAttempt !== attempt ||
          currentController !== controller ||
          roundResult !== connectionRound ||
          !attempt.ownsActiveController(controller)
      ) {
        abandonPublishingController(attempt, controller, connectionRound)
        return
      }
      retryIndex = 0
      connectionRound.complete(controller)
      return
    }
    currentAttempt = null
    attempt.retire()
    continueAfterFailedAttempt(retryImmediately = false)
  }

  private fun continueAfterFailedAttempt(retryImmediately: Boolean) {
    val connectionRound = roundResult
    if (closed || connectionRound.isCompleted) return
    if (retryIndex >= retryDelaysMs.size) {
      exhausted = true
      onUpdate(ControllerConnectionUpdate.Exhausted)
      connectionRound.complete(null)
      return
    }
    if (retryImmediately) {
      retryIndex += 1
      buildAttempt()
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

  private fun handleDisconnected(
    attempt: ControllerConnectionAttempt<T>,
    controller: T,
  ) {
    if (closed || attempt.generation != generation || currentAttempt !== attempt) return
    if (currentController === controller && attempt.ownsAcceptedController(controller)) {
      val connectionRound = roundResult
      val wasPublished = connectionRound.isCompleted
      currentController = null
      currentAttempt = null
      attempt.retire()
      releaseConnected(controller)
      if (closed) return
      if (wasPublished) {
        beginRound()
      } else {
        onUpdate(ControllerConnectionUpdate.Connecting)
        continueAfterFailedAttempt(retryImmediately = true)
      }
      return
    }
    val future = currentFuture ?: return
    if (!attempt.ownsFuture(future) || !future.isDone) return
    val completedController = runCatching { future.get() }.getOrNull()
    if (completedController !== controller || currentFuture !== future) return
    currentFuture = null
    currentAttempt = null
    attempt.retire()
    attempt.releaseFutureOnce(releasePending)
    continueAfterFailedAttempt(retryImmediately = true)
  }

  private fun abandonPublishingController(
    attempt: ControllerConnectionAttempt<T>,
    controller: T,
    connectionRound: CompletableDeferred<T?>,
  ) {
    if (
      closed ||
        currentAttempt !== attempt ||
        currentController !== controller ||
        roundResult !== connectionRound
    ) {
      return
    }
    currentController = null
    currentAttempt = null
    attempt.retire()
    releaseConnected(controller)
    if (closed || connectionRound.isCompleted || roundResult !== connectionRound) return
    onUpdate(ControllerConnectionUpdate.Connecting)
    continueAfterFailedAttempt(retryImmediately = true)
  }

  private fun activeControllerOrNull(): T? {
    val connectionRound = roundResult
    if (!connectionRound.isCompleted) return null
    val attempt = currentAttempt ?: return null
    val controller = currentController ?: return null
    return controller.takeIf {
      roundResult === connectionRound && attempt.ownsActiveController(controller)
    }
  }

  private fun isCurrentActiveConnection(
    connectionRound: CompletableDeferred<T?>,
    controller: T,
  ): Boolean =
    roundResult === connectionRound &&
      currentController === controller &&
      currentAttempt?.ownsActiveController(controller) == true
}

private fun <T : Any> completedNullResult(): CompletableDeferred<T?> =
  CompletableDeferred<T?>().apply { complete(null) }
