package app.yinyuehe.feature.library

import app.yinyuehe.core.common.analytics.PlaybackEvent
import app.yinyuehe.core.common.analytics.PlaybackEventName
import app.yinyuehe.core.common.analytics.PlaybackEventRecorder
import app.yinyuehe.core.common.model.LibrarySource
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.data.TrackRepository
import app.yinyuehe.core.data.scan.LibraryScanner
import app.yinyuehe.core.data.scan.ScanResult
import app.yinyuehe.core.player.PlaybackController
import app.yinyuehe.core.player.PlaybackConnection
import app.yinyuehe.core.player.PlaybackState
import app.yinyuehe.core.testing.FakeTrackRepository
import app.yinyuehe.core.testing.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun repositoryAndControllerFlows_areCombinedIntoOneImmutableState() = runTest {
    val one = track("one")
    val two = track("two")
    val repository = FakeTrackRepository(listOf(one, two))
    repository.setFavorite(one.id, true)
    repository.recordRecent(two.id)
    val controller = RecordingPlaybackController()
    val playback =
      PlaybackState(
        connection = PlaybackConnection.CONNECTED,
        currentTrackId = two.id,
        currentIndex = 1,
        queueTrackIds = listOf(one.id, two.id),
      )
    controller.emit(playback)

    val viewModel = viewModel(repository, controller)
    advanceUntilIdle()

    assertEquals(LibrarySource.DEMO, viewModel.uiState.value.librarySource)
    assertEquals(listOf(one, two), viewModel.uiState.value.libraryTracks)
    assertEquals(setOf(one.id), viewModel.uiState.value.favoriteTrackIds)
    assertEquals(listOf(one), viewModel.uiState.value.favoriteTracks)
    assertEquals(listOf(two), viewModel.uiState.value.recentTracks)
    assertSame(playback, viewModel.uiState.value.playback)
  }

  @Test
  fun controllerCallback_replacesPlaybackSnapshotWithoutUiOwnedPlaybackState() = runTest {
    val controller = RecordingPlaybackController()
    val viewModel = viewModel(FakeTrackRepository(listOf(track("one"))), controller)
    val callbackState =
      PlaybackState(connection = PlaybackConnection.CONNECTED, isPlaying = true, positionMs = 4_200)

    controller.emit(callbackState)
    advanceUntilIdle()

    assertSame(callbackState, viewModel.uiState.value.playback)
  }

  @Test
  fun playAll_startsAtZeroWithoutShuffle() = runTest {
    val tracks = listOf(track("one"), track("two"))
    val controller = RecordingPlaybackController()
    val viewModel = viewModel(FakeTrackRepository(tracks), controller)

    viewModel.onAction(MusicBoxAction.PlayAll(TrackCollection.LIBRARY))
    advanceUntilIdle()

    assertEquals(RecordingPlaybackController.PlayRequest(tracks, 0, false), controller.playRequests.single())
  }

  @Test
  fun playRandom_startsAtZeroWithShuffleEnabled() = runTest {
    val tracks = listOf(track("one"), track("two"))
    val controller = RecordingPlaybackController()
    val viewModel = viewModel(FakeTrackRepository(tracks), controller)

    viewModel.onAction(MusicBoxAction.PlayRandom(TrackCollection.LIBRARY))
    advanceUntilIdle()

    assertEquals(RecordingPlaybackController.PlayRequest(tracks, 0, true), controller.playRequests.single())
  }

  @Test
  fun acceptedTrackSelection_opensPlayerAtSelectedIndex() = runTest {
    val tracks = listOf(track("one"), track("two"))
    val controller = RecordingPlaybackController()
    val viewModel = viewModel(FakeTrackRepository(tracks), controller)

    viewModel.onAction(MusicBoxAction.PlayTrack(TrackId("two"), TrackCollection.LIBRARY))
    advanceUntilIdle()

    assertEquals(1, controller.playRequests.single().startIndex)
    assertEquals(MusicBoxDestination.PLAYER, viewModel.uiState.value.activeDestination)
  }

  @Test
  fun rejectedTrackSelection_keepsDestinationAndExposesStableErrorCode() = runTest {
    val controller = RecordingPlaybackController().apply { playResult = false }
    val viewModel = viewModel(FakeTrackRepository(listOf(track("one"))), controller)

    viewModel.onAction(MusicBoxAction.PlayTrack(TrackId("one"), TrackCollection.LIBRARY))
    advanceUntilIdle()

    assertEquals(MusicBoxDestination.HOME, viewModel.uiState.value.activeDestination)
    assertEquals(LibraryErrorCode.CONNECTION_FAILED, viewModel.uiState.value.errorCode)
  }

  @Test
  fun favoriteToggle_persistsAndRecordsFavoriteChanged() = runTest {
    val one = track("one")
    val repository = FakeTrackRepository(listOf(one))
    val recorder = RecordingPlaybackEventRecorder()
    val viewModel = viewModel(repository, recorder = recorder)

    viewModel.onAction(MusicBoxAction.ToggleFavorite(one.id))
    advanceUntilIdle()

    assertEquals(setOf(one.id), viewModel.uiState.value.favoriteTrackIds)
    assertEquals(PlaybackEventName.FAVORITE_CHANGED, recorder.events.single().name)
    assertEquals(one.id, recorder.events.single().trackId)
  }

  @Test
  fun favoriteAnalyticsFailure_doesNotTurnSuccessfulPersistenceIntoBusinessFailure() = runTest {
    val one = track("one")
    val repository = FakeTrackRepository(listOf(one))
    val recorder = RecordingPlaybackEventRecorder().apply { failure = IllegalStateException("disk") }
    val viewModel = viewModel(repository, recorder = recorder)

    viewModel.onAction(MusicBoxAction.ToggleFavorite(one.id))
    advanceUntilIdle()

    assertEquals(setOf(one.id), viewModel.uiState.value.favoriteTrackIds)
    assertEquals(null, viewModel.uiState.value.errorCode)
  }

  @Test
  fun transportSeekAndQueueActions_delegateExactlyOnce() = runTest {
    val one = track("one")
    val controller = RecordingPlaybackController()
    controller.emit(
      PlaybackState(
        connection = PlaybackConnection.CONNECTED,
        queueTrackIds = listOf(one.id),
        canSeek = true,
        canPrevious = true,
        canNext = true,
      )
    )
    val viewModel = viewModel(FakeTrackRepository(listOf(one)), controller)

    viewModel.onAction(MusicBoxAction.TogglePlayPause)
    viewModel.onAction(MusicBoxAction.Previous)
    viewModel.onAction(MusicBoxAction.Next)
    viewModel.onAction(MusicBoxAction.SeekTo(8_000))
    viewModel.onAction(MusicBoxAction.AddToQueue(one.id))
    viewModel.onAction(MusicBoxAction.RemoveQueueItem(0))
    viewModel.onAction(MusicBoxAction.JumpToQueueItem(0))

    assertEquals(1, controller.toggleCount)
    assertEquals(1, controller.previousCount)
    assertEquals(1, controller.nextCount)
    assertEquals(listOf(8_000L), controller.seekPositions)
    assertEquals(listOf(one), controller.queuedTracks)
    assertEquals(listOf(0), controller.removedQueueIndices)
    assertEquals(listOf(0), controller.skippedQueueIndices)
  }

  @Test
  fun permissionGrant_triggersScanButDenialKeepsDemoLibrary() = runTest {
    val demos = listOf(track("one"), track("two"))
    val scanner = RecordingLibraryScanner()
    val viewModel = viewModel(FakeTrackRepository(demos), scanner = scanner)

    viewModel.onAction(MusicBoxAction.RequestAudioPermission)
    assertTrue(viewModel.uiState.value.permissionRequestPending)
    viewModel.onAction(MusicBoxAction.AudioPermissionResult(false, userInitiated = true))
    advanceUntilIdle()

    assertEquals(0, scanner.scanCount)
    assertFalse(viewModel.uiState.value.hasAudioPermission)
    assertEquals(demos, viewModel.uiState.value.libraryTracks)
    assertEquals(LibraryErrorCode.PERMISSION_REQUIRED, viewModel.uiState.value.errorCode)

    viewModel.onAction(MusicBoxAction.AudioPermissionResult(true, userInitiated = true))
    advanceUntilIdle()

    assertEquals(1, scanner.scanCount)
    assertTrue(viewModel.uiState.value.hasAudioPermission)
    assertFalse(viewModel.uiState.value.isScanning)
  }

  @Test
  fun cachedLocalTracks_areHiddenUntilPermissionAndRefilteredAcrossRevokeAndRegrant() =
    runTest {
      val demo = track("demo")
      val local = track("local", isDemo = false)
      val repository = FakeTrackRepository(listOf(demo)).apply { setLocalTracks(listOf(local)) }
      repository.setFavorite(demo.id, true)
      repository.setFavorite(local.id, true)
      repository.recordRecent(local.id)
      repository.recordRecent(demo.id)
      val scanner = RecordingLibraryScanner()
      val viewModel = viewModel(repository, scanner = scanner)
      advanceUntilIdle()

      assertEquals(LibrarySource.DEMO, viewModel.uiState.value.librarySource)
      assertEquals(listOf(demo), viewModel.uiState.value.libraryTracks)
      assertEquals(setOf(demo.id), viewModel.uiState.value.favoriteTrackIds)
      assertEquals(listOf(demo), viewModel.uiState.value.favoriteTracks)
      assertEquals(listOf(demo), viewModel.uiState.value.recentTracks)
      assertEquals(null, viewModel.uiState.value.errorCode)

      viewModel.onAction(MusicBoxAction.AudioPermissionResult(true, userInitiated = false))
      advanceUntilIdle()

      assertEquals(LibrarySource.LOCAL, viewModel.uiState.value.librarySource)
      assertEquals(listOf(local), viewModel.uiState.value.libraryTracks)
      assertEquals(setOf(demo.id, local.id), viewModel.uiState.value.favoriteTrackIds)
      assertEquals(listOf(demo, local), viewModel.uiState.value.recentTracks)

      viewModel.onAction(MusicBoxAction.AudioPermissionResult(false, userInitiated = false))
      advanceUntilIdle()

      assertEquals(LibrarySource.DEMO, viewModel.uiState.value.librarySource)
      assertEquals(listOf(demo), viewModel.uiState.value.libraryTracks)
      assertEquals(setOf(demo.id), viewModel.uiState.value.favoriteTrackIds)
      assertEquals(listOf(demo), viewModel.uiState.value.favoriteTracks)
      assertEquals(listOf(demo), viewModel.uiState.value.recentTracks)
      assertEquals(LibraryErrorCode.PERMISSION_REQUIRED, viewModel.uiState.value.errorCode)

      viewModel.onAction(MusicBoxAction.AudioPermissionResult(true, userInitiated = false))
      advanceUntilIdle()

      assertEquals(listOf(local), viewModel.uiState.value.libraryTracks)
      assertEquals(setOf(demo.id, local.id), viewModel.uiState.value.favoriteTrackIds)
      assertEquals(2, scanner.scanCount)
    }

  @Test
  fun permissionRevocation_neverPublishesNoPermissionWithCachedLocalTracks() = runTest {
    val demo = track("demo")
    val local = track("local", isDemo = false)
    val repository = FakeTrackRepository(listOf(demo)).apply { setLocalTracks(listOf(local)) }
    repository.setFavorite(local.id, true)
    repository.recordRecent(local.id)
    val viewModel = viewModel(repository)
    val observed = mutableListOf<LibraryUiState>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      viewModel.uiState.collect(observed::add)
    }

    viewModel.onAction(MusicBoxAction.AudioPermissionResult(true, userInitiated = false))
    advanceUntilIdle()
    observed.clear()

    viewModel.onAction(MusicBoxAction.AudioPermissionResult(false, userInitiated = false))
    advanceUntilIdle()

    val inconsistentStates =
      observed.filter { state ->
        !state.hasAudioPermission &&
          (state.libraryTracks + state.favoriteTracks + state.recentTracks +
              state.trackCatalog.values).any { track -> !track.isDemo }
      }
    assertTrue("No-permission states must never expose cached local tracks", inconsistentStates.isEmpty())
  }

  @Test
  fun permissionRevocation_cancelsActiveScanAndClearsScanningFlag() = runTest {
    val started = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
    val scanner =
      RecordingLibraryScanner {
        started.complete(Unit)
        try {
          awaitCancellation()
        } catch (cancellation: CancellationException) {
          cancelled.complete(Unit)
          throw cancellation
        }
      }
    val viewModel = viewModel(FakeTrackRepository(listOf(track("demo"))), scanner = scanner)

    viewModel.onAction(MusicBoxAction.AudioPermissionResult(true, userInitiated = false))
    started.await()
    assertTrue(viewModel.uiState.value.isScanning)

    viewModel.onAction(MusicBoxAction.AudioPermissionResult(false, userInitiated = false))
    cancelled.await()

    assertFalse(viewModel.uiState.value.isScanning)
    assertEquals(LibraryErrorCode.PERMISSION_REQUIRED, viewModel.uiState.value.errorCode)
  }

  @Test
  fun initialPermissionObservation_doesNotShowDenialErrorUntilUserDenies() = runTest {
    val viewModel = viewModel(FakeTrackRepository(listOf(track("demo"))))

    viewModel.onAction(MusicBoxAction.AudioPermissionResult(false, userInitiated = false))
    assertEquals(null, viewModel.uiState.value.errorCode)

    viewModel.onAction(MusicBoxAction.AudioPermissionResult(false, userInitiated = true))
    assertEquals(LibraryErrorCode.PERMISSION_REQUIRED, viewModel.uiState.value.errorCode)
  }

  @Test
  fun rapidFavoriteDoubleToggle_serializesWritesAndEndsAtLatestDesiredState() = runTest {
    val one = track("one")
    val delegate = FakeTrackRepository(listOf(one))
    val repository = GatedFavoriteRepository(delegate)
    val recorder = RecordingPlaybackEventRecorder()
    val viewModel = viewModel(repository, recorder = recorder)

    viewModel.onAction(MusicBoxAction.ToggleFavorite(one.id))
    repository.firstWriteStarted.await()
    viewModel.onAction(MusicBoxAction.ToggleFavorite(one.id))

    assertFalse(one.id in viewModel.uiState.value.favoriteTrackIds)
    assertEquals(listOf(true), repository.requestedValues)

    repository.releaseFirstWrite.complete(Unit)
    advanceUntilIdle()

    assertEquals(listOf(true, false), repository.requestedValues)
    assertFalse(one.id in viewModel.uiState.value.favoriteTrackIds)
    assertEquals(2, recorder.events.count { it.name == PlaybackEventName.FAVORITE_CHANGED })
    assertEquals(null, viewModel.uiState.value.errorCode)
  }

  @Test
  fun positionOnlyPlaybackCallback_reusesCatalogIndexIdentity() = runTest {
    val one = track("one")
    val controller = RecordingPlaybackController()
    val viewModel = viewModel(FakeTrackRepository(listOf(one)), controller)
    advanceUntilIdle()
    val originalCatalog = viewModel.uiState.value.trackCatalog

    controller.emit(
      PlaybackState(
        connection = PlaybackConnection.CONNECTED,
        currentTrackId = one.id,
        positionMs = 500,
        queueTrackIds = listOf(one.id),
      )
    )
    advanceUntilIdle()

    assertSame(originalCatalog, viewModel.uiState.value.trackCatalog)
    assertSame(one, viewModel.uiState.value.currentTrack)
  }

  private fun viewModel(
    repository: TrackRepository,
    controller: RecordingPlaybackController = RecordingPlaybackController(),
    scanner: RecordingLibraryScanner = RecordingLibraryScanner(),
    recorder: RecordingPlaybackEventRecorder = RecordingPlaybackEventRecorder(),
  ) = LibraryViewModel(repository, controller, scanner, recorder)

  private fun track(id: String, isDemo: Boolean = true) =
    Track(
      TrackId(id),
      id,
      null,
      null,
      1_000,
      null,
      "android.resource://app.yinyuehe/$id",
      isDemo,
    )
}

private class RecordingLibraryScanner(
  private val handler: suspend () -> Result<ScanResult> = {
    Result.success(ScanResult(0, 0, 0))
  }
) : LibraryScanner {
  var scanCount = 0

  override suspend fun scan(): Result<ScanResult> {
    scanCount += 1
    return handler()
  }
}

private class GatedFavoriteRepository(
  private val delegate: FakeTrackRepository,
) : TrackRepository by delegate {
  val firstWriteStarted = CompletableDeferred<Unit>()
  val releaseFirstWrite = CompletableDeferred<Unit>()
  val requestedValues = mutableListOf<Boolean>()

  override suspend fun setFavorite(trackId: TrackId, favorite: Boolean): Boolean {
    requestedValues += favorite
    if (requestedValues.size == 1) {
      firstWriteStarted.complete(Unit)
      releaseFirstWrite.await()
    }
    return delegate.setFavorite(trackId, favorite)
  }
}

private class RecordingPlaybackEventRecorder : PlaybackEventRecorder {
  val events = mutableListOf<PlaybackEvent>()
  var failure: Throwable? = null

  override suspend fun record(event: PlaybackEvent) {
    failure?.let { throw it }
    events += event
  }
}

private class RecordingPlaybackController : PlaybackController {
  data class PlayRequest(val tracks: List<Track>, val startIndex: Int, val shuffle: Boolean)

  private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(PlaybackState())
  override val state = mutableState
  val playRequests = mutableListOf<PlayRequest>()
  val seekPositions = mutableListOf<Long>()
  val queuedTracks = mutableListOf<Track>()
  val removedQueueIndices = mutableListOf<Int>()
  val skippedQueueIndices = mutableListOf<Int>()
  var toggleCount = 0
  var previousCount = 0
  var nextCount = 0
  var playResult = true

  fun emit(value: PlaybackState) {
    mutableState.value = value
  }

  override suspend fun play(tracks: List<Track>, startIndex: Int, shuffle: Boolean): Boolean {
    playRequests += PlayRequest(tracks, startIndex, shuffle)
    return playResult
  }

  override fun togglePlayPause() {
    toggleCount += 1
  }

  override fun seekTo(positionMs: Long) {
    seekPositions += positionMs
  }

  override fun seekToPrevious() {
    previousCount += 1
  }

  override fun seekToNext() {
    nextCount += 1
  }

  override fun addToQueue(track: Track) {
    queuedTracks += track
  }

  override fun removeQueueItem(index: Int) {
    removedQueueIndices += index
  }

  override fun skipToQueueItem(index: Int) {
    skippedQueueIndices += index
  }

  override fun setShuffleEnabled(enabled: Boolean) = Unit
}
