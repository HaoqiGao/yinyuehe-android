package app.yinyuehe.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.yinyuehe.core.common.analytics.PlaybackEvent
import app.yinyuehe.core.common.analytics.PlaybackEventName
import app.yinyuehe.core.common.analytics.PlaybackEventRecorder
import app.yinyuehe.core.common.model.LibraryContent
import app.yinyuehe.core.common.model.LibrarySource
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.data.TrackRepository
import app.yinyuehe.core.data.scan.LibraryScanner
import app.yinyuehe.core.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
  private val persistedFavoriteIds = MutableStateFlow<Set<TrackId>>(emptySet())
  private val demoTracks = repository.demoTracks()
  private val favoriteWorkers = mutableMapOf<TrackId, Job>()
  private val favoriteAppliedStates = mutableMapOf<TrackId, Boolean>()
  private var playbackRequestJob: Job? = null
  private var scanJob: Job? = null

  private val repositoryCatalog: Flow<RepositoryCatalog> =
    combine(
      repository.observeLibrary(),
      repository.observeFavoriteTrackIds(),
      repository.observeFavoriteTracks(),
      repository.observeRecentTracks(),
    ) { library, favoriteIds, favoriteTracks, recentTracks ->
      RepositoryCatalog(library, favoriteIds, favoriteTracks, recentTracks)
    }

  private val catalogControls: Flow<CatalogControls> =
    localState
      .map { state ->
        CatalogControls(
          hasAudioPermission = state.hasAudioPermission,
          pendingFavoriteDesired = state.pendingFavoriteDesired,
        )
      }
      .distinctUntilChanged()

  private val catalogSnapshot: Flow<CatalogSnapshot> =
    combine(repositoryCatalog, catalogControls) { data, controls ->
      buildCatalogSnapshot(data, controls, demoTracks)
    }

  val uiState: StateFlow<LibraryUiState> =
    combine(catalogSnapshot, localState, playbackController.state) { catalog, local, playback ->
        LibraryUiState(
          activeDestination = local.activeDestination,
          librarySource = catalog.library.source,
          libraryTracks = catalog.library.tracks,
          favoriteTrackIds = catalog.favoriteIds,
          favoriteTracks = catalog.favoriteTracks,
          recentTracks = catalog.recentTracks,
          trackCatalog = catalog.trackCatalog,
          playback = playback,
          isLoading = false,
          hasAudioPermission = catalog.hasAudioPermission,
          permissionRequestPending = local.permissionRequestPending,
          isScanning = local.isScanning,
          errorCode = local.errorCode,
        )
      }
      .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

  init {
    viewModelScope.launch {
      repository.observeFavoriteTrackIds().collect { favoriteIds ->
        persistedFavoriteIds.value = favoriteIds
        favoriteAppliedStates.entries.removeAll { (trackId, applied) ->
          (trackId in favoriteIds) == applied
        }
        updateLocal {
          val unsettled =
            pendingFavoriteDesired.filterNot { (trackId, desired) ->
              (trackId in favoriteIds) == desired
            }
          if (unsettled == pendingFavoriteDesired) this
          else copy(pendingFavoriteDesired = unsettled)
        }
      }
    }
  }

  fun onAction(action: MusicBoxAction) {
    when (action) {
      is MusicBoxAction.SelectDestination -> updateLocal {
        copy(activeDestination = action.destination)
      }
      MusicBoxAction.RequestAudioPermission -> updateLocal {
        copy(permissionRequestPending = true)
      }
      is MusicBoxAction.AudioPermissionResult ->
        handlePermissionResult(action.granted, action.userInitiated)
      MusicBoxAction.Rescan -> requestScan()
      is MusicBoxAction.PlayTrack -> playTrack(action.trackId, action.collection)
      is MusicBoxAction.PlayAll -> playCollection(action.collection, shuffle = false)
      is MusicBoxAction.PlayRandom -> playCollection(action.collection, shuffle = true)
      MusicBoxAction.TogglePlayPause -> playbackController.togglePlayPause()
      MusicBoxAction.Previous -> playbackController.seekToPrevious()
      MusicBoxAction.Next -> playbackController.seekToNext()
      is MusicBoxAction.SeekTo -> playbackController.seekTo(action.positionMs.coerceAtLeast(0))
      is MusicBoxAction.AddToQueue -> uiState.value.trackCatalog[action.trackId]?.let {
        playbackController.addToQueue(it)
      }
      is MusicBoxAction.RemoveQueueItem -> playbackController.removeQueueItem(action.index)
      is MusicBoxAction.JumpToQueueItem -> playbackController.skipToQueueItem(action.index)
      is MusicBoxAction.ToggleFavorite -> toggleFavorite(action.trackId)
    }
  }

  private fun handlePermissionResult(granted: Boolean, userInitiated: Boolean) {
    val wasGranted = localState.value.hasAudioPermission
    if (!granted) {
      scanJob?.cancel()
      scanJob = null
    }
    updateLocal {
      val shouldExposeDenial = !granted && (userInitiated || wasGranted)
      val nextError =
        when {
          shouldExposeDenial -> LibraryErrorCode.PERMISSION_REQUIRED
          granted && errorCode == LibraryErrorCode.PERMISSION_REQUIRED -> null
          else -> errorCode
        }
      copy(
        hasAudioPermission = granted,
        permissionRequestPending = false,
        isScanning = if (granted) isScanning else false,
        errorCode = nextError,
      )
    }
    if (granted && !wasGranted) requestScan()
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
    val currentDesired =
      localState.value.pendingFavoriteDesired[trackId]
        ?: favoriteAppliedStates[trackId]
        ?: (trackId in persistedFavoriteIds.value)
    updateLocal {
      copy(pendingFavoriteDesired = pendingFavoriteDesired + (trackId to !currentDesired))
    }
    startFavoriteWorker(trackId)
  }

  private fun startFavoriteWorker(trackId: TrackId) {
    if (favoriteWorkers[trackId]?.isActive == true) return
    lateinit var worker: Job
    worker =
      viewModelScope.launch(start = CoroutineStart.LAZY) {
        try {
          processFavoriteIntent(trackId)
        } finally {
          if (favoriteWorkers[trackId] === worker) favoriteWorkers.remove(trackId)
        }
      }
    favoriteWorkers[trackId] = worker
    worker.start()
  }

  private suspend fun processFavoriteIntent(trackId: TrackId) {
    var applied = favoriteAppliedStates[trackId] ?: (trackId in persistedFavoriteIds.value)
    while (true) {
      val desired = localState.value.pendingFavoriteDesired[trackId] ?: return
      if (desired == applied) {
        if ((trackId in persistedFavoriteIds.value) == applied) {
          clearSettledFavoriteIntent(trackId, applied)
        }
        return
      }

      val updated =
        try {
          repository.setFavorite(trackId, desired)
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (_: Exception) {
          false
        }
      if (!updated) {
        handleFavoriteFailure(trackId, applied)
        return
      }

      applied = desired
      favoriteAppliedStates[trackId] = applied
      updateLocal {
        if (errorCode == LibraryErrorCode.FAVORITE_UPDATE_FAILED) copy(errorCode = null) else this
      }
      recordFavoriteChanged(trackId)

      val latestDesired = localState.value.pendingFavoriteDesired[trackId]
      if (latestDesired == null || latestDesired == applied) return
    }
  }

  private fun clearSettledFavoriteIntent(trackId: TrackId, settledValue: Boolean) {
    updateLocal {
      if (pendingFavoriteDesired[trackId] != settledValue) return@updateLocal this
      copy(pendingFavoriteDesired = pendingFavoriteDesired - trackId)
    }
  }

  private fun handleFavoriteFailure(trackId: TrackId, applied: Boolean) {
    val latestDesired = localState.value.pendingFavoriteDesired[trackId]
    val persistedMatchesApplied = (trackId in persistedFavoriteIds.value) == applied
    if (persistedMatchesApplied) favoriteAppliedStates.remove(trackId)
    else favoriteAppliedStates[trackId] = applied
    updateLocal {
      val revertedPending =
        if (persistedMatchesApplied) pendingFavoriteDesired - trackId
        else pendingFavoriteDesired + (trackId to applied)
      copy(
        pendingFavoriteDesired = revertedPending,
        errorCode =
          if (latestDesired == applied) errorCode else LibraryErrorCode.FAVORITE_UPDATE_FAILED,
      )
    }
  }

  private suspend fun recordFavoriteChanged(trackId: TrackId) {
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
  val pendingFavoriteDesired: Map<TrackId, Boolean> = emptyMap(),
)

private data class CatalogControls(
  val hasAudioPermission: Boolean,
  val pendingFavoriteDesired: Map<TrackId, Boolean>,
)

private data class RepositoryCatalog(
  val library: LibraryContent,
  val favoriteIds: Set<TrackId>,
  val favoriteTracks: List<Track>,
  val recentTracks: List<Track>,
)

private data class CatalogSnapshot(
  val hasAudioPermission: Boolean,
  val library: LibraryContent,
  val favoriteIds: Set<TrackId>,
  val favoriteTracks: List<Track>,
  val recentTracks: List<Track>,
  val trackCatalog: Map<TrackId, Track>,
)

private fun buildCatalogSnapshot(
  data: RepositoryCatalog,
  controls: CatalogControls,
  demoTracks: List<Track>,
): CatalogSnapshot {
  val canRead: (Track) -> Boolean = { track -> controls.hasAudioPermission || track.isDemo }
  val library =
    if (controls.hasAudioPermission) {
      data.library
    } else {
      LibraryContent(LibrarySource.DEMO, demoTracks.filter(Track::isDemo))
    }
  val readableFavorites = data.favoriteTracks.filter(canRead)
  val recentTracks = data.recentTracks.filter(canRead)
  val knownTracks =
    (library.tracks + readableFavorites + recentTracks).associateByTo(linkedMapOf(), Track::id)
  val effectiveFavoriteIds =
    data.favoriteIds.filterTo(linkedSetOf()) { trackId -> trackId in knownTracks }
  controls.pendingFavoriteDesired.forEach { (trackId, desired) ->
    if (desired && trackId in knownTracks) effectiveFavoriteIds += trackId
    else effectiveFavoriteIds -= trackId
  }
  val favoriteTracks =
    buildList {
      readableFavorites.filterTo(this) { it.id in effectiveFavoriteIds }
      effectiveFavoriteIds.forEach { trackId ->
        val track = knownTracks[trackId]
        if (track != null && none { it.id == trackId }) add(track)
      }
    }
  val trackCatalog =
    (library.tracks + favoriteTracks + recentTracks).associateByTo(linkedMapOf(), Track::id)
  return CatalogSnapshot(
    hasAudioPermission = controls.hasAudioPermission,
    library = library,
    favoriteIds = effectiveFavoriteIds,
    favoriteTracks = favoriteTracks,
    recentTracks = recentTracks,
    trackCatalog = trackCatalog,
  )
}
