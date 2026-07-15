package app.yinyuehe.core.player.service

import android.os.Bundle
import androidx.media3.common.MediaItem
import java.util.concurrent.atomic.AtomicLong

@JvmInline
internal value class PlaybackOccurrenceToken(val value: Long)

internal class PlaybackOccurrenceTokens(
  private val nextValue: () -> Long = AtomicLong()::incrementAndGet,
) {
  fun decorate(mediaItem: MediaItem): MediaItem {
    val extras = Bundle(mediaItem.mediaMetadata.extras ?: Bundle.EMPTY)
    extras.putLong(EXTRA_OCCURRENCE_TOKEN, nextValue())
    return mediaItem
      .buildUpon()
      .setMediaMetadata(mediaItem.mediaMetadata.buildUpon().setExtras(extras).build())
      .build()
  }

  fun read(mediaItem: MediaItem): PlaybackOccurrenceToken? {
    val extras = mediaItem.mediaMetadata.extras ?: return null
    if (!extras.containsKey(EXTRA_OCCURRENCE_TOKEN)) return null
    return PlaybackOccurrenceToken(extras.getLong(EXTRA_OCCURRENCE_TOKEN))
  }

  private companion object {
    const val EXTRA_OCCURRENCE_TOKEN = "app.yinyuehe.extra.OCCURRENCE_TOKEN"
  }
}
