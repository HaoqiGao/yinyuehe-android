package app.yinyuehe.core.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.yinyuehe.core.data.local.db.entity.ScanCheckpointEntity

@Dao
interface ScanCheckpointDao {
  @Upsert
  suspend fun upsert(entity: ScanCheckpointEntity)

  @Query("SELECT * FROM scan_checkpoints WHERE volumeName = :volumeName")
  suspend fun find(volumeName: String): ScanCheckpointEntity?

  @Query("SELECT * FROM scan_checkpoints ORDER BY volumeName")
  suspend fun getAll(): List<ScanCheckpointEntity>
}
