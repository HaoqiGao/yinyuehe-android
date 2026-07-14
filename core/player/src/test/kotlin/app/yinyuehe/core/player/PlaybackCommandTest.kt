package app.yinyuehe.core.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackCommandTest {
  @Test
  fun playQueue_replacesTheQueueSelectsShuffleAndStartsPlayback() {
    val player = RecordingPlayer(mediaItemCount = 0)
    val dispatcher = PlaybackCommandDispatcher(player.instance)

    assertTrue(dispatcher.canPlayQueue())
    dispatcher.playQueue(
      tracks = listOf(track("local:one"), track("local:two")),
      startIndex = 1,
      shuffle = true,
    )

    assertEquals(
      listOf("setMediaItems", "setShuffleModeEnabled", "prepare", "play"),
      player.calls.map(Call::name),
    )
    val mediaItems = player.calls[0].arguments[0] as List<*>
    assertEquals(listOf("local:one", "local:two"), mediaItems.map { (it as MediaItem).mediaId })
    assertEquals(listOf(mediaItems, 1, 0L), player.calls[0].arguments)
    assertEquals(listOf(true), player.calls[1].arguments)
  }

  @Test
  fun transportCommands_delegateToTheMatchingPlayerOperations() {
    val player = RecordingPlayer(mediaItemCount = 3)
    val dispatcher = PlaybackCommandDispatcher(player.instance)

    dispatcher.seekTo(321)
    dispatcher.seekToPrevious()
    dispatcher.seekToNext()
    dispatcher.setShuffleEnabled(true)

    assertEquals(
      listOf("seekTo", "seekToPreviousMediaItem", "seekToNextMediaItem", "setShuffleModeEnabled"),
      player.calls.map(Call::name),
    )
    assertEquals(listOf(321L), player.calls[0].arguments)
    assertEquals(listOf(true), player.calls[3].arguments)
  }

  @Test
  fun addToQueue_mapsTheDomainTrackToAPlayableMediaItem() {
    val player = RecordingPlayer(mediaItemCount = 0)
    val dispatcher = PlaybackCommandDispatcher(player.instance)

    dispatcher.addToQueue(track("local:one"))

    val mediaItem = player.calls.single().arguments.single() as MediaItem
    assertEquals("addMediaItem", player.calls.single().name)
    assertEquals("local:one", mediaItem.mediaId)
    assertEquals("content://media/local:one", mediaItem.localConfiguration?.uri.toString())
  }

  @Test
  fun removeQueueItem_validatesAgainstTheLivePlayerQueue() {
    val player = RecordingPlayer(mediaItemCount = 2)
    val dispatcher = PlaybackCommandDispatcher(player.instance)

    dispatcher.removeQueueItem(-1)
    dispatcher.removeQueueItem(2)
    dispatcher.removeQueueItem(1)

    assertEquals(listOf(Call("removeMediaItem", listOf(1))), player.calls)
  }

  @Test
  fun skipToQueueItem_validatesAgainstTheLivePlayerQueue() {
    val player = RecordingPlayer(mediaItemCount = 2)
    val dispatcher = PlaybackCommandDispatcher(player.instance)

    dispatcher.skipToQueueItem(-1)
    dispatcher.skipToQueueItem(2)
    dispatcher.skipToQueueItem(0)

    assertEquals(listOf(Call("seekToDefaultPosition", listOf(0))), player.calls)
  }

  @Test
  fun togglePlayPause_restartsEndedPlaybackInOneAction() {
    val ended =
      RecordingPlayer(
        mediaItemCount = 1,
        playWhenReady = true,
        playbackState = Player.STATE_ENDED,
      )

    PlaybackCommandDispatcher(ended.instance).togglePlayPause()

    assertEquals(listOf("seekToDefaultPosition", "play"), ended.calls.map(Call::name))
  }

  @Test
  fun togglePlayPause_pausesPlaybackThatIsStillBuffering() {
    val playing =
      RecordingPlayer(
        mediaItemCount = 1,
        playWhenReady = true,
        playbackState = Player.STATE_BUFFERING,
      )
    val paused = RecordingPlayer(mediaItemCount = 1, playWhenReady = false)

    PlaybackCommandDispatcher(playing.instance).togglePlayPause()
    PlaybackCommandDispatcher(paused.instance).togglePlayPause()

    assertEquals("pause", playing.calls.single().name)
    assertEquals("play", paused.calls.single().name)
  }

  @Test
  fun togglePlayPause_doesNotPartiallyRestartEndedPlaybackWhenSeekIsUnavailable() {
    val ended =
      RecordingPlayer(
        mediaItemCount = 1,
        playWhenReady = true,
        playbackState = Player.STATE_ENDED,
        availableCommands = setOf(Player.COMMAND_PLAY_PAUSE),
      )

    PlaybackCommandDispatcher(ended.instance).togglePlayPause()

    assertTrue(ended.calls.isEmpty())
  }

  @Test
  fun togglePlayPause_doesNotDispatchForAnEmptyQueue() {
    val emptyQueue =
      RecordingPlayer(
        mediaItemCount = 0,
        availableCommands = setOf(Player.COMMAND_PLAY_PAUSE),
      )

    PlaybackCommandDispatcher(emptyQueue.instance).togglePlayPause()

    assertTrue(emptyQueue.calls.isEmpty())
  }

  @Test
  fun togglePlayPause_preparesAndPlaysAnIdleCurrentItemDespiteStalePlayWhenReady() {
    val idle =
      RecordingPlayer(
        mediaItemCount = 1,
        playWhenReady = true,
        playbackState = Player.STATE_IDLE,
        availableCommands = setOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE),
      )

    PlaybackCommandDispatcher(idle.instance).togglePlayPause()

    assertEquals(listOf("prepare", "play"), idle.calls.map(Call::name))
  }

  @Test
  fun togglePlayPause_doesNotPartiallyStartIdlePlaybackWhenPrepareIsUnavailable() {
    val idle =
      RecordingPlayer(
        mediaItemCount = 1,
        playbackState = Player.STATE_IDLE,
        availableCommands = setOf(Player.COMMAND_PLAY_PAUSE),
      )

    PlaybackCommandDispatcher(idle.instance).togglePlayPause()

    assertTrue(idle.calls.isEmpty())
  }

  @Test
  fun unavailablePlayerCommands_areNotDispatched() {
    val player = RecordingPlayer(mediaItemCount = 2, availableCommands = emptySet())
    val dispatcher = PlaybackCommandDispatcher(player.instance)

    assertFalse(dispatcher.canPlayQueue())
    assertFalse(dispatcher.playQueue(listOf(track("local:one")), 0, shuffle = false))
    dispatcher.togglePlayPause()
    dispatcher.seekTo(10)
    dispatcher.seekToPrevious()
    dispatcher.seekToNext()
    dispatcher.addToQueue(track("local:two"))
    dispatcher.removeQueueItem(0)
    dispatcher.skipToQueueItem(0)
    dispatcher.setShuffleEnabled(true)

    assertTrue(player.calls.isEmpty())
  }

  private fun track(id: String) =
    Track(
      id = TrackId(id),
      title = "Track $id",
      artist = "Artist",
      album = null,
      durationMs = 1_000,
      artworkUri = null,
      sourceUri = "content://media/$id",
      isDemo = false,
    )
}

private data class Call(val name: String, val arguments: List<Any?>)

private class RecordingPlayer(
  private val mediaItemCount: Int,
  private val hasCurrentMediaItem: Boolean = mediaItemCount > 0,
  private val playWhenReady: Boolean = false,
  private val playbackState: Int = Player.STATE_READY,
  private val availableCommands: Set<Int> =
    setOf(
      Player.COMMAND_PLAY_PAUSE,
      Player.COMMAND_PREPARE,
      Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
      Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
      Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
      Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
      Player.COMMAND_SEEK_TO_MEDIA_ITEM,
      Player.COMMAND_SET_SHUFFLE_MODE,
      Player.COMMAND_CHANGE_MEDIA_ITEMS,
    ),
) {
  val calls = mutableListOf<Call>()

  val instance: Player =
    Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
        when (method.name) {
          "getMediaItemCount" -> mediaItemCount
          "getCurrentMediaItem" ->
            if (hasCurrentMediaItem) {
              MediaItem.Builder().setMediaId("current").setUri("content://media/current").build()
            } else {
              null
            }
          "getPlayWhenReady" -> playWhenReady
          "getPlaybackState" -> playbackState
          "isCommandAvailable" -> args?.single() in availableCommands
          "hashCode" -> System.identityHashCode(this)
          "equals" -> false
          "toString" -> "RecordingPlayer"
          else -> {
            calls += Call(method.name, args?.toList().orEmpty())
            method.defaultValue()
          }
        }
      } as Player
}

private fun Method.defaultValue(): Any? =
  when (returnType) {
    Boolean::class.javaPrimitiveType -> false
    Int::class.javaPrimitiveType -> 0
    Long::class.javaPrimitiveType -> 0L
    Float::class.javaPrimitiveType -> 0f
    Double::class.javaPrimitiveType -> 0.0
    else -> null
  }
