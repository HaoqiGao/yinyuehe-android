package app.yinyuehe.core.testing

import app.yinyuehe.core.common.model.LibraryContent
import app.yinyuehe.core.common.model.LibrarySource
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
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
  private val favoriteTrackIds = MutableStateFlow<Set<TrackId>>(emptySet())
  private val recentTracks = MutableStateFlow<List<Track>>(emptyList())

  override fun observeAvailableLocalTracks(): Flow<List<Track>> = localTracks

  override fun demoTracks(): List<Track> = demos.value

  override fun observeFavoriteTrackIds(): Flow<Set<TrackId>> = favoriteTrackIds

  override fun observeFavoriteTracks(): Flow<List<Track>> =
    combine(localTracks, favoriteTrackIds) { tracks, favoriteIds ->
        favoriteIds.mapNotNull { id -> tracks.find { it.id == id } }
      }
      .distinctUntilChanged()

  override fun observeRecentTracks(): Flow<List<Track>> = recentTracks

  override suspend fun setFavorite(trackId: TrackId, favorite: Boolean): Boolean {
    if (localTracks.value.none { it.id == trackId }) return false
    favoriteTrackIds.value =
      if (favorite) favoriteTrackIds.value + trackId else favoriteTrackIds.value - trackId
    return true
  }

  override suspend fun recordRecent(trackId: TrackId, positionMs: Long?): Boolean {
    val track = localTracks.value.find { it.id == trackId } ?: return false
    recentTracks.value = listOf(track) + recentTracks.value.filterNot { it.id == trackId }.take(19)
    return true
  }

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
