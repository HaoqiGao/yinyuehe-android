package app.yinyuehe.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.data.TrackRepository
import app.yinyuehe.core.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class LibraryViewModel @Inject internal constructor(
  repository: TrackRepository,
  private val playbackController: PlaybackController,
) : ViewModel() {
  private val playbackError = MutableStateFlow<PlaybackError?>(null)
  private var playbackRequestJob: Job? = null

  val uiState: StateFlow<LibraryUiState> =
    combine(repository.observeTracks(), playbackError) { tracks, error ->
        LibraryUiState(isLoading = false, tracks = tracks, playbackError = error)
      }
      .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

  fun onTrackClick(trackId: TrackId) {
    val tracks = uiState.value.tracks
    val index = tracks.indexOfFirst { it.id == trackId }
    if (index < 0) return
    playbackRequestJob?.cancel()
    playbackRequestJob =
      viewModelScope.launch {
        try {
          val accepted = playbackController.play(tracks, index)
          currentCoroutineContext().ensureActive()
          playbackError.value =
            if (accepted) null else PlaybackError.CONNECTION_FAILED
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (_: Exception) {
          playbackError.value = PlaybackError.PLAYBACK_FAILED
        }
      }
  }
}
