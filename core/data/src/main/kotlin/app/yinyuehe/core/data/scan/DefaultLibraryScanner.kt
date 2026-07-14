package app.yinyuehe.core.data.scan

import androidx.room.withTransaction
import app.yinyuehe.core.data.local.db.DEMO_VOLUME_NAME
import app.yinyuehe.core.data.local.db.YinYueHeDatabase
import app.yinyuehe.core.data.local.db.dao.ScanCheckpointDao
import app.yinyuehe.core.data.local.db.dao.TrackDao
import app.yinyuehe.core.data.local.db.entity.ScanCheckpointEntity
import app.yinyuehe.core.data.local.db.entity.TrackEntity
import app.yinyuehe.core.data.local.mediastore.MediaStoreGateway
import app.yinyuehe.core.data.local.mediastore.toTrackEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class DefaultLibraryScanner @Inject internal constructor(
  private val gateway: MediaStoreGateway,
  private val database: YinYueHeDatabase,
  private val trackDao: TrackDao,
  private val checkpointDao: ScanCheckpointDao,
) : LibraryScanner {
  override suspend fun scan(): Result<ScanResult> =
    try {
      val volumes = gateway.externalVolumeNames()
      check(volumes.size == volumes.distinct().size) {
        "MediaStore volume enumeration contained duplicates"
      }
      val snapshots =
        volumes.map { volumeName ->
          check(volumeName.isNotBlank() && volumeName != DEMO_VOLUME_NAME) {
            "MediaStore volume enumeration contained an invalid volume"
          }
          val completedRows = gateway.readVolume(volumeName)
          check(completedRows.all { it.volumeName == volumeName }) {
            "MediaStore snapshot contained a row from a different volume"
          }
          val scanToken = UUID.randomUUID().toString()
          val entities = completedRows.map { it.toTrackEntity(scanToken) }
          CompletedVolumeSnapshot(volumeName, scanToken, entities)
        }
      val committedAt = System.currentTimeMillis()
      var unavailable = 0
      database.withTransaction {
        val currentVolumes = volumes.toSet()
        val missingMountedCheckpoints =
          checkpointDao
            .getAll()
            .filter { checkpoint ->
              checkpoint.isMounted &&
                checkpoint.volumeName != DEMO_VOLUME_NAME &&
                checkpoint.volumeName !in currentVolumes
            }
        snapshots.forEach { snapshot ->
          with(snapshot) {
            trackDao.upsertTracks(entities)
            val markedUnavailable =
              trackDao.markUnavailableNotSeenInScan(volumeName, scanToken)
            checkpointDao.upsert(
              ScanCheckpointEntity(
                volumeName = volumeName,
                mediaStoreVersion = null,
                generationUpperBound = null,
                lastFullScanEpochMs = committedAt,
                lastSuccessfulScanEpochMs = committedAt,
                lastScanToken = scanToken,
                isMounted = true,
                lastDiscoveredCount = entities.size.toLong(),
                lastInsertedCount = 0,
                lastUpdatedCount = entities.size.toLong(),
                lastUnavailableCount = markedUnavailable.toLong(),
              )
            )
            unavailable += markedUnavailable
          }
        }
        missingMountedCheckpoints.forEach { checkpoint ->
          val markedUnavailable =
            trackDao.markVolumeUnavailable(
              volumeName = checkpoint.volumeName,
              excludedVolumeName = DEMO_VOLUME_NAME,
            )
          checkpointDao.upsert(
            checkpoint.copy(
              isMounted = false,
              lastUnavailableCount = markedUnavailable.toLong(),
            )
          )
          unavailable += markedUnavailable
        }
      }
      Result.success(
        ScanResult(
          discovered = snapshots.sumOf { it.entities.size },
          unavailable = unavailable,
          volumeCount = volumes.size,
        )
      )
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (failure: Throwable) {
      Result.failure(failure)
    }
}

private data class CompletedVolumeSnapshot(
  val volumeName: String,
  val scanToken: String,
  val entities: List<TrackEntity>,
)
