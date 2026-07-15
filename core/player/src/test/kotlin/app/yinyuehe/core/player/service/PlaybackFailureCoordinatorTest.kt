package app.yinyuehe.core.player.service

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackError
import app.yinyuehe.core.common.playback.PlaybackErrorType
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackFailureCoordinatorTest {
  @Test
  fun exhaustedSingleOccurrencePausesWithoutRemovingTheQueueOrSendingNotice() {
    val tokens = PlaybackOccurrenceTokens { 7 }
    val item =
      tokens.decorate(
        MediaItem.Builder().setMediaId("local:broken").setUri("content://media/broken").build()
      )
    val calls = mutableListOf<String>()
    val player =
      Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) {
          _,
          method,
          _ ->
        when (method.name) {
          "getMediaItemCount" -> 1
          "getCurrentMediaItemIndex" -> 0
          "getMediaItemAt" -> item
          "getPlayWhenReady" -> true
          "pause" -> {
            calls += "pause"
            null
          }
          "hashCode" -> 1
          "equals" -> false
          "toString" -> "FailurePlayer"
          else -> method.failureDefaultValue()
        }
      } as Player
    val notices = mutableListOf<app.yinyuehe.core.common.playback.PlaybackNotice.TrackSkipped>()
    val terminalUpdates = mutableListOf<PlaybackError?>()
    val coordinator =
      PlaybackFailureCoordinator(
        player,
        tokens,
        PlaybackFailurePolicy(),
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        notices::add,
        onTerminalErrorChanged = { terminalError ->
          terminalUpdates.add(terminalError)
          calls += "terminal:${terminalError?.type}"
        },
      )

    coordinator.onPlayerError(
      PlaybackException("not exposed", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
    )

    val expectedError =
      PlaybackError(PlaybackErrorType.SOURCE_UNAVAILABLE, 2005, TrackId("local:broken"))
    assertEquals(listOf("terminal:SOURCE_UNAVAILABLE", "pause"), calls)
    assertEquals(listOf(expectedError), terminalUpdates)
    assertEquals(1, player.mediaItemCount)
    assertEquals(emptyList<Any>(), notices)
    coordinator.close()
  }

  @Test
  fun recoverableFailureClearsTerminalStateBeforeTransportAndSendsOneShotNotice() {
    val tokens = PlaybackOccurrenceTokens { 11 }
    val first = QueueOccurrence(0, PlaybackOccurrenceToken(1), TrackId("local:broken"))
    val second = QueueOccurrence(1, PlaybackOccurrenceToken(2), TrackId("local:next"))
    val calls = mutableListOf<String>()
    val player =
      Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) {
          _,
          method,
          args ->
        when (method.name) {
          "getPlayWhenReady" -> true
          "seekToDefaultPosition" -> {
            calls += "seek:${args?.single()}"
            null
          }
          "prepare" -> {
            calls += "prepare"
            null
          }
          "play" -> {
            calls += "play"
            null
          }
          "hashCode" -> 1
          "equals" -> false
          "toString" -> "RecoverableFailurePlayer"
          else -> method.failureDefaultValue()
        }
      } as Player
    val terminalUpdates = mutableListOf<PlaybackError?>()
    val coordinator =
      PlaybackFailureCoordinator(
        player = player,
        tokens = tokens,
        policy = PlaybackFailurePolicy(),
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        onNotice = { calls += "notice:${it.error.trackId?.value}" },
        onTerminalErrorChanged = { terminalError ->
          terminalUpdates.add(terminalError)
          calls += "terminal:${terminalError?.type}"
        },
        currentOccurrenceProvider = { first },
        failureCandidatesProvider = { listOf(second) },
      )

    coordinator.onPlayerError(
      PlaybackException("must not escape", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
    )

    assertEquals(listOf<PlaybackError?>(null), terminalUpdates)
    assertEquals(
      listOf("terminal:null", "seek:1", "prepare", "play", "notice:local:broken"),
      calls,
    )
    coordinator.close()
  }

  @Test
  fun naturalEndAndExplicitRetryClearDecisionOwnedTerminalState() {
    val terminalUpdates = mutableListOf<PlaybackError?>()
    val player =
      Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) {
          _,
          method,
          _ ->
        when (method.name) {
          "hashCode" -> 1
          "equals" -> false
          "toString" -> "ResetPlayer"
          else -> method.failureDefaultValue()
        }
      } as Player
    val coordinator =
      PlaybackFailureCoordinator(
        player = player,
        tokens = PlaybackOccurrenceTokens { 13 },
        policy = PlaybackFailurePolicy(),
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        onNotice = {},
        onTerminalErrorChanged = { terminalError -> terminalUpdates.add(terminalError) },
      )

    coordinator.onPlaybackEnded()
    coordinator.onUserRetry()

    assertEquals(listOf<PlaybackError?>(null, null), terminalUpdates)
    coordinator.close()
  }

  @Test
  fun oneSecondOfTargetProgressInvokesTheCoordinatorStableCallback() = runTest {
    val tokens = PlaybackOccurrenceTokens { 9 }
    val item =
      tokens.decorate(
        MediaItem.Builder().setMediaId("local:target").setUri("content://media/target").build()
      )
    val targetToken = checkNotNull(tokens.read(item))
    var positionMs = 0L
    val player =
      Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) {
          _,
          method,
          _ ->
        when (method.name) {
          "getMediaItemCount" -> 1
          "getCurrentMediaItemIndex" -> 0
          "getMediaItemAt" -> item
          "getCurrentPosition" -> positionMs
          "isPlaying" -> true
          "hashCode" -> 1
          "equals" -> false
          "toString" -> "StableTargetPlayer"
          else -> method.failureDefaultValue()
        }
      } as Player
    val stableTokens = mutableListOf<PlaybackOccurrenceToken>()
    val coordinator =
      PlaybackFailureCoordinator(
        player = player,
        tokens = tokens,
        policy = PlaybackFailurePolicy(),
        scope = backgroundScope,
        onNotice = {},
        sampleIntervalMs = 250,
        onStablePlayback = stableTokens::add,
      )

    coordinator.trackStableTarget(targetToken)
    advanceTimeBy(250)
    runCurrent()
    listOf(250L, 500L, 750L, 999L).forEach { sampledPosition ->
      positionMs = sampledPosition
      advanceTimeBy(250)
      runCurrent()
    }
    assertEquals(emptyList<PlaybackOccurrenceToken>(), stableTokens)
    positionMs = 1_000
    advanceTimeBy(250)
    runCurrent()

    assertEquals(listOf(targetToken), stableTokens)
    coordinator.close()
  }
}

private fun Method.failureDefaultValue(): Any? =
  when (returnType) {
    Boolean::class.javaPrimitiveType -> false
    Int::class.javaPrimitiveType -> 0
    Long::class.javaPrimitiveType -> 0L
    Float::class.javaPrimitiveType -> 0f
    Double::class.javaPrimitiveType -> 0.0
    else -> null
  }
