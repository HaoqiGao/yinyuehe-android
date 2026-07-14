package app.yinyuehe.core.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.yinyuehe.core.data.local.db.entity.ScanCheckpointEntity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class YinYueHeDatabaseTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test
  fun checkpoint_isUpsertedReadAndOrderedByVolumeName() = runTest {
    val database =
      Room.inMemoryDatabaseBuilder(context, YinYueHeDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    try {
      val dao = database.scanCheckpointDao()
      val secondary = checkpoint(volumeName = "secondary", lastScanToken = "scan-secondary")
      val initialPrimary = checkpoint(volumeName = "external_primary", lastScanToken = "scan-old")
      val updatedPrimary =
        initialPrimary.copy(
          mediaStoreVersion = "v2",
          generationUpperBound = 99L,
          lastScanToken = "scan-new",
          lastUpdatedCount = 3L,
        )

      dao.upsert(secondary)
      dao.upsert(initialPrimary)
      dao.upsert(updatedPrimary)

      assertEquals(updatedPrimary, dao.find("external_primary"))
      assertEquals(listOf(updatedPrimary, secondary), dao.getAll())
    } finally {
      database.close()
    }
  }

  @Test
  fun fileBackedDatabase_keepsCachedTrackAfterCloseAndReopen() = runTest {
    val databaseName = "local-library-${UUID.randomUUID()}.db"
    val expected = trackEntity(mediaId = "local:persisted", title = "Persisted")
    context.deleteDatabase(databaseName)

    try {
      val database =
        Room.databaseBuilder(context, YinYueHeDatabase::class.java, databaseName)
          .allowMainThreadQueries()
          .build()
      try {
        database.trackDao().upsertTracks(listOf(expected))
      } finally {
        database.close()
      }

      val reopened =
        Room.databaseBuilder(context, YinYueHeDatabase::class.java, databaseName)
          .allowMainThreadQueries()
          .build()
      try {
        assertEquals(expected, reopened.trackDao().findByMediaId(expected.mediaId))
      } finally {
        reopened.close()
      }
    } finally {
      context.deleteDatabase(databaseName)
    }
  }

  @Test
  fun exportedV1Schema_declaresExactTablesColumnsAndDefaults() {
    val database = exportedSchema().getJSONObject("database")
    val entities = database.getJSONArray("entities").objectsBy("tableName")

    assertEquals(1, database.getInt("version"))
    assertEquals(
      setOf("tracks", "favorites", "recent_plays", "scan_checkpoints"),
      entities.keys,
    )
    assertEquals(
      trackColumns,
      entities.getValue("tracks").columns(),
    )
    assertEquals(
      mapOf(
        "trackId" to Column("TEXT", notNull = true),
        "addedAtEpochMs" to Column("INTEGER", notNull = true),
      ),
      entities.getValue("favorites").columns(),
    )
    assertEquals(
      mapOf(
        "trackId" to Column("TEXT", notNull = true),
        "lastPlayedAtEpochMs" to Column("INTEGER", notNull = true, defaultValue = "0"),
        "playCount" to Column("INTEGER", notNull = true, defaultValue = "0"),
        "lastPositionMs" to Column("INTEGER", notNull = false, defaultValue = "NULL"),
      ),
      entities.getValue("recent_plays").columns(),
    )
    assertEquals(
      checkpointColumns,
      entities.getValue("scan_checkpoints").columns(),
    )
    assertEquals(
      mapOf(
        "tracks" to listOf("mediaId"),
        "favorites" to listOf("trackId"),
        "recent_plays" to listOf("trackId"),
        "scan_checkpoints" to listOf("volumeName"),
      ),
      entities.mapValues { (_, entity) ->
        entity.getJSONObject("primaryKey").getJSONArray("columnNames").strings()
      },
    )
  }

  @Test
  fun exportedV1Schema_declaresExactIndicesAndForeignKeys() {
    val entities =
      exportedSchema()
        .getJSONObject("database")
        .getJSONArray("entities")
        .objectsBy("tableName")

    assertEquals(expectedTrackIndices, entities.getValue("tracks").indices())
    assertTrue(entities.getValue("favorites").indices().isEmpty())
    assertTrue(entities.getValue("recent_plays").indices().isEmpty())
    assertTrue(entities.getValue("scan_checkpoints").indices().isEmpty())

    assertTrue(entities.getValue("tracks").foreignKeys().isEmpty())
    assertEquals(
      listOf(
        SchemaForeignKey(
          table = "tracks",
          onDelete = "CASCADE",
          onUpdate = "NO ACTION",
          columns = listOf("trackId"),
          referencedColumns = listOf("mediaId"),
        )
      ),
      entities.getValue("favorites").foreignKeys(),
    )
    assertEquals(
      listOf(
        SchemaForeignKey(
          table = "tracks",
          onDelete = "CASCADE",
          onUpdate = "NO ACTION",
          columns = listOf("trackId"),
          referencedColumns = listOf("mediaId"),
        )
      ),
      entities.getValue("recent_plays").foreignKeys(),
    )
    assertTrue(entities.getValue("scan_checkpoints").foreignKeys().isEmpty())
  }

  private fun exportedSchema(): JSONObject {
    val relativePath =
      "schemas/app.yinyuehe.core.data.local.db.YinYueHeDatabase/1.json"
    var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile

    while (directory != null) {
      val candidates =
        listOf(
          File(directory, relativePath),
          File(directory, "core/data/$relativePath"),
        )
      candidates.firstOrNull(File::isFile)?.let { return JSONObject(it.readText()) }
      directory = directory.parentFile
    }

    throw AssertionError("Room v1 schema was not exported at $relativePath")
  }

  private fun checkpoint(
    volumeName: String,
    lastScanToken: String,
  ) =
    ScanCheckpointEntity(
      volumeName = volumeName,
      mediaStoreVersion = "v1",
      generationUpperBound = 10L,
      lastFullScanEpochMs = 20L,
      lastSuccessfulScanEpochMs = 30L,
      lastScanToken = lastScanToken,
      isMounted = true,
      lastDiscoveredCount = 40L,
      lastInsertedCount = 5L,
      lastUpdatedCount = 6L,
      lastUnavailableCount = 7L,
    )

  private data class Column(
    val affinity: String,
    val notNull: Boolean,
    val defaultValue: String? = null,
  )

  private data class SchemaIndex(
    val unique: Boolean,
    val columns: List<String>,
  )

  private data class SchemaForeignKey(
    val table: String,
    val onDelete: String,
    val onUpdate: String,
    val columns: List<String>,
    val referencedColumns: List<String>,
  )

  private fun JSONObject.columns(): Map<String, Column> =
    getJSONArray("fields")
      .objects()
      .associate { field ->
        field.getString("columnName") to
          Column(
            affinity = field.getString("affinity"),
            notNull = field.optBoolean("notNull", false),
            defaultValue =
              if (field.isNull("defaultValue")) {
                null
              } else {
                field.getString("defaultValue")
              },
          )
      }

  private fun JSONObject.indices(): Map<String, SchemaIndex> =
    (optJSONArray("indices")?.objects() ?: emptyList())
      .associate { index ->
        index.getString("name") to
          SchemaIndex(
            unique = index.getBoolean("unique"),
            columns = index.getJSONArray("columnNames").strings(),
          )
      }

  private fun JSONObject.foreignKeys(): List<SchemaForeignKey> =
    (optJSONArray("foreignKeys")?.objects() ?: emptyList())
      .map { foreignKey ->
        SchemaForeignKey(
          table = foreignKey.getString("table"),
          onDelete = foreignKey.getString("onDelete"),
          onUpdate = foreignKey.getString("onUpdate"),
          columns = foreignKey.getJSONArray("columns").strings(),
          referencedColumns = foreignKey.getJSONArray("referencedColumns").strings(),
        )
      }

  private fun org.json.JSONArray.objects(): List<JSONObject> =
    (0 until length()).map(::getJSONObject)

  private fun org.json.JSONArray.objectsBy(property: String): Map<String, JSONObject> =
    objects().associateBy { it.getString(property) }

  private fun org.json.JSONArray.strings(): List<String> =
    (0 until length()).map(::getString)

  private val trackColumns =
    mapOf(
      "mediaId" to Column("TEXT", notNull = true),
      "volumeName" to Column("TEXT", notNull = true),
      "mediaStoreId" to Column("INTEGER", notNull = true),
      "contentUri" to Column("TEXT", notNull = true),
      "displayName" to Column("TEXT", notNull = false),
      "title" to Column("TEXT", notNull = false),
      "artist" to Column("TEXT", notNull = false),
      "album" to Column("TEXT", notNull = false),
      "albumId" to Column("INTEGER", notNull = false),
      "artworkUri" to Column("TEXT", notNull = false),
      "durationMs" to Column("INTEGER", notNull = true, defaultValue = "0"),
      "mimeType" to Column("TEXT", notNull = false),
      "sizeBytes" to Column("INTEGER", notNull = true, defaultValue = "0"),
      "folderKey" to Column("TEXT", notNull = false),
      "folderDisplayName" to Column("TEXT", notNull = false),
      "dateAddedSeconds" to Column("INTEGER", notNull = true, defaultValue = "0"),
      "dateModifiedSeconds" to Column("INTEGER", notNull = true, defaultValue = "0"),
      "searchText" to Column("TEXT", notNull = true, defaultValue = "''"),
      "titleSortKey" to Column("TEXT", notNull = true, defaultValue = "''"),
      "artistSortKey" to Column("TEXT", notNull = true, defaultValue = "''"),
      "albumSortKey" to Column("TEXT", notNull = true, defaultValue = "''"),
      "folderSortKey" to Column("TEXT", notNull = true, defaultValue = "''"),
      "metadataFingerprint" to Column("TEXT", notNull = true, defaultValue = "''"),
      "isAvailable" to Column("INTEGER", notNull = true, defaultValue = "1"),
      "lastSeenScanToken" to Column("TEXT", notNull = true),
    )

  private val checkpointColumns =
    mapOf(
      "volumeName" to Column("TEXT", notNull = true),
      "mediaStoreVersion" to Column("TEXT", notNull = false),
      "generationUpperBound" to Column("INTEGER", notNull = false),
      "lastFullScanEpochMs" to Column("INTEGER", notNull = true, defaultValue = "0"),
      "lastSuccessfulScanEpochMs" to Column("INTEGER", notNull = true, defaultValue = "0"),
      "lastScanToken" to Column("TEXT", notNull = true),
      "isMounted" to Column("INTEGER", notNull = true, defaultValue = "1"),
      "lastDiscoveredCount" to Column("INTEGER", notNull = true, defaultValue = "0"),
      "lastInsertedCount" to Column("INTEGER", notNull = true, defaultValue = "0"),
      "lastUpdatedCount" to Column("INTEGER", notNull = true, defaultValue = "0"),
      "lastUnavailableCount" to Column("INTEGER", notNull = true, defaultValue = "0"),
    )

  private val expectedTrackIndices =
    mapOf(
      "index_tracks_volumeName_mediaStoreId" to
        SchemaIndex(unique = true, columns = listOf("volumeName", "mediaStoreId")),
      "index_tracks_isAvailable_titleSortKey_mediaId" to
        SchemaIndex(
          unique = false,
          columns = listOf("isAvailable", "titleSortKey", "mediaId"),
        ),
      "index_tracks_isAvailable_artistSortKey_mediaId" to
        SchemaIndex(
          unique = false,
          columns = listOf("isAvailable", "artistSortKey", "mediaId"),
        ),
      "index_tracks_isAvailable_albumSortKey_mediaId" to
        SchemaIndex(
          unique = false,
          columns = listOf("isAvailable", "albumSortKey", "mediaId"),
        ),
      "index_tracks_isAvailable_folderSortKey_mediaId" to
        SchemaIndex(
          unique = false,
          columns = listOf("isAvailable", "folderSortKey", "mediaId"),
        ),
      "index_tracks_isAvailable_dateAddedSeconds_mediaId" to
        SchemaIndex(
          unique = false,
          columns = listOf("isAvailable", "dateAddedSeconds", "mediaId"),
        ),
      "index_tracks_isAvailable_durationMs_mediaId" to
        SchemaIndex(
          unique = false,
          columns = listOf("isAvailable", "durationMs", "mediaId"),
        ),
      "index_tracks_volumeName_lastSeenScanToken" to
        SchemaIndex(
          unique = false,
          columns = listOf("volumeName", "lastSeenScanToken"),
        ),
    )
}
