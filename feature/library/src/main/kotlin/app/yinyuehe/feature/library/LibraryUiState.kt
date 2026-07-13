package app.yinyuehe.feature.library

import app.yinyuehe.core.common.model.Track

data class LibraryUiState(
  val isLoading: Boolean = true,
  val tracks: List<Track> = emptyList(),
  val playbackError: PlaybackError? = null,
)

enum class PlaybackError {
  CONNECTION_FAILED,
  PLAYBACK_FAILED,
}
