package app.yinyuehe.core.player

import androidx.media3.common.Player
import app.yinyuehe.core.common.model.Track

internal class PlaybackCommandDispatcher(private val player: Player) {
  fun canPlayQueue(): Boolean = REQUIRED_PLAY_COMMANDS.all(player::isCommandAvailable)

  fun playQueue(tracks: List<Track>, startIndex: Int, shuffle: Boolean): Boolean {
    if (!canPlayQueue()) return false
    player.setMediaItems(tracks.map(Track::toMediaItem), startIndex, 0)
    player.shuffleModeEnabled = shuffle
    player.prepare()
    player.play()
    return true
  }

  fun togglePlayPause() {
    val playbackState = player.playbackState
    val decision =
      playbackToggleDecision(
        playWhenReady = player.playWhenReady,
        hasCurrentMediaItem = player.currentMediaItem != null,
        isIdle = playbackState == Player.STATE_IDLE,
        isEnded = playbackState == Player.STATE_ENDED,
        canPlayPause = player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE),
        canPrepare = player.isCommandAvailable(Player.COMMAND_PREPARE),
        canSeekToDefaultPosition =
          player.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION),
      )
    if (!decision.canDispatch) return
    when (decision.preparation) {
      PlaybackTogglePreparation.NONE -> Unit
      PlaybackTogglePreparation.PREPARE -> player.prepare()
      PlaybackTogglePreparation.SEEK_TO_DEFAULT_POSITION -> player.seekToDefaultPosition()
    }
    when (decision.action) {
      PlaybackToggleAction.PLAY -> player.play()
      PlaybackToggleAction.PAUSE -> player.pause()
    }
  }

  fun seekTo(positionMs: Long) {
    if (!player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return
    player.seekTo(positionMs)
  }

  fun seekToPrevious() {
    if (!player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) return
    player.seekToPreviousMediaItem()
  }

  fun seekToNext() {
    if (!player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) return
    player.seekToNextMediaItem()
  }

  fun addToQueue(track: Track) {
    if (!player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return
    player.addMediaItem(track.toMediaItem())
  }

  fun removeQueueItem(index: Int) {
    if (
      player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) &&
        index in 0 until player.mediaItemCount
    ) {
      player.removeMediaItem(index)
    }
  }

  fun skipToQueueItem(index: Int) {
    if (
      player.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM) &&
        index in 0 until player.mediaItemCount
    ) {
      player.seekToDefaultPosition(index)
    }
  }

  fun setShuffleEnabled(enabled: Boolean) {
    if (!player.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE)) return
    player.shuffleModeEnabled = enabled
  }

  private companion object {
    val REQUIRED_PLAY_COMMANDS =
      listOf(
        Player.COMMAND_CHANGE_MEDIA_ITEMS,
        Player.COMMAND_SET_SHUFFLE_MODE,
        Player.COMMAND_PREPARE,
        Player.COMMAND_PLAY_PAUSE,
      )
  }
}
