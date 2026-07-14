package app.yinyuehe.core.data.scan

import androidx.room.withTransaction
import app.yinyuehe.core.data.local.db.YinYueHeDatabase
import app.yinyuehe.core.data.local.db.dao.ScanCheckpointDao
import app.yinyuehe.core.data.local.db.dao.TrackDao
import app.yinyuehe.core.data.local.db.entity.ScanCheckpointEntity
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
      var discovered = 0
      var unavailable = 0
      volumes.forEach { volumeName ->
        val completedRows = gateway.readVolume(volumeName)
        check(completedRows.all { it.volumeName == volumeName }) {
          "MediaStore snapshot contained a row from a different volume"
        }
        val scanToken = UUID.randomUUID().toString()
        val entities = completedRows.map { it.toTrackEntity(scanToken) }
        val committedAt = System.currentTimeMillis()
        val volumeUnavailable =
          database.withTransaction {
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
            markedUnavailable
          }
        discovered += entities.size
        unavailable += volumeUnavailable
      }
      Result.success(ScanResult(discovered, unavailable, volumes.size))
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (failure: Throwable) {
      Result.failure(failure)
    }
}
