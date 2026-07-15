package app.yinyuehe.core.player.service

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.yinyuehe.core.player.PlaybackSessionProtocol
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackOccurrenceTokensTest {
  @Test
  fun duplicateMediaIdsReceiveDistinctTokensWithoutChangingStableMediaItemData() {
    val next = AtomicLong(40)
    val tokens = PlaybackOccurrenceTokens(next::incrementAndGet)
    val source =
      MediaItem.Builder()
        .setMediaId("demo:duplicate")
        .setUri("android.resource://app/1")
        .setMediaMetadata(
          MediaMetadata.Builder()
            .setTitle("Duplicate")
            .setExtras(Bundle().apply { putString("existing", "kept") })
            .build()
        )
        .build()

    val first = tokens.decorate(source)
    val second = tokens.decorate(source)

    assertEquals("demo:duplicate", first.mediaId)
    assertEquals(source.localConfiguration?.uri, first.localConfiguration?.uri)
    assertEquals("Duplicate", first.mediaMetadata.title)
    assertEquals("kept", first.mediaMetadata.extras?.getString("existing"))
    assertEquals(PlaybackOccurrenceToken(41), tokens.read(first))
    assertEquals(PlaybackOccurrenceToken(42), tokens.read(second))
    assertNotEquals(tokens.read(first), tokens.read(second))
    assertNull(tokens.read(source))
  }

  @Test
  fun wrongTypedOccurrenceTokenExtrasAreRejected() {
    val tokens = PlaybackOccurrenceTokens()
    val stringToken =
      mediaItemWithExtras(Bundle().apply { putString(OCCURRENCE_TOKEN_KEY, "not-a-token") })
    val intToken = mediaItemWithExtras(Bundle().apply { putInt(OCCURRENCE_TOKEN_KEY, 7) })

    assertNull(tokens.read(stringToken))
    assertNull(tokens.read(intToken))
  }

  @Test
  fun decorateOverwritesAConflictingTokenWithoutMutatingTheSourceExtras() {
    val next = AtomicLong(70)
    val tokens = PlaybackOccurrenceTokens(next::incrementAndGet)
    val source =
      mediaItemWithExtras(
        Bundle().apply {
          putString(OCCURRENCE_TOKEN_KEY, "conflicting")
          putString("existing", "kept")
        }
      )

    val decorated = tokens.decorate(source)

    assertEquals(PlaybackOccurrenceToken(71), tokens.read(decorated))
    assertEquals("conflicting", source.mediaMetadata.extras?.getString(OCCURRENCE_TOKEN_KEY))
    assertEquals("kept", source.mediaMetadata.extras?.getString("existing"))
    assertNull(tokens.read(source))
  }

  @Test
  fun sessionExtrasCopiesBaseBeforeAddingThePrivatePersistenceFlag() {
    val base = Bundle().apply { putString("existing", "kept") }

    val extras =
      PlaybackSessionProtocol.sessionExtras(queuePersistenceLimited = true, base = base)

    assertEquals(setOf("existing"), base.keySet())
    assertEquals("kept", extras.getString("existing"))
    assertFalse(PlaybackSessionProtocol.queuePersistenceLimited(base))
    assertTrue(PlaybackSessionProtocol.queuePersistenceLimited(extras))
  }
}

private fun mediaItemWithExtras(extras: Bundle): MediaItem =
  MediaItem.Builder()
    .setMediaMetadata(MediaMetadata.Builder().setExtras(extras).build())
    .build()

private const val OCCURRENCE_TOKEN_KEY = "app.yinyuehe.extra.OCCURRENCE_TOKEN"
