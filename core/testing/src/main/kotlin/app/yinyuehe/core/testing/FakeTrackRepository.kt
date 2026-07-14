package app.yinyuehe.core.testing

import app.yinyuehe.core.common.model.LibraryContent
import app.yinyuehe.core.common.model.LibrarySource
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.data.TrackRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

class FakeTrackRepository(initialTracks: List<Track> = emptyList()) : TrackRepository {
  private val initialTracksAreDemo = initialTracks.isNotEmpty() && initialTracks.all { it.isDemo }
  private val localTracks =
    MutableStateFlow(if (initialTracksAreDemo) emptyList() else initialTracks)
  private val demos = MutableStateFlow(if (initialTracksAreDemo) initialTracks else emptyList())

  override fun observeAvailableLocalTracks(): Flow<List<Track>> = localTracks

  override fun demoTracks(): List<Track> = demos.value

  override fun observeLibrary(): Flow<LibraryContent> =
    combine(localTracks, demos) { localTracks, demoTracks ->
        if (localTracks.isEmpty()) {
          LibraryContent(LibrarySource.DEMO, demoTracks)
        } else {
          LibraryContent(LibrarySource.LOCAL, localTracks)
        }
      }
      .distinctUntilChanged()

  fun setTracks(value: List<Track>) {
    if (value.isNotEmpty() && value.all { it.isDemo }) {
      demos.value = value
      localTracks.value = emptyList()
    } else {
      localTracks.value = value
      demos.value = emptyList()
    }
  }

  fun setLocalTracks(value: List<Track>) {
    localTracks.value = value
  }

  fun setDemoTracks(value: List<Track>) {
    demos.value = value
  }
}
