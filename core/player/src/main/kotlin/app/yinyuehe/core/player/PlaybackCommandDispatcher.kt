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
    if (!player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) return
    if (player.playbackState == Player.STATE_ENDED) {
      if (!player.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)) return
      player.seekToDefaultPosition()
      player.play()
      return
    }
    if (shouldPauseForToggle(player.playWhenReady)) player.pause() else player.play()
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
