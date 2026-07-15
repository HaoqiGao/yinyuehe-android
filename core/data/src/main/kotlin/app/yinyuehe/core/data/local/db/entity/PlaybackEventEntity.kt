package app.yinyuehe.core.data.local.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "playback_events",
  indices = [Index("occurredAtEpochMs"), Index("name")],
)
data class PlaybackEventEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val name: String,
  val trackId: String?,
  val occurredAtEpochMs: Long,
  val durationMs: Long?,
)
