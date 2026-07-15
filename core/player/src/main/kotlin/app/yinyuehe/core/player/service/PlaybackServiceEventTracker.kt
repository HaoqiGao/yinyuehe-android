package app.yinyuehe.core.player.service

import androidx.media3.common.Player
import app.yinyuehe.core.common.analytics.PlaybackEventName
import app.yinyuehe.core.common.model.TrackId

internal data class PlaybackServiceUpdate(
  val eventNames: List<PlaybackEventName>,
  val trackId: TrackId,
  val recordHistory: Boolean = false,
)

internal class PlaybackServiceEventTracker {
  private var currentTrackId: TrackId? = null
  private var started = false
  private var completed = false

  fun onMediaItemTransition(
    mediaId: String?,
    isPlaying: Boolean,
    @Player.MediaItemTransitionReason reason: Int,
  ): List<PlaybackServiceUpdate> {
    val updates = mutableListOf<PlaybackServiceUpdate>()
    if (reason.completesPreviousOccurrence()) {
      completeCurrentOccurrence()?.let(updates::add)
    }

    val trackId = mediaId.toTrackIdOrNull()
    currentTrackId = trackId
    started = false
    completed = false
    if (trackId == null) return updates

    val eventNames = mutableListOf(PlaybackEventName.TRACK_CHANGED)
    if (isPlaying) {
      started = true
      eventNames += PlaybackEventName.PLAYBACK_STARTED
    }
    updates +=
      PlaybackServiceUpdate(
        eventNames = eventNames,
        trackId = trackId,
        recordHistory = isPlaying,
      )
    return updates
  }

  fun onIsPlayingChanged(isPlaying: Boolean, mediaId: String?): PlaybackServiceUpdate? {
    if (!isPlaying) return null
    val trackId = mediaId.toTrackIdOrNull() ?: return null
    if (currentTrackId != trackId || completed) {
      currentTrackId = trackId
      started = false
      completed = false
    }
    if (started) return null
    started = true
    return PlaybackServiceUpdate(
      eventNames = listOf(PlaybackEventName.PLAYBACK_STARTED),
      trackId = trackId,
      recordHistory = true,
    )
  }

  fun onPlaybackEnded(mediaId: String?): PlaybackServiceUpdate? {
    val trackId = mediaId.toTrackIdOrNull() ?: return null
    return completeCurrentOccurrence(expectedTrackId = trackId)
  }

  private fun completeCurrentOccurrence(expectedTrackId: TrackId? = null): PlaybackServiceUpdate? {
    val trackId = currentTrackId ?: return null
    if (expectedTrackId != null && trackId != expectedTrackId) return null
    if (!started || completed) return null
    completed = true
    return PlaybackServiceUpdate(
      eventNames = listOf(PlaybackEventName.PLAYBACK_COMPLETED),
      trackId = trackId,
    )
  }
}

private fun String?.toTrackIdOrNull(): TrackId? =
  this?.takeIf(String::isNotBlank)?.let(::TrackId)

private fun Int.completesPreviousOccurrence(): Boolean =
  this == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
    this == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
