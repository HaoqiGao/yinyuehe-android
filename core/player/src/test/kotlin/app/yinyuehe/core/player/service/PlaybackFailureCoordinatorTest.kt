package app.yinyuehe.core.player.service

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
    val coordinator =
      PlaybackFailureCoordinator(
        player,
        tokens,
        PlaybackFailurePolicy(),
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        notices::add,
      )

    coordinator.onPlayerError(
      PlaybackException("not exposed", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
    )

    assertEquals(listOf("pause"), calls)
    assertEquals(1, player.mediaItemCount)
    assertEquals(emptyList<Any>(), notices)
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
