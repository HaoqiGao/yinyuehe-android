package app.yinyuehe.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.data.TrackRepository
import app.yinyuehe.core.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class LibraryViewModel @Inject constructor(
  repository: TrackRepository,
  private val playbackController: PlaybackController,
) : ViewModel() {
  val uiState: StateFlow<LibraryUiState> =
    repository
      .observeTracks()
      .map { LibraryUiState(isLoading = false, tracks = it) }
      .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

  fun onTrackClick(trackId: TrackId) {
    val tracks = uiState.value.tracks
    val index = tracks.indexOfFirst { it.id == trackId }
    if (index < 0) return
    viewModelScope.launch { playbackController.play(tracks, index) }
  }
}
