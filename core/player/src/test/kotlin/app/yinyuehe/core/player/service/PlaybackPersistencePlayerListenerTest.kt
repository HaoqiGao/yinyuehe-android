package app.yinyuehe.core.player.service

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import app.yinyuehe.core.common.playback.PlaybackSnapshot
import app.yinyuehe.core.common.playback.PlaybackSnapshotReadResult
import app.yinyuehe.core.common.playback.PlaybackSnapshotStore
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaybackPersistencePlayerListenerTest {
  @Test
  fun initialEmptyIsSuppressed_userTimelineOpensGate_andSeekIsImmediate() = runTest {
    val tokens = PlaybackOccurrenceTokens { 1 }
    val mutablePlayer = MutableQueuePlayer()
    val gate = RestorePersistenceGate(ControllerIdentity("app.yinyuehe", 1))
    val store = ListenerRecordingStore()
    val writer =
      PlaybackSnapshotWriter(store, StandardTestDispatcher(testScheduler))
    val persistence =
      PlaybackPersistenceCoordinator(
        gate,
        writer,
        backgroundScope,
        mutablePlayer.player::capturePlaybackSnapshot,
      )
    var gateChangeCount = 0
    val listener =
      PlaybackPersistencePlayerListener(
        mutablePlayer.player,
        tokens,
        gate,
        persistence,
      ) { gateChangeCount += 1 }

    listener.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
    runCurrent()
    assertEquals(RestoreGateStatus.RESTORE_PENDING, gate.status)
    assertTrue(store.writes.isEmpty())

    mutablePlayer.items =
      listOf(
        tokens.decorate(
          MediaItem.Builder().setMediaId("demo:user").setUri("android.resource://app/1").build()
        )
      )
    listener.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
    runCurrent()
    assertEquals(RestoreGateStatus.SUPERSEDED, gate.status)
    assertEquals(1, gateChangeCount)
    assertEquals(1, store.writes.size)

    listener.onPositionDiscontinuity(
      positionInfo(),
      positionInfo(),
      Player.DISCONTINUITY_REASON_SEEK,
    )
    runCurrent()
    assertEquals(2, store.writes.size)
    listener.onRepeatModeChanged(Player.REPEAT_MODE_ALL)
    advanceTimeBy(249)
    assertEquals(2, store.writes.size)
    advanceTimeBy(1)
    runCurrent()
    assertEquals(3, store.writes.size)
    persistence.close()
  }
}

private class MutableQueuePlayer {
  var items: List<MediaItem> = emptyList()
  val player =
    Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
      when (method.name) {
        "getMediaItemCount" -> items.size
        "getMediaItemAt" -> items[args?.single() as Int]
        "getCurrentMediaItemIndex" -> if (items.isEmpty()) C.INDEX_UNSET else 0
        "getCurrentPosition" -> 100L
        "getShuffleModeEnabled" -> false
        "getRepeatMode" -> Player.REPEAT_MODE_OFF
        "hashCode" -> 1
        "equals" -> false
        "toString" -> "MutableQueuePlayer"
        else -> method.listenerDefaultValue()
      }
    } as Player
}

private class ListenerRecordingStore : PlaybackSnapshotStore {
  val writes = mutableListOf<PlaybackSnapshot>()
  override suspend fun read() = PlaybackSnapshotReadResult.Usable(PlaybackSnapshot.empty())
  override suspend fun write(snapshot: PlaybackSnapshot) {
    writes += snapshot
  }
}

private fun positionInfo() =
  Player.PositionInfo(null, 0, null, 0, 0, 0, C.INDEX_UNSET, C.INDEX_UNSET)

private fun Method.listenerDefaultValue(): Any? =
  when (returnType) {
    Boolean::class.javaPrimitiveType -> false
    Int::class.javaPrimitiveType -> 0
    Long::class.javaPrimitiveType -> 0L
    Float::class.javaPrimitiveType -> 0f
    Double::class.javaPrimitiveType -> 0.0
    else -> null
  }
