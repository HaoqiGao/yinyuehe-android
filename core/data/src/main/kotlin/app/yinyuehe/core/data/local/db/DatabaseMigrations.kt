package app.yinyuehe.core.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 =
  object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
      database.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `playback_events` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `name` TEXT NOT NULL,
          `trackId` TEXT,
          `occurredAtEpochMs` INTEGER NOT NULL,
          `durationMs` INTEGER
        )
        """.trimIndent()
      )
      database.execSQL(
        """
        CREATE INDEX IF NOT EXISTS `index_playback_events_occurredAtEpochMs`
        ON `playback_events` (`occurredAtEpochMs`)
        """.trimIndent()
      )
      database.execSQL(
        """
        CREATE INDEX IF NOT EXISTS `index_playback_events_name`
        ON `playback_events` (`name`)
        """.trimIndent()
      )
    }
  }
