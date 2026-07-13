package app.yinyuehe.core.testing

import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.data.TrackRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeTrackRepository(initialTracks: List<Track> = emptyList()) : TrackRepository {
  private val tracks = MutableStateFlow(initialTracks)

  override fun observeTracks(): Flow<List<Track>> = tracks

  fun setTracks(value: List<Track>) {
    tracks.value = value
  }
}
