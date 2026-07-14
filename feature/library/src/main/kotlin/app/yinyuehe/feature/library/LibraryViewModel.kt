package app.yinyuehe.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.yinyuehe.core.common.analytics.PlaybackEvent
import app.yinyuehe.core.common.analytics.PlaybackEventName
import app.yinyuehe.core.common.analytics.PlaybackEventRecorder
import app.yinyuehe.core.common.model.LibraryContent
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.data.TrackRepository
import app.yinyuehe.core.data.scan.LibraryScanner
import app.yinyuehe.core.player.PlaybackController
import app.yinyuehe.core.player.PlaybackState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class LibraryViewModel @Inject internal constructor(
  private val repository: TrackRepository,
  private val playbackController: PlaybackController,
  private val libraryScanner: LibraryScanner,
  private val playbackEventRecorder: PlaybackEventRecorder,
) : ViewModel() {
  private val localState = MutableStateFlow(LocalLibraryState())
  private var playbackRequestJob: Job? = null
  private var scanJob: Job? = null

  private val repositoryAndPlayback: Flow<RepositoryAndPlaybackState> =
    combine(
      repository.observeLibrary(),
      repository.observeFavoriteTrackIds(),
      repository.observeFavoriteTracks(),
      repository.observeRecentTracks(),
      playbackController.state,
    ) { library, favoriteIds, favoriteTracks, recentTracks, playback ->
      RepositoryAndPlaybackState(
        library = library,
        favoriteIds = favoriteIds,
        favoriteTracks = favoriteTracks,
        recentTracks = recentTracks,
        playback = playback,
      )
    }

  val uiState: StateFlow<LibraryUiState> =
    combine(repositoryAndPlayback, localState) { data, local ->
        LibraryUiState(
          activeDestination = local.activeDestination,
          librarySource = data.library.source,
          libraryTracks = data.library.tracks,
          favoriteTrackIds = data.favoriteIds,
          favoriteTracks = data.favoriteTracks,
          recentTracks = data.recentTracks,
          playback = data.playback,
          isLoading = false,
          hasAudioPermission = local.hasAudioPermission,
          permissionRequestPending = local.permissionRequestPending,
          isScanning = local.isScanning,
          errorCode = local.errorCode,
        )
      }
      .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

  fun onAction(action: MusicBoxAction) {
    when (action) {
      is MusicBoxAction.SelectDestination -> updateLocal {
        copy(activeDestination = action.destination)
      }
      MusicBoxAction.RequestAudioPermission -> updateLocal {
        copy(permissionRequestPending = true)
      }
      is MusicBoxAction.AudioPermissionResult -> handlePermissionResult(action.granted)
      MusicBoxAction.Rescan -> requestScan()
      is MusicBoxAction.PlayTrack -> playTrack(action.trackId, action.collection)
      is MusicBoxAction.PlayAll -> playCollection(action.collection, shuffle = false)
      is MusicBoxAction.PlayRandom -> playCollection(action.collection, shuffle = true)
      MusicBoxAction.TogglePlayPause -> playbackController.togglePlayPause()
      MusicBoxAction.Previous -> playbackController.seekToPrevious()
      MusicBoxAction.Next -> playbackController.seekToNext()
      is MusicBoxAction.SeekTo -> playbackController.seekTo(action.positionMs.coerceAtLeast(0))
      is MusicBoxAction.AddToQueue -> findKnownTrack(action.trackId)?.let(playbackController::addToQueue)
      is MusicBoxAction.RemoveQueueItem -> playbackController.removeQueueItem(action.index)
      is MusicBoxAction.JumpToQueueItem -> playbackController.skipToQueueItem(action.index)
      is MusicBoxAction.ToggleFavorite -> toggleFavorite(action.trackId)
    }
  }

  private fun handlePermissionResult(granted: Boolean) {
    val alreadyGranted = localState.value.hasAudioPermission
    updateLocal {
      copy(
        hasAudioPermission = granted,
        permissionRequestPending = false,
        errorCode = if (granted) null else LibraryErrorCode.PERMISSION_REQUIRED,
      )
    }
    if (granted && !alreadyGranted) requestScan()
  }

  private fun requestScan() {
    if (!localState.value.hasAudioPermission) {
      updateLocal { copy(errorCode = LibraryErrorCode.PERMISSION_REQUIRED) }
      return
    }
    if (scanJob?.isActive == true) return
    scanJob =
      viewModelScope.launch {
        updateLocal { copy(isScanning = true, errorCode = null) }
        try {
          val result = libraryScanner.scan()
          currentCoroutineContext().ensureActive()
          updateLocal {
            copy(
              isScanning = false,
              errorCode = if (result.isSuccess) null else LibraryErrorCode.SCAN_FAILED,
            )
          }
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (_: Exception) {
          updateLocal { copy(isScanning = false, errorCode = LibraryErrorCode.SCAN_FAILED) }
        }
      }
  }

  private fun playTrack(trackId: TrackId, collection: TrackCollection) {
    val tracks = uiState.value.tracksFor(collection)
    val index = tracks.indexOfFirst { it.id == trackId }
    if (index >= 0) requestPlayback(tracks, index, shuffle = false)
  }

  private fun playCollection(collection: TrackCollection, shuffle: Boolean) {
    val tracks = uiState.value.tracksFor(collection)
    if (tracks.isNotEmpty()) requestPlayback(tracks, startIndex = 0, shuffle = shuffle)
  }

  private fun requestPlayback(tracks: List<Track>, startIndex: Int, shuffle: Boolean) {
    playbackRequestJob?.cancel()
    playbackRequestJob =
      viewModelScope.launch {
        try {
          val accepted = playbackController.play(tracks, startIndex, shuffle)
          currentCoroutineContext().ensureActive()
          updateLocal {
            if (accepted) {
              copy(activeDestination = MusicBoxDestination.PLAYER, errorCode = null)
            } else {
              copy(errorCode = LibraryErrorCode.CONNECTION_FAILED)
            }
          }
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (_: Exception) {
          updateLocal { copy(errorCode = LibraryErrorCode.PLAYBACK_FAILED) }
        }
      }
  }

  private fun toggleFavorite(trackId: TrackId) {
    val shouldFavorite = trackId !in uiState.value.favoriteTrackIds
    viewModelScope.launch {
      val updated =
        try {
          repository.setFavorite(trackId, shouldFavorite)
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (_: Exception) {
          false
        }
      if (!updated) {
        updateLocal { copy(errorCode = LibraryErrorCode.FAVORITE_UPDATE_FAILED) }
        return@launch
      }

      updateLocal { copy(errorCode = null) }
      try {
        playbackEventRecorder.record(
          PlaybackEvent(
            name = PlaybackEventName.FAVORITE_CHANGED,
            occurredAtEpochMs = System.currentTimeMillis(),
            trackId = trackId,
          )
        )
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Exception) {
        // Analytics must never change the result of a successful favorite mutation.
      }
    }
  }

  private fun findKnownTrack(trackId: TrackId): Track? =
    uiState.value.allKnownTracks.firstOrNull { it.id == trackId }

  private inline fun updateLocal(transform: LocalLibraryState.() -> LocalLibraryState) {
    localState.value = localState.value.transform()
  }
}

private data class LocalLibraryState(
  val activeDestination: MusicBoxDestination = MusicBoxDestination.HOME,
  val hasAudioPermission: Boolean = false,
  val permissionRequestPending: Boolean = false,
  val isScanning: Boolean = false,
  val errorCode: LibraryErrorCode? = null,
)

private data class RepositoryAndPlaybackState(
  val library: LibraryContent,
  val favoriteIds: Set<TrackId>,
  val favoriteTracks: List<Track>,
  val recentTracks: List<Track>,
  val playback: PlaybackState,
)
