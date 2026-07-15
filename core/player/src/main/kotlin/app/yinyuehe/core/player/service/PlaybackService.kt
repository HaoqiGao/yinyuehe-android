package app.yinyuehe.core.player.service

import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import app.yinyuehe.core.common.analytics.PlaybackEventRecorder
import app.yinyuehe.core.common.analytics.PlaybackHistoryRecorder
import app.yinyuehe.core.common.playback.PlaybackQueueResolver
import app.yinyuehe.core.common.playback.PlaybackSnapshotStore
import app.yinyuehe.core.player.PlaybackSessionProtocol
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {
  @Inject lateinit var playbackEventRecorder: PlaybackEventRecorder
  @Inject lateinit var playbackHistoryRecorder: PlaybackHistoryRecorder
  @Inject lateinit var playbackSnapshotStore: PlaybackSnapshotStore
  @Inject lateinit var playbackQueueResolver: PlaybackQueueResolver
  @Inject
  lateinit var playbackRestoreBarriers: Set<@JvmSuppressWildcards PlaybackRestoreBarrier>

  private val eventTracker = PlaybackServiceEventTracker()
  private val serviceJob = SupervisorJob()
  private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
  private var recordingQueue: PlaybackServiceRecordingQueue? = null
  private var session: MediaLibrarySession? = null
  private var player: ExoPlayer? = null
  private var playerListener: Player.Listener? = null
  private var restoreCoordinator: PlaybackRestoreCoordinator? = null
  private var persistenceCoordinator: PlaybackPersistenceCoordinator? = null
  private var persistenceListener: Player.Listener? = null
  private var failureCoordinator: PlaybackFailureCoordinator? = null
  private var failureListener: Player.Listener? = null

  override fun onCreate() {
    super.onCreate()
    recordingQueue =
      PlaybackServiceRecordingQueue(
        eventRecorder = playbackEventRecorder,
        historyRecorder = playbackHistoryRecorder,
        onRecordingFailure = { error ->
          Log.w(TAG, "Playback metadata recording failed", error)
        },
      )
    val player =
      ExoPlayer.Builder(this)
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build(),
          true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    val applicationController = ControllerIdentity(packageName, applicationInfo.uid)
    val gate = RestorePersistenceGate(applicationController)
    val tokens = PlaybackOccurrenceTokens()
    val writer =
      PlaybackSnapshotWriter(
        snapshotStore = playbackSnapshotStore,
        dispatcher = Dispatchers.IO,
        onFailure = { error ->
          Log.w(TAG, "Playback snapshot write failed: ${error::class.java.simpleName}")
        },
      )
    val persistence =
      PlaybackPersistenceCoordinator(
        gate = gate,
        writer = writer,
        scope = serviceScope,
        capture = player::capturePlaybackSnapshot,
      )
    val persistenceListener =
      PlaybackPersistencePlayerListener(player, tokens, gate, persistence) {
        session?.setSessionExtras(
          PlaybackSessionProtocol.sessionExtras(gate.queuePersistenceLimited)
        )
      }
    player.addListener(persistenceListener)
    this.persistenceListener = persistenceListener
    this.persistenceCoordinator = persistence

    val callback =
      PlaybackLibrarySessionCallback(
        tokens,
        gate,
        onUserRetry = { failureCoordinator?.onUserRetry() },
      )
    session = MediaLibrarySession.Builder(this, player, callback).build()
    session?.setSessionExtras(PlaybackSessionProtocol.sessionExtras(false))

    val failureCoordinator =
      PlaybackFailureCoordinator(
        player = player,
        tokens = tokens,
        policy = PlaybackFailurePolicy(),
        scope = serviceScope,
        onNotice = { notice ->
          val encoded = PlaybackSessionProtocol.encode(notice)
          session
            ?.connectedControllers
            ?.filter { controller ->
              controller.packageName == packageName && controller.uid == applicationInfo.uid
            }
            ?.forEach { controller ->
              session?.sendCustomCommand(controller, encoded.command, encoded.extras)
            }
        },
      )
    val failureListener = PlaybackFailurePlayerListener(failureCoordinator)
    player.addListener(failureListener)
    this.failureCoordinator = failureCoordinator
    this.failureListener = failureListener

    restoreCoordinator =
      PlaybackRestoreCoordinator(
          snapshotStore = playbackSnapshotStore,
          queueResolver = playbackQueueResolver,
          gate = gate,
          player = Media3RestorablePlayer(player, tokens),
          scope = serviceScope,
          ioDispatcher = Dispatchers.IO,
          onNormalizedSnapshot = { snapshot ->
            writer.submit(snapshot, SnapshotWriteUrgency.IMMEDIATE)
          },
          onGateChanged = {
            session?.setSessionExtras(
              PlaybackSessionProtocol.sessionExtras(gate.queuePersistenceLimited)
            )
          },
          onFailure = { error ->
            Log.w(TAG, "Playback restore failed: ${error::class.java.simpleName}")
          },
          beforeRead = {
            playbackRestoreBarriers.forEach { barrier ->
              barrier.awaitPhase(PlaybackRestoreBarrierPhase.BEFORE_READ)
            }
          },
          beforeApply = {
            playbackRestoreBarriers.forEach { barrier ->
              barrier.awaitPhase(PlaybackRestoreBarrierPhase.BEFORE_APPLY)
            }
          },
        )
        .also(PlaybackRestoreCoordinator::start)

    val listener =
      object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
          eventTracker
            .onMediaItemTransition(
              mediaId = mediaItem?.mediaId,
              isPlaying = player.isPlaying,
              reason = reason,
            )
            .forEach(::recordUpdate)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
          recordUpdate(
            eventTracker.onIsPlayingChanged(
              isPlaying = isPlaying,
              mediaId = player.currentMediaItem?.mediaId,
            )
          )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
          if (playbackState == Player.STATE_ENDED) {
            recordUpdate(eventTracker.onPlaybackEnded(player.currentMediaItem?.mediaId))
          }
        }
      }
    player.addListener(listener)
    this.player = player
    playerListener = listener
  }

  private fun recordUpdate(update: PlaybackServiceUpdate?) {
    recordingQueue?.record(update)
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
    session

  override fun onDestroy() {
    restoreCoordinator?.cancel()
    restoreCoordinator = null
    persistenceListener?.let { listener -> player?.removeListener(listener) }
    persistenceListener = null
    persistenceCoordinator?.close()
    persistenceCoordinator = null
    failureListener?.let { listener -> player?.removeListener(listener) }
    failureListener = null
    failureCoordinator?.close()
    failureCoordinator = null
    serviceJob.cancel()
    playerListener?.let { listener -> player?.removeListener(listener) }
    session?.release()
    player?.release()
    playerListener = null
    player = null
    session = null
    recordingQueue?.close()
    recordingQueue = null
    super.onDestroy()
  }

  private companion object {
    const val TAG = "PlaybackService"
  }
}
