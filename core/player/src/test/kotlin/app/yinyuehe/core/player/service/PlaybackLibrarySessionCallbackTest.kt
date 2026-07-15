package app.yinyuehe.core.player.service

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class PlaybackLibrarySessionCallbackTest {
  @Test
  fun everyApplicableQueuePrepareAndSeekCommandIsAnExplicitRetry() {
    val retryCommands =
      listOf(
        Player.COMMAND_CHANGE_MEDIA_ITEMS,
        Player.COMMAND_SEEK_TO_MEDIA_ITEM,
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_BACK,
        Player.COMMAND_SEEK_FORWARD,
        Player.COMMAND_PREPARE,
      )

    retryCommands.forEach { command ->
      assertTrue(
        "Player command $command must reset the failure round",
        isExplicitPlaybackRetry(commands(command), playWhenReady = false),
      )
    }
  }

  @Test
  fun playPauseIsARetryOnlyWhenTheFinishedInteractionRequestsPlayback() {
    val playPause = commands(Player.COMMAND_PLAY_PAUSE)

    assertTrue(isExplicitPlaybackRetry(playPause, playWhenReady = true))
    assertFalse(isExplicitPlaybackRetry(playPause, playWhenReady = false))
  }

  @Test
  fun unrelatedOrEmptyCommandSetsDoNotResetTheFailureRound() {
    assertFalse(
      isExplicitPlaybackRetry(commands(Player.COMMAND_SET_VOLUME), playWhenReady = true)
    )
    assertFalse(isExplicitPlaybackRetry(Player.Commands.EMPTY, playWhenReady = true))
  }
}

private fun commands(command: Int): Player.Commands =
  Player.Commands.Builder().add(command).build()
