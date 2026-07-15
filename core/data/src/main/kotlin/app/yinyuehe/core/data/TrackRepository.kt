package app.yinyuehe.core.data

import app.yinyuehe.core.common.model.LibraryContent
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface TrackRepository {
  fun observeAvailableLocalTracks(): Flow<List<Track>>

  fun demoTracks(): List<Track>

  fun observeLibrary(): Flow<LibraryContent>

  fun observeFavoriteTrackIds(): Flow<Set<TrackId>>

  fun observeFavoriteTracks(): Flow<List<Track>>

  fun observeRecentTracks(): Flow<List<Track>>

  suspend fun setFavorite(trackId: TrackId, favorite: Boolean): Boolean

  suspend fun recordRecent(trackId: TrackId, positionMs: Long? = null): Boolean

  fun observeTracks(): Flow<List<Track>> = observeLibrary().map { content -> content.tracks }
}
