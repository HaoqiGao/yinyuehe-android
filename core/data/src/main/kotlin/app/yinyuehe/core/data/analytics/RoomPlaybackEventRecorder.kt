package app.yinyuehe.core.data.analytics

import app.yinyuehe.core.common.analytics.PlaybackEvent
import app.yinyuehe.core.common.analytics.PlaybackEventName
import app.yinyuehe.core.common.analytics.PlaybackEventRecorder
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.data.local.db.dao.PlaybackEventDao
import app.yinyuehe.core.data.local.db.entity.PlaybackEventEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Singleton
class RoomPlaybackEventRecorder @Inject internal constructor(
  private val dao: PlaybackEventDao,
) : PlaybackEventRecorder {
  override suspend fun record(event: PlaybackEvent) {
    dao.insertAndTrim(event.toEntity(), MAX_EVENT_ROWS)
  }

  fun observeEvents(): Flow<List<PlaybackEvent>> =
    dao.observeEvents().map { entities -> entities.map { it.toDomain() } }.distinctUntilChanged()
}

private fun PlaybackEvent.toEntity(): PlaybackEventEntity =
  PlaybackEventEntity(
    name = name.name,
    trackId = trackId?.value,
    occurredAtEpochMs = occurredAtEpochMs,
    durationMs = durationMs,
  )

private fun PlaybackEventEntity.toDomain(): PlaybackEvent =
  PlaybackEvent(
    name = PlaybackEventName.valueOf(name),
    trackId = trackId?.let(::TrackId),
    occurredAtEpochMs = occurredAtEpochMs,
    durationMs = durationMs,
  )

private const val MAX_EVENT_ROWS = 500
