package app.yinyuehe.feature.library

import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.testing.FakePlaybackController
import app.yinyuehe.core.testing.FakeTrackRepository
import app.yinyuehe.core.testing.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun repositoryTracks_areExposedInOrder() = runTest {
    val repository = FakeTrackRepository(listOf(track("one"), track("two")))
    val viewModel = LibraryViewModel(repository, FakePlaybackController())

    assertEquals(listOf("one", "two"), viewModel.uiState.value.tracks.map { it.id.value })
  }

  @Test
  fun selectingTrack_playsWholeQueueAtSelectedIndex() = runTest {
    val tracks = listOf(track("one"), track("two"))
    val player = FakePlaybackController()
    val viewModel = LibraryViewModel(FakeTrackRepository(tracks), player)

    viewModel.onTrackClick(TrackId("two"))

    assertEquals(1, player.playRequests.single().startIndex)
    assertEquals(tracks, player.playRequests.single().tracks)
  }

  @Test
  fun rejectedPlayback_exposesConnectionError() = runTest {
    val player = FakePlaybackController().apply { playResult = false }
    val viewModel = LibraryViewModel(FakeTrackRepository(listOf(track("one"))), player)

    viewModel.onTrackClick(TrackId("one"))

    assertEquals(PlaybackError.CONNECTION_FAILED, viewModel.uiState.value.playbackError)
  }

  @Test
  fun playbackException_exposesPlaybackError() = runTest {
    val player =
      FakePlaybackController().apply {
        playFailure = IllegalStateException("Media3 rejected the request")
      }
    val viewModel = LibraryViewModel(FakeTrackRepository(listOf(track("one"))), player)

    viewModel.onTrackClick(TrackId("one"))

    assertEquals(PlaybackError.PLAYBACK_FAILED, viewModel.uiState.value.playbackError)
  }

  @Test
  fun successfulRetry_clearsPlaybackError() = runTest {
    val player = FakePlaybackController().apply { playResult = false }
    val viewModel = LibraryViewModel(FakeTrackRepository(listOf(track("one"))), player)
    viewModel.onTrackClick(TrackId("one"))
    assertEquals(PlaybackError.CONNECTION_FAILED, viewModel.uiState.value.playbackError)

    player.playResult = true
    viewModel.onTrackClick(TrackId("one"))

    assertNull(viewModel.uiState.value.playbackError)
  }

  @Test
  fun newerSuccess_isNotOverwrittenByCancelledOlderFailure() = runTest {
    val firstResult = CompletableDeferred<Boolean>()
    val player = FakePlaybackController()
    player.playHandler = { request ->
      if (request.startIndex == 0) {
        try {
          firstResult.await()
        } catch (_: CancellationException) {
          false
        }
      } else {
        true
      }
    }
    val viewModel =
      LibraryViewModel(
        FakeTrackRepository(listOf(track("one"), track("two"))),
        player,
      )

    viewModel.onTrackClick(TrackId("one"))
    viewModel.onTrackClick(TrackId("two"))

    assertEquals(2, player.playRequests.size)
    assertNull(viewModel.uiState.value.playbackError)
  }

  private fun track(id: String) =
    Track(TrackId(id), id, null, null, 1_000, null, "android.resource://app.yinyuehe/$id", true)
}
