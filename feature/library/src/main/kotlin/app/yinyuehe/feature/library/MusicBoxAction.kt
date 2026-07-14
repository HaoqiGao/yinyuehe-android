package app.yinyuehe.feature.library

import app.yinyuehe.core.common.model.TrackId

enum class TrackCollection {
  LIBRARY,
  FAVORITES,
  RECENT,
}

sealed interface MusicBoxAction {
  data class SelectDestination(val destination: MusicBoxDestination) : MusicBoxAction

  data object RequestAudioPermission : MusicBoxAction

  data class AudioPermissionResult(val granted: Boolean) : MusicBoxAction

  data object Rescan : MusicBoxAction

  data class PlayTrack(
    val trackId: TrackId,
    val collection: TrackCollection,
  ) : MusicBoxAction

  data class PlayAll(val collection: TrackCollection) : MusicBoxAction

  data class PlayRandom(val collection: TrackCollection) : MusicBoxAction

  data object TogglePlayPause : MusicBoxAction

  data object Previous : MusicBoxAction

  data object Next : MusicBoxAction

  data class SeekTo(val positionMs: Long) : MusicBoxAction

  data class AddToQueue(val trackId: TrackId) : MusicBoxAction

  data class RemoveQueueItem(val index: Int) : MusicBoxAction

  data class JumpToQueueItem(val index: Int) : MusicBoxAction

  data class ToggleFavorite(val trackId: TrackId) : MusicBoxAction
}
