package app.yinyuehe.core.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
  tableName = "recent_plays",
  foreignKeys = [
    ForeignKey(
      entity = TrackEntity::class,
      parentColumns = ["mediaId"],
      childColumns = ["trackId"],
      onDelete = ForeignKey.CASCADE,
    )
  ],
)
data class RecentPlayEntity(
  @PrimaryKey val trackId: String,
  @ColumnInfo(defaultValue = "0") val lastPlayedAtEpochMs: Long = 0,
  @ColumnInfo(defaultValue = "0") val playCount: Long = 0,
  @ColumnInfo(defaultValue = "NULL") val lastPositionMs: Long? = null,
)
