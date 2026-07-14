package app.yinyuehe.core.data.local.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YinYueHeMigration1To2Test {
  private val databaseName = "migration-1-to-2"

  @get:Rule
  val helper =
    MigrationTestHelper(
      InstrumentationRegistry.getInstrumentation(),
      YinYueHeDatabase::class.java,
    )

  @Test
  fun migration_addsPlaybackEventsWithoutChangingVersionOneUserData() {
    helper.createDatabase(databaseName, 1).apply {
      insertVersionOneTrack()
      execSQL("INSERT INTO favorites(trackId, addedAtEpochMs) VALUES('local:one', 10)")
      execSQL(
        """
        INSERT INTO recent_plays(trackId, lastPlayedAtEpochMs, playCount, lastPositionMs)
        VALUES('local:one', 20, 2, 300)
        """.trimIndent()
      )
      execSQL(
        """
        INSERT INTO scan_checkpoints(volumeName, lastScanToken)
        VALUES('external_primary', 'scan-1')
        """.trimIndent()
      )
      close()
    }

    val migrated = helper.runMigrationsAndValidate(databaseName, 2, true, MIGRATION_1_2)

    assertEquals("Before migration", migrated.singleString("SELECT title FROM tracks"))
    assertEquals(10L, migrated.singleLong("SELECT addedAtEpochMs FROM favorites"))
    assertEquals(2L, migrated.singleLong("SELECT playCount FROM recent_plays"))
    assertEquals(300L, migrated.singleLong("SELECT lastPositionMs FROM recent_plays"))
    assertEquals("scan-1", migrated.singleString("SELECT lastScanToken FROM scan_checkpoints"))
    assertEquals(0, migrated.singleInt("SELECT COUNT(*) FROM playback_events"))
    migrated.close()
  }

  private fun SupportSQLiteDatabase.insertVersionOneTrack() {
    execSQL(
      """
      INSERT INTO tracks(mediaId, volumeName, mediaStoreId, contentUri, title, lastSeenScanToken)
      VALUES(
        'local:one',
        'external_primary',
        1,
        'content://media/1',
        'Before migration',
        'scan-1'
      )
      """.trimIndent()
    )
  }
}

private fun SupportSQLiteDatabase.singleInt(query: String): Int =
  query(query).use { cursor ->
    check(cursor.moveToFirst()) { "Expected one result row" }
    cursor.getInt(0)
  }

private fun SupportSQLiteDatabase.singleLong(query: String): Long =
  query(query).use { cursor ->
    check(cursor.moveToFirst()) { "Expected one result row" }
    cursor.getLong(0)
  }

private fun SupportSQLiteDatabase.singleString(query: String): String =
  query(query).use { cursor ->
    check(cursor.moveToFirst()) { "Expected one result row" }
    cursor.getString(0)
  }
