package app.yinyuehe.core.data.local.db

import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackEntityMapperTest {
  @Test
  fun completeMetadata_mapsEveryDomainField() {
    val entity =
      trackEntity(
        mediaId = "local:v1:external_primary:42",
        contentUri = "content://media/external/audio/media/42",
        displayName = "Complete.flac",
        title = "Complete",
        artist = "Local Artist",
        album = "Local Album",
        albumId = 84L,
        artworkUri = "content://media/external/audio/albumart/84",
        durationMs = 123_456L,
        mimeType = "audio/flac",
        sizeBytes = 9_876_543L,
        folderKey = "music/live",
        folderDisplayName = "Live",
        dateAddedSeconds = 1_700_000_000L,
        dateModifiedSeconds = 1_700_000_100L,
      )

    assertEquals(
      Track(
        id = TrackId("local:v1:external_primary:42"),
        title = "Complete",
        artist = "Local Artist",
        album = "Local Album",
        durationMs = 123_456L,
        artworkUri = "content://media/external/audio/albumart/84",
        sourceUri = "content://media/external/audio/media/42",
        isDemo = false,
        displayName = "Complete.flac",
        albumId = 84L,
        mimeType = "audio/flac",
        sizeBytes = 9_876_543L,
        folderKey = "music/live",
        folderDisplayName = "Live",
        dateAddedSeconds = 1_700_000_000L,
        dateModifiedSeconds = 1_700_000_100L,
      ),
      entity.toDomain(),
    )
  }

  @Test
  fun nullableMetadata_staysNullWithoutInventedText() {
    val mapped =
      trackEntity(
          displayName = null,
          title = null,
          artist = null,
          album = null,
          albumId = null,
          artworkUri = null,
          mimeType = null,
          folderKey = null,
          folderDisplayName = null,
        )
        .toDomain()

    assertNull(mapped.displayName)
    assertNull(mapped.title)
    assertNull(mapped.artist)
    assertNull(mapped.album)
    assertNull(mapped.albumId)
    assertNull(mapped.artworkUri)
    assertNull(mapped.mimeType)
    assertNull(mapped.folderKey)
    assertNull(mapped.folderDisplayName)
  }

  @Test
  fun blankMetadata_normalizesToNull() {
    val mapped =
      trackEntity(
          displayName = " ",
          title = "\t",
          artist = "\n",
          album = "  ",
          artworkUri = "\t",
          mimeType = " ",
          folderKey = "\n",
          folderDisplayName = "  ",
        )
        .toDomain()

    assertNull(mapped.displayName)
    assertNull(mapped.title)
    assertNull(mapped.artist)
    assertNull(mapped.album)
    assertNull(mapped.artworkUri)
    assertNull(mapped.mimeType)
    assertNull(mapped.folderKey)
    assertNull(mapped.folderDisplayName)
  }

  @Test
  fun nonPositiveAndNegativeNumbers_followDomainNormalization() {
    val negative =
      trackEntity(
          albumId = -1L,
          durationMs = -1L,
          sizeBytes = -1L,
          dateAddedSeconds = -1L,
          dateModifiedSeconds = -1L,
        )
        .toDomain()
    val zero =
      trackEntity(
          albumId = 0L,
          durationMs = 0L,
          sizeBytes = 0L,
          dateAddedSeconds = 0L,
          dateModifiedSeconds = 0L,
        )
        .toDomain()

    assertNull(negative.albumId)
    assertNull(negative.durationMs)
    assertEquals(0L, negative.sizeBytes)
    assertEquals(0L, negative.dateAddedSeconds)
    assertEquals(0L, negative.dateModifiedSeconds)
    assertEquals(0L, zero.albumId)
    assertNull(zero.durationMs)
    assertEquals(0L, zero.sizeBytes)
    assertEquals(0L, zero.dateAddedSeconds)
    assertEquals(0L, zero.dateModifiedSeconds)
  }

  @Test
  fun contentUri_isPassedThroughAndLocalRoomTracksAreNotDemo() {
    val mapped =
      trackEntity(contentUri = "content://media/external/audio/media/314").toDomain()

    assertEquals("content://media/external/audio/media/314", mapped.sourceUri)
    assertFalse(mapped.isDemo)
  }

  @Test
  fun reservedDemoVolume_mapsToDemoWithoutInventingLocalMetadata() {
    val mapped =
      trackEntity(
          mediaId = "demo:one",
          volumeName = DEMO_VOLUME_NAME,
          mediaStoreId = -1,
          contentUri = "android.resource://app/1",
          displayName = null,
          albumId = null,
          mimeType = null,
          sizeBytes = 0,
          folderKey = null,
          folderDisplayName = null,
          dateAddedSeconds = 0,
          dateModifiedSeconds = 0,
        )
        .toDomain()

    assertTrue(mapped.isDemo)
    assertNull(mapped.sizeBytes)
    assertNull(mapped.dateAddedSeconds)
    assertNull(mapped.dateModifiedSeconds)
  }
}
