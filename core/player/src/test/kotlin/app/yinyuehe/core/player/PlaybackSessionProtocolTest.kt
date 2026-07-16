package app.yinyuehe.core.player

import android.os.Bundle
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackError
import app.yinyuehe.core.common.playback.PlaybackErrorType
import app.yinyuehe.core.common.playback.PlaybackNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
  fun everyNoticeFieldIsRequiredButANullTrackIdRemainsValid() {
    val notice =
      PlaybackNotice.TrackSkipped(
        PlaybackError(PlaybackErrorType.SOURCE_UNAVAILABLE, 2005, trackId = null)
      )
    val encoded = PlaybackSessionProtocol.encode(notice)

    assertEquals(notice, PlaybackSessionProtocol.decode(encoded.command, encoded.extras))
    listOf(ERROR_TYPE, ERROR_CODE, TRACK_ID).forEach { missingKey ->
      val missing = Bundle(encoded.extras).apply { remove(missingKey) }
      assertNull(
        "Missing $missingKey must be rejected",
        PlaybackSessionProtocol.decode(encoded.command, missing),
      )
    }
  }

  @Test
  fun wrongRawNoticeFieldTypesAreRejectedWithoutThrowing() {
    val notice =
      PlaybackNotice.TrackSkipped(
        PlaybackError(PlaybackErrorType.SOURCE_UNAVAILABLE, 2005, TrackId("local:one"))
      )
    val encoded = PlaybackSessionProtocol.encode(notice)
    val malformed =
      listOf(
        Bundle(encoded.extras).apply { putInt(ERROR_TYPE, 1) },
        Bundle(encoded.extras).apply { putLong(ERROR_CODE, 2005L) },
        Bundle(encoded.extras).apply { putInt(TRACK_ID, 1) },
      )

    malformed.forEach { extras ->
      assertNull(PlaybackSessionProtocol.decode(encoded.command, extras))
    }
  }

  @Test
  fun queuePersistenceExtraPreservesUnrelatedSessionExtras() {
    val base = android.os.Bundle().apply { putString("existing", "kept") }

    val extras = PlaybackSessionProtocol.sessionExtras(true, base)

    assertEquals("kept", extras.getString("existing"))
    assertEquals(true, PlaybackSessionProtocol.queuePersistenceLimited(extras))
  }

  @Test
  fun sessionStateComposesQueueLimitAndDecisionOwnedTerminalError() {
    val base = Bundle().apply { putString("existing", "kept") }
    val error =
      PlaybackError(PlaybackErrorType.SOURCE_UNAVAILABLE, 2005, TrackId("local:broken"))

    val terminal =
      PlaybackSessionProtocol.sessionExtras(
        queuePersistenceLimited = true,
        base = base,
        terminalPlaybackError = error,
      )

    assertEquals("kept", terminal.getString("existing"))
    assertEquals(true, PlaybackSessionProtocol.queuePersistenceLimited(terminal))
    assertEquals(error, PlaybackSessionProtocol.terminalPlaybackError(terminal))

    val cleared =
      PlaybackSessionProtocol.sessionExtras(
        queuePersistenceLimited = false,
        base = terminal,
        terminalPlaybackError = null,
      )
    assertEquals("kept", cleared.getString("existing"))
    assertFalse(PlaybackSessionProtocol.queuePersistenceLimited(cleared))
    assertNull(PlaybackSessionProtocol.terminalPlaybackError(cleared))
  }

  @Test
  fun malformedSessionStateFieldsAreIgnoredWithoutThrowing() {
    val error = PlaybackError(PlaybackErrorType.DECODER, 4003, TrackId("local:broken"))
    val valid =
      PlaybackSessionProtocol.sessionExtras(
        queuePersistenceLimited = true,
        terminalPlaybackError = error,
      )
    val wrongQueueType = Bundle(valid).apply { putString(QUEUE_LIMITED, "true") }
    val wrongTerminalCode = Bundle(valid).apply { putLong(TERMINAL_ERROR_CODE, 4003L) }
    val missingTerminalTrack = Bundle(valid).apply { remove(TERMINAL_TRACK_ID) }

    assertFalse(PlaybackSessionProtocol.queuePersistenceLimited(wrongQueueType))
    assertNull(PlaybackSessionProtocol.terminalPlaybackError(wrongTerminalCode))
    assertNull(PlaybackSessionProtocol.terminalPlaybackError(missingTerminalTrack))
  }

  private companion object {
    const val ERROR_TYPE = "app.yinyuehe.extra.ERROR_TYPE"
    const val ERROR_CODE = "app.yinyuehe.extra.ERROR_CODE"
    const val TRACK_ID = "app.yinyuehe.extra.TRACK_ID"
    const val QUEUE_LIMITED = "app.yinyuehe.extra.QUEUE_PERSISTENCE_LIMITED"
    const val TERMINAL_ERROR_CODE = "app.yinyuehe.extra.TERMINAL_ERROR_CODE"
    const val TERMINAL_TRACK_ID = "app.yinyuehe.extra.TERMINAL_TRACK_ID"
  }
}
