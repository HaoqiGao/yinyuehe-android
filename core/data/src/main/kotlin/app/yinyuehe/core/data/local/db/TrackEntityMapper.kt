package app.yinyuehe.core.data.local.db

import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.data.local.db.entity.TrackEntity

internal fun TrackEntity.toDomain(): Track =
  Track(
    id = TrackId(mediaId),
    title = title?.takeIf { it.isNotBlank() },
    artist = artist?.takeIf { it.isNotBlank() },
    album = album?.takeIf { it.isNotBlank() },
    durationMs = durationMs.takeIf { it > 0 },
    artworkUri = artworkUri?.takeIf { it.isNotBlank() },
    sourceUri = contentUri,
    isDemo = false,
    displayName = displayName?.takeIf { it.isNotBlank() },
    albumId = albumId?.takeIf { it >= 0 },
    mimeType = mimeType?.takeIf { it.isNotBlank() },
    sizeBytes = sizeBytes.coerceAtLeast(0),
    folderKey = folderKey?.takeIf { it.isNotBlank() },
    folderDisplayName = folderDisplayName?.takeIf { it.isNotBlank() },
    dateAddedSeconds = dateAddedSeconds.coerceAtLeast(0),
    dateModifiedSeconds = dateModifiedSeconds.coerceAtLeast(0),
  )
