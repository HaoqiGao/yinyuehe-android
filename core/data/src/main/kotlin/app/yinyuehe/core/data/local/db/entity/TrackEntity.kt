package app.yinyuehe.core.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "tracks",
  indices = [
    Index(value = ["volumeName", "mediaStoreId"], unique = true),
    Index(value = ["isAvailable", "titleSortKey", "mediaId"]),
    Index(value = ["isAvailable", "artistSortKey", "mediaId"]),
    Index(value = ["isAvailable", "albumSortKey", "mediaId"]),
    Index(value = ["isAvailable", "folderSortKey", "mediaId"]),
    Index(value = ["isAvailable", "dateAddedSeconds", "mediaId"]),
    Index(value = ["isAvailable", "durationMs", "mediaId"]),
    Index(value = ["volumeName", "lastSeenScanToken"]),
  ],
)
data class TrackEntity(
  @PrimaryKey val mediaId: String,
  val volumeName: String,
  val mediaStoreId: Long,
  val contentUri: String,
  val displayName: String?,
  val title: String?,
  val artist: String?,
  val album: String?,
  val albumId: Long?,
  val artworkUri: String?,
  @ColumnInfo(defaultValue = "0") val durationMs: Long = 0,
  val mimeType: String?,
  @ColumnInfo(defaultValue = "0") val sizeBytes: Long = 0,
  val folderKey: String?,
  val folderDisplayName: String?,
  @ColumnInfo(defaultValue = "0") val dateAddedSeconds: Long = 0,
  @ColumnInfo(defaultValue = "0") val dateModifiedSeconds: Long = 0,
  @ColumnInfo(defaultValue = "''") val searchText: String = "",
  @ColumnInfo(defaultValue = "''") val titleSortKey: String = "",
  @ColumnInfo(defaultValue = "''") val artistSortKey: String = "",
  @ColumnInfo(defaultValue = "''") val albumSortKey: String = "",
  @ColumnInfo(defaultValue = "''") val folderSortKey: String = "",
  @ColumnInfo(defaultValue = "''") val metadataFingerprint: String = "",
  @ColumnInfo(defaultValue = "1") val isAvailable: Boolean = true,
  val lastSeenScanToken: String,
)
