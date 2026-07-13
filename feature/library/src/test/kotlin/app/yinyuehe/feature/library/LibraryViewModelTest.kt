package app.yinyuehe.feature.library

import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.testing.FakePlaybackController
import app.yinyuehe.core.testing.FakeTrackRepository
import app.yinyuehe.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

  private fun track(id: String) =
    Track(TrackId(id), id, null, null, 1_000, null, "android.resource://app.yinyuehe/$id", true)
}
