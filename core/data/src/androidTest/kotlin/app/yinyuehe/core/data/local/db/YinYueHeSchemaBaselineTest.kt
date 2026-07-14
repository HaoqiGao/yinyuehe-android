package app.yinyuehe.core.data.local.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YinYueHeSchemaBaselineTest {
  private val databaseName = "schema-v1-baseline"

  @get:Rule
  val helper =
    MigrationTestHelper(
      InstrumentationRegistry.getInstrumentation(),
      YinYueHeDatabase::class.java,
    )

  @Test
  fun exportedVersion1Schema_canBeCreatedAndOpened() {
    helper.createDatabase(databaseName, 1).close()
    val database =
      Room.databaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        YinYueHeDatabase::class.java,
        databaseName,
      )
        .addMigrations(MIGRATION_1_2)
        .build()
    try {
      database.openHelper.writableDatabase
    } finally {
      database.close()
    }
  }
}
