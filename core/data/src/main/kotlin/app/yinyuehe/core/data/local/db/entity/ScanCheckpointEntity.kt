package app.yinyuehe.core.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scan_checkpoints")
data class ScanCheckpointEntity(
  @PrimaryKey val volumeName: String,
  val mediaStoreVersion: String?,
  val generationUpperBound: Long?,
  @ColumnInfo(defaultValue = "0") val lastFullScanEpochMs: Long = 0,
  @ColumnInfo(defaultValue = "0") val lastSuccessfulScanEpochMs: Long = 0,
  val lastScanToken: String,
  @ColumnInfo(defaultValue = "1") val isMounted: Boolean = true,
  @ColumnInfo(defaultValue = "0") val lastDiscoveredCount: Long = 0,
  @ColumnInfo(defaultValue = "0") val lastInsertedCount: Long = 0,
  @ColumnInfo(defaultValue = "0") val lastUpdatedCount: Long = 0,
  @ColumnInfo(defaultValue = "0") val lastUnavailableCount: Long = 0,
)
