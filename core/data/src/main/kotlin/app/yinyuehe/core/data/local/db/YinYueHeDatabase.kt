package app.yinyuehe.core.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import app.yinyuehe.core.data.local.db.dao.FavoriteDao
import app.yinyuehe.core.data.local.db.dao.PlaybackEventDao
import app.yinyuehe.core.data.local.db.dao.RecentPlayDao
import app.yinyuehe.core.data.local.db.dao.ScanCheckpointDao
import app.yinyuehe.core.data.local.db.dao.TrackDao
import app.yinyuehe.core.data.local.db.entity.FavoriteEntity
import app.yinyuehe.core.data.local.db.entity.PlaybackEventEntity
import app.yinyuehe.core.data.local.db.entity.RecentPlayEntity
import app.yinyuehe.core.data.local.db.entity.ScanCheckpointEntity
import app.yinyuehe.core.data.local.db.entity.TrackEntity

@Database(
  entities = [
    TrackEntity::class,
    FavoriteEntity::class,
    PlaybackEventEntity::class,
    RecentPlayEntity::class,
    ScanCheckpointEntity::class,
  ],
  version = 2,
  exportSchema = true,
)
abstract class YinYueHeDatabase : RoomDatabase() {
  abstract fun trackDao(): TrackDao

  abstract fun favoriteDao(): FavoriteDao

  abstract fun playbackEventDao(): PlaybackEventDao

  abstract fun recentPlayDao(): RecentPlayDao

  abstract fun scanCheckpointDao(): ScanCheckpointDao
}
