package app.yinyuehe.core.data.playback

import androidx.room.withTransaction
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackQueueBlockReason
import app.yinyuehe.core.common.playback.PlaybackQueueItemResolution
import app.yinyuehe.core.common.playback.PlaybackQueueResolution
import app.yinyuehe.core.common.playback.PlaybackQueueResolver
import app.yinyuehe.core.data.DemoTrackCatalog
import app.yinyuehe.core.data.local.db.DEMO_VOLUME_NAME
import app.yinyuehe.core.data.local.db.YinYueHeDatabase
import app.yinyuehe.core.data.local.db.toDomain
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class RoomPlaybackQueueResolver @Inject constructor(
  private val database: YinYueHeDatabase,
  demoTrackCatalog: DemoTrackCatalog,
  private val permissionChecker: AudioReadPermissionChecker,
) : PlaybackQueueResolver {
  private val demosById: Map<TrackId, Track> = demoTrackCatalog.tracks().associateBy(Track::id)

  override suspend fun resolve(mediaIds: List<TrackId>): PlaybackQueueResolution {
    val localCandidates =
      mediaIds.filter { id -> id !in demosById && !id.value.startsWith(DEMO_ID_PREFIX) }
    val hasLocalPermission =
      localCandidates.isEmpty() || permissionChecker.hasAudioReadPermission()
    val localTracksById =
      if (localCandidates.isNotEmpty() && hasLocalPermission) {
        database
          .withTransaction {
            uniqueLocalIdBatches(localCandidates).flatMap { batch ->
              database
                .trackDao()
                .findAvailableByMediaIds(
                  mediaIds = batch,
                  excludedVolumeName = DEMO_VOLUME_NAME,
                )
            }
          }
          .map { entity -> entity.toDomain() }
          .associateBy(Track::id)
      } else {
        emptyMap()
      }

    val items =
      mediaIds.mapIndexed { originalIndex, id ->
        val demo = demosById[id]
        when {
          demo != null ->
            PlaybackQueueItemResolution.Resolved(
              originalIndex = originalIndex,
              trackId = id,
              track = demo,
            )
          id.value.startsWith(DEMO_ID_PREFIX) ->
            PlaybackQueueItemResolution.PermanentlyMissing(originalIndex, id)
          !hasLocalPermission ->
            PlaybackQueueItemResolution.TemporarilyBlocked(
              originalIndex = originalIndex,
              trackId = id,
              reason = PlaybackQueueBlockReason.PERMISSION_DENIED,
            )
          else -> {
            val localTrack = localTracksById[id]
            if (localTrack == null) {
              PlaybackQueueItemResolution.PermanentlyMissing(originalIndex, id)
            } else {
              PlaybackQueueItemResolution.Resolved(originalIndex, id, localTrack)
            }
          }
        }
      }

    return PlaybackQueueResolution(
      items = items,
      temporaryBlockReason =
        if (localCandidates.isNotEmpty() && !hasLocalPermission) {
          PlaybackQueueBlockReason.PERMISSION_DENIED
        } else {
          null
        },
    )
  }
}

internal fun uniqueLocalIdBatches(mediaIds: List<TrackId>): List<List<String>> =
  mediaIds
    .asSequence()
    .map { id -> id.value }
    .distinct()
    .chunked(PLAYBACK_QUEUE_QUERY_BATCH_SIZE)
    .toList()

// Room also binds excludedVolumeName, so each query has at most 900 total bind arguments.
internal const val PLAYBACK_QUEUE_QUERY_BATCH_SIZE: Int = 899
private const val DEMO_ID_PREFIX: String = "demo:"
