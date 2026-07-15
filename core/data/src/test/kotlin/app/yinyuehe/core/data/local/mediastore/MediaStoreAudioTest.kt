package app.yinyuehe.core.data.local.mediastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaStoreAudioTest {
  @Test
  fun stableMediaId_includesTheVolumeName() {
    assertNotEquals(stableMediaId("external_primary", 42), stableMediaId("sd-card", 42))
  }

  @Test
  fun stableMediaId_isDeterministicAndUrlSafe() {
    assertEquals("local:v1:ZXh0ZXJuYWxfcHJpbWFyeQ:42", stableMediaId("external_primary", 42))
  }

  @Test
  fun cleanMediaStoreText_removesBlankAndUnknownMetadata() {
    assertNull(cleanMediaStoreText(null))
    assertNull(cleanMediaStoreText("  "))
    assertNull(cleanMediaStoreText("<unknown>"))
    assertEquals("Title", cleanMediaStoreText(" Title "))
  }

  @Test
  fun toTrackEntity_usesStableIdentityAndScanToken() {
    val entity = audio(volumeName = "sd-card", mediaStoreId = 7).toTrackEntity("scan-2")

    assertEquals(stableMediaId("sd-card", 7), entity.mediaId)
    assertEquals("scan-2", entity.lastSeenScanToken)
    assertEquals("a title an artist an album", entity.searchText)
    assertEquals("a title", entity.titleSortKey)
  }

  private fun audio(volumeName: String, mediaStoreId: Long) =
    MediaStoreAudio(
      volumeName = volumeName,
      mediaStoreId = mediaStoreId,
      contentUri = "content://media/$volumeName/audio/media/$mediaStoreId",
      displayName = "track-$mediaStoreId.mp3",
      title = "A Title",
      artist = "An Artist",
      album = "An Album",
      albumId = 11,
      artworkUri = "content://media/$volumeName/audio/albumart/11",
      durationMs = 1_000,
      mimeType = "audio/mpeg",
      sizeBytes = 2_000,
      dateAddedSeconds = 3_000,
      dateModifiedSeconds = 4_000,
    )
}
