package app.yinyuehe.core.player.service

import androidx.media3.common.C
import androidx.media3.common.Player
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackRepeatMode
import app.yinyuehe.core.common.playback.PlaybackSnapshot
import app.yinyuehe.core.player.toMediaItem

internal class Media3RestorablePlayer(
  private val player: Player,
  internal val tokens: PlaybackOccurrenceTokens,
) : RestorablePlayer {
  override val isQueueEmpty: Boolean
    get() = player.mediaItemCount == 0

  override fun apply(plan: PlaybackRestorePlan) {
    player.playWhenReady = false
    if (plan.tracks.isEmpty()) {
      player.clearMediaItems()
    } else {
      val items = plan.tracks.map { track -> tokens.decorate(track.toMediaItem()) }
      player.setMediaItems(items, plan.currentIndex, plan.positionMs)
    }
    player.shuffleModeEnabled = plan.shuffleEnabled
    player.repeatMode = plan.repeatMode.toMedia3RepeatMode()
    if (plan.tracks.isNotEmpty()) player.prepare()
    player.playWhenReady = false
  }

  override fun queueFingerprint(): PlayerQueueFingerprint = player.queueFingerprint(tokens)
}

internal fun Player.capturePlaybackSnapshot(): PlaybackSnapshot {
  val indexedIds =
    List(mediaItemCount) { index -> index to getMediaItemAt(index).mediaId }
      .filter { (_, mediaId) -> mediaId.isNotBlank() }
  val mappedIndex = indexedIds.indexOfFirst { (index) -> index == currentMediaItemIndex }
  if (indexedIds.isEmpty()) return PlaybackSnapshot.empty()
  return PlaybackSnapshot(
    mediaIds = indexedIds.map { (_, mediaId) -> TrackId(mediaId) },
    currentIndex = mappedIndex.coerceAtLeast(0),
    positionMs = currentPosition.coerceAtLeast(0),
    shuffleEnabled = shuffleModeEnabled,
    repeatMode = repeatMode.toDomainRepeatMode(),
  )
}

internal fun Player.queueFingerprint(tokens: PlaybackOccurrenceTokens): PlayerQueueFingerprint =
  PlayerQueueFingerprint(
    occurrenceKeys =
      List(mediaItemCount) { index ->
        val item = getMediaItemAt(index)
        tokens.read(item)?.value?.toString() ?: "missing-token:$index:${item.mediaId}"
      },
    mediaIds = List(mediaItemCount) { getMediaItemAt(it).mediaId },
    currentIndex = currentMediaItemIndex.takeUnless { it == C.INDEX_UNSET } ?: -1,
  )

internal fun PlaybackRepeatMode.toMedia3RepeatMode(): Int =
  when (this) {
    PlaybackRepeatMode.OFF -> Player.REPEAT_MODE_OFF
    PlaybackRepeatMode.ALL -> Player.REPEAT_MODE_ALL
    PlaybackRepeatMode.ONE -> Player.REPEAT_MODE_ONE
  }

internal fun Int.toDomainRepeatMode(): PlaybackRepeatMode =
  when (this) {
    Player.REPEAT_MODE_ALL -> PlaybackRepeatMode.ALL
    Player.REPEAT_MODE_ONE -> PlaybackRepeatMode.ONE
    else -> PlaybackRepeatMode.OFF
  }
