package app.yinyuehe.core.player

import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackError
import app.yinyuehe.core.common.playback.PlaybackErrorType
import app.yinyuehe.core.common.playback.PlaybackNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackSessionProtocolTest {
  @Test
  fun trackSkippedRoundTripContainsOnlyTypeCodeAndTrackId() {
    val notice =
      PlaybackNotice.TrackSkipped(
        PlaybackError(PlaybackErrorType.DECODER, 4003, TrackId("local:one"))
      )
    val encoded = PlaybackSessionProtocol.encode(notice)

    assertEquals(notice, PlaybackSessionProtocol.decode(encoded.command, encoded.extras))
    assertEquals(3, encoded.extras.keySet().size)
  }

  @Test
  fun unknownActionOrEnumIsRejectedWithoutThrowing() {
    assertNull(
      PlaybackSessionProtocol.decode(
        androidx.media3.session.SessionCommand("unknown", android.os.Bundle.EMPTY),
        android.os.Bundle.EMPTY,
      )
    )
  }

  @Test
  fun queuePersistenceExtraPreservesUnrelatedSessionExtras() {
    val base = android.os.Bundle().apply { putString("existing", "kept") }

    val extras = PlaybackSessionProtocol.sessionExtras(true, base)

    assertEquals("kept", extras.getString("existing"))
    assertEquals(true, PlaybackSessionProtocol.queuePersistenceLimited(extras))
  }
}
