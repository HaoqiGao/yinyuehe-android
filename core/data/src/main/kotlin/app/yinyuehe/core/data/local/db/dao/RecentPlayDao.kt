package app.yinyuehe.core.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.yinyuehe.core.data.local.db.entity.RecentPlayEntity
import app.yinyuehe.core.data.local.db.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentPlayDao {
  @Upsert
  suspend fun upsert(entity: RecentPlayEntity)

  @Query("SELECT * FROM recent_plays WHERE trackId = :trackId")
  suspend fun find(trackId: String): RecentPlayEntity?

  @Transaction
  suspend fun recordRecent(trackId: String, playedAtEpochMs: Long, positionMs: Long?) {
    val existing = find(trackId)
    upsert(
      RecentPlayEntity(
        trackId = trackId,
        lastPlayedAtEpochMs = playedAtEpochMs,
        playCount = (existing?.playCount ?: 0) + 1,
        lastPositionMs = positionMs,
      )
    )
  }

  @Query(
    """
    SELECT tracks.* FROM tracks
    INNER JOIN recent_plays ON recent_plays.trackId = tracks.mediaId
    WHERE tracks.isAvailable = 1
    ORDER BY recent_plays.lastPlayedAtEpochMs DESC, tracks.mediaId ASC
    LIMIT 20
    """
  )
  fun observeRecentTracks(): Flow<List<TrackEntity>>
}
