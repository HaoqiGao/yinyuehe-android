package app.yinyuehe.core.data.repository

import app.yinyuehe.core.common.model.LibraryContent
import app.yinyuehe.core.common.model.LibrarySource
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.data.DemoTrackCatalog
import app.yinyuehe.core.data.TrackRepository
import app.yinyuehe.core.data.local.db.dao.TrackDao
import app.yinyuehe.core.data.local.db.toDomain
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Singleton
class RoomTrackRepository @Inject internal constructor(
  private val trackDao: TrackDao,
  demoCatalog: DemoTrackCatalog,
) : TrackRepository {
  private val demos = demoCatalog.tracks()

  override fun observeAvailableLocalTracks(): Flow<List<Track>> =
    trackDao
      .observeAvailableTracks()
      .map { entities -> entities.map { it.toDomain() } }
      .distinctUntilChanged()

  override fun demoTracks(): List<Track> = demos

  override fun observeLibrary(): Flow<LibraryContent> =
    observeAvailableLocalTracks()
      .map { localTracks ->
        if (localTracks.isEmpty()) {
          LibraryContent(LibrarySource.DEMO, demos)
        } else {
          LibraryContent(LibrarySource.LOCAL, localTracks)
        }
      }
      .distinctUntilChanged()
}
