package app.yinyuehe.core.data.repository

import app.yinyuehe.core.common.model.LibraryContent
import app.yinyuehe.core.common.model.LibrarySource
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.data.DemoTrackCatalog
import app.yinyuehe.core.data.TrackRepository
import app.yinyuehe.core.data.local.db.DEMO_VOLUME_NAME
import app.yinyuehe.core.data.local.db.dao.FavoriteDao
import app.yinyuehe.core.data.local.db.dao.RecentPlayDao
import app.yinyuehe.core.data.local.db.dao.TrackDao
import app.yinyuehe.core.data.local.db.entity.FavoriteEntity
import app.yinyuehe.core.data.local.db.entity.TrackEntity
import app.yinyuehe.core.data.local.db.toDemoEntity
import app.yinyuehe.core.data.local.db.toDomain
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Singleton
class RoomTrackRepository @Inject internal constructor(
  private val trackDao: TrackDao,
  private val favoriteDao: FavoriteDao,
  private val recentPlayDao: RecentPlayDao,
  demoCatalog: DemoTrackCatalog,
) : TrackRepository {
  private val demos = demoCatalog.tracks()
  private val demosById = demos.associateBy(Track::id)
  private val demoAnchors: Map<TrackId, TrackEntity> =
    demos.associate { track -> track.id to track.toDemoEntity() }

  override fun observeAvailableLocalTracks(): Flow<List<Track>> =
    trackDao
      .observeAvailableTracks(excludedVolumeName = DEMO_VOLUME_NAME)
      .map { entities -> entities.map { it.toDomain() } }
      .distinctUntilChanged()

  override fun demoTracks(): List<Track> = demos

  override fun observeFavoriteTrackIds(): Flow<Set<TrackId>> =
    favoriteDao
      .observeTrackIds()
      .map { ids -> ids.mapTo(linkedSetOf(), ::TrackId) }
      .distinctUntilChanged()

  override fun observeFavoriteTracks(): Flow<List<Track>> =
    favoriteDao
      .observeFavoriteTracks()
      .map { entities -> entities.mapNotNull(::mapStoredTrack) }
      .distinctUntilChanged()

  override fun observeRecentTracks(): Flow<List<Track>> =
    recentPlayDao
      .observeRecentTracks()
      .map { entities -> entities.mapNotNull(::mapStoredTrack) }
      .distinctUntilChanged()

  override suspend fun setFavorite(trackId: TrackId, favorite: Boolean): Boolean {
    if (!ensureTrackExists(trackId)) return false
    if (favorite) {
      favoriteDao.upsert(FavoriteEntity(trackId.value, System.currentTimeMillis()))
    } else {
      favoriteDao.delete(trackId.value)
    }
    return true
  }

  override suspend fun recordRecent(trackId: TrackId, positionMs: Long?): Boolean {
    if (!ensureTrackExists(trackId)) return false
    recentPlayDao.recordRecent(
      trackId = trackId.value,
      playedAtEpochMs = System.currentTimeMillis(),
      positionMs = positionMs?.coerceAtLeast(0),
    )
    return true
  }

  private suspend fun ensureTrackExists(trackId: TrackId): Boolean {
    demoAnchors[trackId]?.let { anchor ->
      trackDao.upsertTracks(listOf(anchor))
      return true
    }
    return trackDao.findByMediaId(trackId.value) != null
  }

  private fun mapStoredTrack(entity: TrackEntity): Track? =
    if (entity.volumeName == DEMO_VOLUME_NAME) {
      demosById[TrackId(entity.mediaId)]
    } else {
      entity.toDomain()
    }

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
