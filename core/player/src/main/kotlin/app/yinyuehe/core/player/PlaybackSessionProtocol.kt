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
    if (command.customAction != ACTION_TRACK_SKIPPED) return null
    if (!extras.containsKey(EXTRA_ERROR_CODE)) return null
    val type =
      runCatching { PlaybackErrorType.valueOf(extras.getString(EXTRA_ERROR_TYPE).orEmpty()) }
        .getOrNull() ?: return null
    val trackId = extras.getString(EXTRA_TRACK_ID)?.takeIf(String::isNotBlank)?.let(::TrackId)
    return PlaybackNotice.TrackSkipped(
      PlaybackError(type, extras.getInt(EXTRA_ERROR_CODE), trackId)
    )
  }

  fun sessionExtras(
    queuePersistenceLimited: Boolean,
    base: Bundle = Bundle.EMPTY,
  ): Bundle =
    Bundle(base).apply { putBoolean(EXTRA_QUEUE_PERSISTENCE_LIMITED, queuePersistenceLimited) }

  fun queuePersistenceLimited(extras: Bundle): Boolean =
    extras.getBoolean(EXTRA_QUEUE_PERSISTENCE_LIMITED, false)
}
