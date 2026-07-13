package app.yinyuehe.core.data.local.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
  tableName = "favorites",
  foreignKeys = [
    ForeignKey(
      entity = TrackEntity::class,
      parentColumns = ["mediaId"],
      childColumns = ["trackId"],
      onDelete = ForeignKey.CASCADE,
    )
  ],
)
data class FavoriteEntity(
  @PrimaryKey val trackId: String,
  val addedAtEpochMs: Long,
)
