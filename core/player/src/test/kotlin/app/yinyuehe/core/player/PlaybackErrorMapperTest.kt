package app.yinyuehe.core.player

import androidx.media3.common.PlaybackException
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackErrorType
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackErrorMapperTest {
  @Test
  fun representativeMedia3CodesMapToStableDomainTypes() {
    val cases =
      listOf(
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND to PlaybackErrorType.SOURCE_UNAVAILABLE,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED to
          PlaybackErrorType.UNSUPPORTED_FORMAT,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED to PlaybackErrorType.DECODER,
        PlaybackException.ERROR_CODE_UNSPECIFIED to PlaybackErrorType.UNKNOWN,
      )
    val trackId = TrackId("local:one")

    cases.forEach { (code, expectedType) ->
      val mapped = playbackError(code, trackId)
      assertEquals(expectedType, mapped.type)
      assertEquals(code, mapped.media3ErrorCode)
      assertEquals(trackId, mapped.trackId)
    }
  }
}
