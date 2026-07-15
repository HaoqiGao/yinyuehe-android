package app.yinyuehe.core.data.local.db

import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.data.local.db.entity.TrackEntity

internal const val DEMO_VOLUME_NAME = "__yinyuehe_demo__"

internal fun Track.toDemoEntity(): TrackEntity {
  require(isDemo) { "Only Demo tracks can be persisted as Demo anchors" }
  return TrackEntity(
    mediaId = id.value,
    volumeName = DEMO_VOLUME_NAME,
    mediaStoreId = id.stableDemoMediaStoreId(),
    contentUri = sourceUri,
    displayName = null,
    title = title,
    artist = artist,
    album = album,
    albumId = null,
    artworkUri = artworkUri,
    durationMs = durationMs ?: 0,
    mimeType = null,
    sizeBytes = 0,
    folderKey = null,
    folderDisplayName = null,
    dateAddedSeconds = 0,
    dateModifiedSeconds = 0,
    searchText = listOfNotNull(title, artist, album).joinToString(separator = " "),
    titleSortKey = title.orEmpty(),
    artistSortKey = artist.orEmpty(),
    albumSortKey = album.orEmpty(),
    folderSortKey = "",
    metadataFingerprint = "demo-catalog:${id.value}",
    isAvailable = true,
    lastSeenScanToken = "demo-catalog",
  )
}

private fun TrackId.stableDemoMediaStoreId(): Long {
  var hash = FNV_64_OFFSET_BASIS
  value.toByteArray(Charsets.UTF_8).forEach { byte ->
    hash = hash xor (byte.toLong() and 0xffL)
    hash *= FNV_64_PRIME
  }
  return hash
}

private const val FNV_64_OFFSET_BASIS = -3750763034362895579L
private const val FNV_64_PRIME = 1099511628211L
