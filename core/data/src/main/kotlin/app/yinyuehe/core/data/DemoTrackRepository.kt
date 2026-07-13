package app.yinyuehe.core.data

import app.yinyuehe.core.common.model.Track
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class DemoTrackRepository @Inject internal constructor(
  catalog: DemoTrackCatalog,
) : TrackRepository {
  private val tracks = catalog.tracks()

  override fun observeTracks(): Flow<List<Track>> = flowOf(tracks)
}
