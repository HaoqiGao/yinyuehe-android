package app.yinyuehe.core.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import app.yinyuehe.core.data.local.db.entity.PlaybackEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackEventDao {
  @Insert suspend fun insert(event: PlaybackEventEntity): Long

  @Query(
    """
    SELECT * FROM playback_events
    ORDER BY occurredAtEpochMs DESC, id DESC
    """
  )
  fun observeEvents(): Flow<List<PlaybackEventEntity>>

  @Query(
    """
    DELETE FROM playback_events
    WHERE id NOT IN (
      SELECT id FROM playback_events
      ORDER BY occurredAtEpochMs DESC, id DESC
      LIMIT :maxRows
    )
    """
  )
  suspend fun trimToNewest(maxRows: Int): Int

  @Transaction
  suspend fun insertAndTrim(event: PlaybackEventEntity, maxRows: Int) {
    insert(event)
    trimToNewest(maxRows)
  }
}
