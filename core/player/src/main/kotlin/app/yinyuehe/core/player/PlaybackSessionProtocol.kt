package app.yinyuehe.core.player

import android.os.Bundle

internal object PlaybackSessionProtocol {
  private const val EXTRA_QUEUE_PERSISTENCE_LIMITED =
    "app.yinyuehe.extra.QUEUE_PERSISTENCE_LIMITED"

  fun sessionExtras(
    queuePersistenceLimited: Boolean,
    base: Bundle = Bundle.EMPTY,
  ): Bundle =
    Bundle(base).apply { putBoolean(EXTRA_QUEUE_PERSISTENCE_LIMITED, queuePersistenceLimited) }

  fun queuePersistenceLimited(extras: Bundle): Boolean =
    extras.getBoolean(EXTRA_QUEUE_PERSISTENCE_LIMITED, false)
}
