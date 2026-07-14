package app.yinyuehe.core.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.yinyuehe.core.data.local.db.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
  @Query(
    """
    SELECT * FROM tracks
    WHERE isAvailable = 1
      AND volumeName != :excludedVolumeName
    ORDER BY titleSortKey ASC, mediaId ASC
    """
  )
  fun observeAvailableTracks(excludedVolumeName: String): Flow<List<TrackEntity>>

  @Query("SELECT * FROM tracks WHERE mediaId = :mediaId")
  suspend fun findByMediaId(mediaId: String): TrackEntity?

  @Query("SELECT * FROM tracks ORDER BY mediaId")
  suspend fun getAll(): List<TrackEntity>

  @Upsert
  suspend fun upsertTracks(tracks: List<TrackEntity>)

  @Query("DELETE FROM tracks WHERE mediaId = :mediaId")
  suspend fun deleteByMediaId(mediaId: String)

  @Query(
    """
    UPDATE tracks
    SET isAvailable = 0
    WHERE volumeName = :volumeName
      AND lastSeenScanToken != :scanToken
      AND isAvailable = 1
    """
  )
  suspend fun markUnavailableNotSeenInScan(volumeName: String, scanToken: String): Int
}
