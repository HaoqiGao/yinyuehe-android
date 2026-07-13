package app.yinyuehe.core.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.yinyuehe.core.data.local.db.entity.TrackEntity
import org.junit.rules.TestWatcher
import org.junit.runner.Description

class RoomDatabaseRule : TestWatcher() {
  lateinit var database: YinYueHeDatabase
    private set

  override fun starting(description: Description) {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database =
      Room.inMemoryDatabaseBuilder(context, YinYueHeDatabase::class.java)
        .allowMainThreadQueries()
        .build()
  }

  override fun finished(description: Description) {
    if (::database.isInitialized) {
      database.close()
    }
  }
}

internal fun trackEntity(
  mediaId: String = "local:one",
  volumeName: String = "external_primary",
  mediaStoreId: Long = mediaId.hashCode().toLong(),
  contentUri: String = "content://media/$mediaId",
  displayName: String? = "$mediaId.mp3",
  title: String? = mediaId,
  artist: String? = "Artist",
  album: String? = "Album",
  albumId: Long? = 7L,
  artworkUri: String? = "content://media/artwork/7",
  durationMs: Long = 1_000L,
  mimeType: String? = "audio/mpeg",
  sizeBytes: Long = 2_000L,
  folderKey: String? = "music",
  folderDisplayName: String? = "Music",
  dateAddedSeconds: Long = 3_000L,
  dateModifiedSeconds: Long = 4_000L,
  searchText: String = "$mediaId artist album music",
  titleSortKey: String = mediaId,
  artistSortKey: String = "artist",
  albumSortKey: String = "album",
  folderSortKey: String = "music",
  metadataFingerprint: String = "fingerprint:$mediaId",
  isAvailable: Boolean = true,
  lastSeenScanToken: String = "scan-1",
) =
  TrackEntity(
    mediaId = mediaId,
    volumeName = volumeName,
    mediaStoreId = mediaStoreId,
    contentUri = contentUri,
    displayName = displayName,
    title = title,
    artist = artist,
    album = album,
    albumId = albumId,
    artworkUri = artworkUri,
    durationMs = durationMs,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    folderKey = folderKey,
    folderDisplayName = folderDisplayName,
    dateAddedSeconds = dateAddedSeconds,
    dateModifiedSeconds = dateModifiedSeconds,
    searchText = searchText,
    titleSortKey = titleSortKey,
    artistSortKey = artistSortKey,
    albumSortKey = albumSortKey,
    folderSortKey = folderSortKey,
    metadataFingerprint = metadataFingerprint,
    isAvailable = isAvailable,
    lastSeenScanToken = lastSeenScanToken,
  )
