package app.yinyuehe.core.common.playback

import app.yinyuehe.core.common.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackFailureTest {
  @Test
  fun skippedNoticeCarriesOnlyTypedDomainErrorData() {
    val error =
      PlaybackError(
        type = PlaybackErrorType.DECODER,
        media3ErrorCode = 4003,
        trackId = TrackId("local:v1:ZXh0ZXJuYWw:7"),
      )

    assertEquals(error, PlaybackNotice.TrackSkipped(error).error)
    assertEquals(PlaybackConnectionError.RETRIES_EXHAUSTED.name, "RETRIES_EXHAUSTED")
  }
}
