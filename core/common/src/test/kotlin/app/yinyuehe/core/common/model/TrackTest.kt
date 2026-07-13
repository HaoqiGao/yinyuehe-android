package app.yinyuehe.core.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TrackTest {
  @Test
  fun validTrack_keepsDomainValues() {
    val track =
      Track(
        id = TrackId("demo:morning-pulse"),
        title = "晨间节拍",
        artist = "音悦盒 Demo Band",
        album = "Compose Sessions",
        durationMs = 3_200,
        artworkUri = null,
        sourceUri = "android.resource://app.yinyuehe/1",
        isDemo = true,
      )

    assertEquals("demo:morning-pulse", track.id.value)
    assertEquals(3_200L, track.durationMs)
  }

  @Test
  fun blankId_isRejected() {
    assertThrows(IllegalArgumentException::class.java) { TrackId(" ") }
  }

  @Test
  fun negativeDuration_isRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      validTrack().copy(durationMs = -1)
    }
  }

  @Test
  fun missingTitle_isAllowedWhenDisplayNameCanDescribeTheRow() {
    val track = validTrack().copy(title = null, displayName = "01 - Intro.flac")

    assertEquals(null, track.title)
    assertEquals("01 - Intro.flac", track.displayName)
  }

  @Test
  fun negativeSize_isRejected() {
    assertThrows(IllegalArgumentException::class.java) { validTrack().copy(sizeBytes = -1) }
  }

  @Test
  fun blankNonNullTitle_isRejected() {
    assertThrows(IllegalArgumentException::class.java) { validTrack().copy(title = " ") }
  }

  private fun validTrack() =
    Track(
      id = TrackId("demo:test"),
      title = "Test",
      artist = null,
      album = null,
      durationMs = null,
      artworkUri = null,
      sourceUri = "android.resource://app.yinyuehe/1",
      isDemo = true,
    )
}
