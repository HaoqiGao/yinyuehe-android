package app.yinyuehe.core.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.yinyuehe.core.common.model.Track

internal fun Track.toMediaItem(): MediaItem =
  MediaItem.Builder()
    .setMediaId(id.value)
    .setUri(sourceUri)
    .setMediaMetadata(
      MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setArtworkUri(artworkUri?.let(Uri::parse))
        .setDurationMs(durationMs)
        .setIsPlayable(true)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .build()
    )
    .build()
