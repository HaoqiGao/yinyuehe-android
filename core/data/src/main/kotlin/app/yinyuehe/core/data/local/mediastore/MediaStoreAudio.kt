package app.yinyuehe.core.data.local.mediastore

import android.provider.MediaStore
import app.yinyuehe.core.data.local.db.entity.TrackEntity
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

internal data class MediaStoreAudio(
  val volumeName: String,
  val mediaStoreId: Long,
  val contentUri: String,
  val displayName: String?,
  val title: String?,
  val artist: String?,
  val album: String?,
  val albumId: Long?,
  val artworkUri: String?,
  val durationMs: Long,
  val mimeType: String?,
  val sizeBytes: Long,
  val dateAddedSeconds: Long,
  val dateModifiedSeconds: Long,
)

internal fun stableMediaId(volumeName: String, mediaStoreId: Long): String {
  require(volumeName.isNotBlank()) { "volumeName must not be blank" }
  require(mediaStoreId >= 0) { "mediaStoreId must not be negative" }
  val encodedVolume =
    Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(volumeName.toByteArray(StandardCharsets.UTF_8))
  return "local:v1:$encodedVolume:$mediaStoreId"
}

internal fun cleanMediaStoreText(value: String?): String? =
  value
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.takeUnless { it == MediaStore.UNKNOWN_STRING }

internal fun MediaStoreAudio.toTrackEntity(scanToken: String): TrackEntity {
  val normalizedTitle = normalizeForSearch(title)
  val normalizedArtist = normalizeForSearch(artist)
  val normalizedAlbum = normalizeForSearch(album)
  val searchText =
    listOf(normalizedTitle, normalizedArtist, normalizedAlbum)
      .filter(String::isNotEmpty)
      .joinToString(" ")
  val fingerprint =
    listOf(
        contentUri,
        displayName,
        title,
        artist,
        album,
        albumId,
        artworkUri,
        durationMs,
        mimeType,
        sizeBytes,
        dateAddedSeconds,
        dateModifiedSeconds,
      )
      .joinToString(separator = "\u001f", transform = Any?::toString)
  return TrackEntity(
    mediaId = stableMediaId(volumeName, mediaStoreId),
    volumeName = volumeName,
    mediaStoreId = mediaStoreId,
    contentUri = contentUri,
    displayName = cleanMediaStoreText(displayName),
    title = cleanMediaStoreText(title),
    artist = cleanMediaStoreText(artist),
    album = cleanMediaStoreText(album),
    albumId = albumId?.takeIf { it >= 0 },
    artworkUri = artworkUri?.takeIf { it.isNotBlank() },
    durationMs = durationMs.coerceAtLeast(0),
    mimeType = cleanMediaStoreText(mimeType),
    sizeBytes = sizeBytes.coerceAtLeast(0),
    folderKey = null,
    folderDisplayName = null,
    dateAddedSeconds = dateAddedSeconds.coerceAtLeast(0),
    dateModifiedSeconds = dateModifiedSeconds.coerceAtLeast(0),
    searchText = searchText,
    titleSortKey = normalizedTitle,
    artistSortKey = normalizedArtist,
    albumSortKey = normalizedAlbum,
    folderSortKey = "",
    metadataFingerprint = fingerprint,
    isAvailable = true,
    lastSeenScanToken = scanToken,
  )
}

private fun normalizeForSearch(value: String?): String =
  cleanMediaStoreText(value)
    ?.trim()
    ?.replace(Regex("\\s+"), " ")
    ?.lowercase(Locale.ROOT)
    .orEmpty()
