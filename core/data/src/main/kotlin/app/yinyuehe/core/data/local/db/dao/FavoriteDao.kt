package app.yinyuehe.core.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.yinyuehe.core.data.local.db.entity.FavoriteEntity

@Dao
interface FavoriteDao {
  @Upsert
  suspend fun upsert(entity: FavoriteEntity)

  @Query("SELECT * FROM favorites WHERE trackId = :trackId")
  suspend fun find(trackId: String): FavoriteEntity?
}
