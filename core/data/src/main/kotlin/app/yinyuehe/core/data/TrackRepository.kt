package app.yinyuehe.core.data

import app.yinyuehe.core.common.model.Track
import kotlinx.coroutines.flow.Flow

interface TrackRepository {
  fun observeTracks(): Flow<List<Track>>
}
