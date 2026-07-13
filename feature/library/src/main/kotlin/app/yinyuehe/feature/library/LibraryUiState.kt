package app.yinyuehe.feature.library

import app.yinyuehe.core.common.model.Track

data class LibraryUiState(
  val isLoading: Boolean = true,
  val tracks: List<Track> = emptyList(),
)
