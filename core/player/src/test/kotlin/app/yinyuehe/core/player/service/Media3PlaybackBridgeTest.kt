package app.yinyuehe.core.player.service

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackRepeatMode
import app.yinyuehe.core.common.playback.PlaybackSnapshot
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Media3PlaybackBridgeTest {
  @Test
  fun applyRestoredQueueAssignsTokensPreparesAndNeverStartsPlayback() {
    val recording = RecordingBridgePlayer()
    val bridge =
      Media3RestorablePlayer(
        recording.player,
        PlaybackOccurrenceTokens(AtomicLong(0)::incrementAndGet),
      )
    val track =
      Track(
        id = TrackId("demo:one"),
        title = "One",
        artist = null,
        album = null,
        durationMs = 3_000,
        artworkUri = null,
        sourceUri = "android.resource://app/1",
        isDemo = true,
      )
    val snapshot =
      PlaybackSnapshot(
        mediaIds = listOf(track.id),
        currentIndex = 0,
        positionMs = 1_200,
        shuffleEnabled = true,
        repeatMode = PlaybackRepeatMode.ONE,
      )

    bridge.apply(
      PlaybackRestorePlan(
        tracks = listOf(track),
        currentIndex = 0,
        positionMs = 1_200,
        shuffleEnabled = true,
        repeatMode = PlaybackRepeatMode.ONE,
        normalizedSnapshot = snapshot,
      )
    )

    assertEquals(
      listOf(
        "setPlayWhenReady",
        "setMediaItems",
        "setShuffleModeEnabled",
        "setRepeatMode",
        "prepare",
        "setPlayWhenReady",
      ),
      recording.calls.map(BridgeCall::name),
    )
    val items = recording.calls[1].arguments[0] as List<*>
    val mediaItem = items.single() as MediaItem
    assertEquals("demo:one", mediaItem.mediaId)
    assertEquals(PlaybackOccurrenceToken(1), bridge.tokens.read(mediaItem))
    assertFalse(recording.playWasCalled)
  }
}

private data class BridgeCall(val name: String, val arguments: List<Any?>)

private class RecordingBridgePlayer {
  val calls = mutableListOf<BridgeCall>()
  var playWasCalled = false
  val player =
    Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
      when (method.name) {
        "getMediaItemCount" -> 0
        "getPlayWhenReady" -> false
        "play" -> {
          playWasCalled = true
          calls += BridgeCall(method.name, args?.toList().orEmpty())
          null
        }
        "hashCode" -> System.identityHashCode(this)
        "equals" -> false
        "toString" -> "RecordingBridgePlayer"
        else -> {
          calls += BridgeCall(method.name, args?.toList().orEmpty())
          method.defaultBridgeValue()
        }
      }
    } as Player
}

private fun Method.defaultBridgeValue(): Any? =
  when (returnType) {
    Boolean::class.javaPrimitiveType -> false
    Int::class.javaPrimitiveType -> 0
    Long::class.javaPrimitiveType -> 0L
    Float::class.javaPrimitiveType -> 0f
    Double::class.javaPrimitiveType -> 0.0
    else -> null
  }
