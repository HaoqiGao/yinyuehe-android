package app.yinyuehe.core.data.analytics

import app.yinyuehe.core.common.analytics.PlaybackHistoryRecorder
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.data.TrackRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomPlaybackHistoryRecorder @Inject internal constructor(
  private val repository: TrackRepository,
) : PlaybackHistoryRecorder {
  override suspend fun recordRecent(trackId: TrackId, positionMs: Long?): Boolean =
    repository.recordRecent(trackId, positionMs)
}
