package app.yinyuehe.core.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.yinyuehe.core.data.local.db.entity.FavoriteEntity
import app.yinyuehe.core.data.local.db.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
  @Upsert
  suspend fun upsert(entity: FavoriteEntity)

  @Query("SELECT * FROM favorites WHERE trackId = :trackId")
  suspend fun find(trackId: String): FavoriteEntity?

  @Query("SELECT trackId FROM favorites ORDER BY trackId ASC")
  fun observeTrackIds(): Flow<List<String>>

  @Query(
    """
    SELECT tracks.* FROM tracks
    INNER JOIN favorites ON favorites.trackId = tracks.mediaId
    WHERE tracks.isAvailable = 1
    ORDER BY favorites.addedAtEpochMs DESC, tracks.mediaId ASC
    """
  )
  fun observeFavoriteTracks(): Flow<List<TrackEntity>>

  @Query("DELETE FROM favorites WHERE trackId = :trackId")
  suspend fun delete(trackId: String): Int
}
