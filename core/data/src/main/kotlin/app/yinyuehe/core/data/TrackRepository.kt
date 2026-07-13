package app.yinyuehe.core.data

import app.yinyuehe.core.common.model.LibraryContent
import app.yinyuehe.core.common.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface TrackRepository {
  fun observeAvailableLocalTracks(): Flow<List<Track>>

  fun demoTracks(): List<Track>

  fun observeLibrary(): Flow<LibraryContent>

  fun observeTracks(): Flow<List<Track>> = observeLibrary().map { content -> content.tracks }
}
