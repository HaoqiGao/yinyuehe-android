package app.yinyuehe.feature.library

import app.yinyuehe.core.common.model.LibrarySource
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.player.PlaybackState

data class LibraryUiState(
  val activeDestination: MusicBoxDestination = MusicBoxDestination.HOME,
  val librarySource: LibrarySource = LibrarySource.DEMO,
  val libraryTracks: List<Track> = emptyList(),
  val favoriteTrackIds: Set<TrackId> = emptySet(),
  val favoriteTracks: List<Track> = emptyList(),
  val recentTracks: List<Track> = emptyList(),
  val trackCatalog: Map<TrackId, Track> = emptyMap(),
  val playback: PlaybackState = PlaybackState(),
  val isLoading: Boolean = true,
  val hasAudioPermission: Boolean = false,
  val permissionRequestPending: Boolean = false,
  val isScanning: Boolean = false,
  val errorCode: LibraryErrorCode? = null,
) {
  val currentTrack: Track?
    get() = playback.currentTrackId?.let(trackCatalog::get)

  fun tracksFor(collection: TrackCollection): List<Track> =
    when (collection) {
      TrackCollection.LIBRARY -> libraryTracks
      TrackCollection.FAVORITES -> favoriteTracks
      TrackCollection.RECENT -> recentTracks
    }
}

enum class LibraryErrorCode {
  CONNECTION_FAILED,
  PLAYBACK_FAILED,
  PERMISSION_REQUIRED,
  SCAN_FAILED,
  FAVORITE_UPDATE_FAILED,
}
