package app.yinyuehe.core.player.service

import androidx.media3.common.C
import androidx.media3.common.Player
import app.yinyuehe.core.common.model.TrackId

internal fun Player.currentOccurrence(tokens: PlaybackOccurrenceTokens): QueueOccurrence? {
  val index = currentMediaItemIndex
  if (index !in 0 until mediaItemCount) return null
  val item = getMediaItemAt(index)
  val token = tokens.read(item) ?: return null
  return QueueOccurrence(index, token, item.mediaId.toTrackIdOrNull())
}

internal fun Player.failureCandidates(tokens: PlaybackOccurrenceTokens): List<QueueOccurrence> {
  if (mediaItemCount <= 1 || currentTimeline.isEmpty) return emptyList()
  val start = currentMediaItemIndex
  if (start !in 0 until mediaItemCount) return emptyList()
  val recoveryRepeatMode =
    if (repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
  return boundedCandidateIndices(start, mediaItemCount) { index ->
      currentTimeline.getNextWindowIndex(index, recoveryRepeatMode, shuffleModeEnabled)
    }
    .mapNotNull { index ->
      val item = getMediaItemAt(index)
      tokens.read(item)?.let { token ->
        QueueOccurrence(index, token, item.mediaId.toTrackIdOrNull())
      }
    }
}

internal fun boundedCandidateIndices(
  startIndex: Int,
  itemCount: Int,
  nextIndex: (Int) -> Int,
): List<Int> {
  val result = mutableListOf<Int>()
  var index = startIndex
  repeat((itemCount - 1).coerceAtLeast(0)) {
    index = nextIndex(index)
    if (index == C.INDEX_UNSET || index == startIndex || index in result) return result
    result += index
  }
  return result
}

private fun String.toTrackIdOrNull(): TrackId? = takeIf(String::isNotBlank)?.let(::TrackId)
