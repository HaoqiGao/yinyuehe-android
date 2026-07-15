package app.yinyuehe.core.player

import android.os.Bundle
import androidx.media3.session.SessionCommand
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackError
import app.yinyuehe.core.common.playback.PlaybackErrorType
import app.yinyuehe.core.common.playback.PlaybackNotice

internal object PlaybackSessionProtocol {
  private const val ACTION_TRACK_SKIPPED = "app.yinyuehe.action.TRACK_SKIPPED"
  private const val EXTRA_ERROR_TYPE = "app.yinyuehe.extra.ERROR_TYPE"
  private const val EXTRA_ERROR_CODE = "app.yinyuehe.extra.ERROR_CODE"
  private const val EXTRA_TRACK_ID = "app.yinyuehe.extra.TRACK_ID"
  private const val EXTRA_QUEUE_PERSISTENCE_LIMITED =
    "app.yinyuehe.extra.QUEUE_PERSISTENCE_LIMITED"
  private const val EXTRA_TERMINAL_ERROR_TYPE = "app.yinyuehe.extra.TERMINAL_ERROR_TYPE"
  private const val EXTRA_TERMINAL_ERROR_CODE = "app.yinyuehe.extra.TERMINAL_ERROR_CODE"
  private const val EXTRA_TERMINAL_TRACK_ID = "app.yinyuehe.extra.TERMINAL_TRACK_ID"

  data class EncodedNotice(val command: SessionCommand, val extras: Bundle)

  fun encode(notice: PlaybackNotice.TrackSkipped): EncodedNotice {
    val extras =
      Bundle().apply {
        putString(EXTRA_ERROR_TYPE, notice.error.type.name)
        putInt(EXTRA_ERROR_CODE, notice.error.media3ErrorCode)
        putString(EXTRA_TRACK_ID, notice.error.trackId?.value)
      }
    return EncodedNotice(SessionCommand(ACTION_TRACK_SKIPPED, Bundle.EMPTY), extras)
  }

  fun decode(command: SessionCommand, extras: Bundle): PlaybackNotice.TrackSkipped? {
    return try {
      if (command.customAction != ACTION_TRACK_SKIPPED) return null
      PlaybackNotice.TrackSkipped(
        extras.strictPlaybackError(EXTRA_ERROR_TYPE, EXTRA_ERROR_CODE, EXTRA_TRACK_ID) ?: return null
      )
    } catch (_: RuntimeException) {
      null
    }
  }

  fun sessionExtras(
    queuePersistenceLimited: Boolean,
    base: Bundle = Bundle.EMPTY,
    terminalPlaybackError: PlaybackError? = null,
  ): Bundle =
    Bundle(base).apply {
      putBoolean(EXTRA_QUEUE_PERSISTENCE_LIMITED, queuePersistenceLimited)
      if (terminalPlaybackError == null) {
        remove(EXTRA_TERMINAL_ERROR_TYPE)
        remove(EXTRA_TERMINAL_ERROR_CODE)
        remove(EXTRA_TERMINAL_TRACK_ID)
      } else {
        putString(EXTRA_TERMINAL_ERROR_TYPE, terminalPlaybackError.type.name)
        putInt(EXTRA_TERMINAL_ERROR_CODE, terminalPlaybackError.media3ErrorCode)
        putString(EXTRA_TERMINAL_TRACK_ID, terminalPlaybackError.trackId?.value)
      }
    }

  @Suppress("DEPRECATION")
  fun queuePersistenceLimited(extras: Bundle): Boolean =
    try {
      extras.get(EXTRA_QUEUE_PERSISTENCE_LIMITED) as? Boolean ?: false
    } catch (_: RuntimeException) {
      false
    }

  fun terminalPlaybackError(extras: Bundle): PlaybackError? =
    try {
      extras.strictPlaybackError(
        EXTRA_TERMINAL_ERROR_TYPE,
        EXTRA_TERMINAL_ERROR_CODE,
        EXTRA_TERMINAL_TRACK_ID,
      )
    } catch (_: RuntimeException) {
      null
    }

  @Suppress("DEPRECATION")
  private fun Bundle.strictPlaybackError(
    typeKey: String,
    codeKey: String,
    trackIdKey: String,
  ): PlaybackError? {
    if (!containsKey(typeKey) || !containsKey(codeKey) || !containsKey(trackIdKey)) return null
    val rawType = get(typeKey)
    if (rawType !is String) return null
    val rawCode = get(codeKey)
    if (rawCode !is Int) return null
    val rawTrackId = get(trackIdKey)
    if (rawTrackId != null && rawTrackId !is String) return null
    val type =
      try {
        PlaybackErrorType.valueOf(rawType)
      } catch (_: IllegalArgumentException) {
        return null
      }
    val trackId = (rawTrackId as String?)?.takeIf(String::isNotBlank)?.let(::TrackId)
    return PlaybackError(type, rawCode, trackId)
  }
}
