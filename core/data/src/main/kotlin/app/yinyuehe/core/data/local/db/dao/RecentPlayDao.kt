package app.yinyuehe.core.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.yinyuehe.core.data.local.db.entity.RecentPlayEntity

@Dao
interface RecentPlayDao {
  @Upsert
  suspend fun upsert(entity: RecentPlayEntity)

  @Query("SELECT * FROM recent_plays WHERE trackId = :trackId")
  suspend fun find(trackId: String): RecentPlayEntity?
}
