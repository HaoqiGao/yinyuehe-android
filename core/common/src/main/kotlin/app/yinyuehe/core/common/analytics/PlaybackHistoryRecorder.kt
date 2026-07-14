package app.yinyuehe.core.common.analytics

import app.yinyuehe.core.common.model.TrackId

interface PlaybackHistoryRecorder {
  suspend fun recordRecent(trackId: TrackId, positionMs: Long? = null): Boolean
}
