package app.yinyuehe.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.designsystem.theme.YinYueHeTheme
import app.yinyuehe.core.player.PlaybackConnection
import app.yinyuehe.core.player.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryScreenTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun bottomNavigation_reachesExactlyHomePlayerAndPlaylists() {
    var state by mutableStateOf(screenState())
    composeRule.setContent {
      YinYueHeTheme {
        LibraryScreen(state) { action ->
          if (action is MusicBoxAction.SelectDestination) {
            state = state.copy(activeDestination = action.destination)
          }
        }
      }
    }

    composeRule.onNodeWithTag("destination-home").assertIsDisplayed()
    composeRule.onNodeWithTag("home-track-list").assertIsDisplayed()
    composeRule.onNodeWithTag("destination-player").performClick()
    composeRule.onNodeWithTag("player-queue").assertIsDisplayed()
    composeRule.onNodeWithTag("destination-playlists").performClick()
    composeRule.onNodeWithTag("playlists-recent").assertIsDisplayed()
  }

  @Test
  fun home_dispatchesPlayAllRandomTrackFavoriteAndQueueActions() {
    val actions = mutableListOf<MusicBoxAction>()
    val one = track("one")
    composeRule.setContent {
      YinYueHeTheme { LibraryScreen(screenState(one), actions::add) }
    }

    composeRule.onNodeWithTag("home-play-all").performClick()
    composeRule.onNodeWithTag("home-play-random").performClick()
    composeRule.onNodeWithTag("home-play-one").performClick()
    composeRule.onNodeWithTag("home-favorite-one").performClick()
    composeRule.onNodeWithTag("home-add-queue-one").performClick()

    assertEquals(
      listOf(
        MusicBoxAction.PlayAll(TrackCollection.LIBRARY),
        MusicBoxAction.PlayRandom(TrackCollection.LIBRARY),
        MusicBoxAction.PlayTrack(one.id, TrackCollection.LIBRARY),
        MusicBoxAction.ToggleFavorite(one.id),
        MusicBoxAction.AddToQueue(one.id),
      ),
      actions,
    )
  }

  @Test
  fun player_dispatchesTransportSeekAndQueueActions() {
    val actions = mutableListOf<MusicBoxAction>()
    val one = track("one")
    composeRule.setContent {
      YinYueHeTheme {
        LibraryScreen(
          screenState(one).copy(
            activeDestination = MusicBoxDestination.PLAYER,
            playback =
              PlaybackState(
                connection = PlaybackConnection.CONNECTED,
                currentTrackId = one.id,
                queueTrackIds = listOf(one.id),
                durationMs = 1_000,
                canSeek = true,
                canPrevious = true,
                canNext = true,
              ),
          ),
          actions::add,
        )
      }
    }

    composeRule.onNodeWithTag("player-toggle").performClick()
    composeRule.onNodeWithTag("player-previous").performClick()
    composeRule.onNodeWithTag("player-next").performClick()
    composeRule.onNodeWithTag("player-seek").performSemanticsAction(SemanticsActions.SetProgress) {
      it(500f)
    }
    composeRule.onNodeWithTag("player-queue-jump-0").performClick()
    composeRule.onNodeWithTag("player-queue-remove-0").performClick()

    assertTrue(MusicBoxAction.TogglePlayPause in actions)
    assertTrue(MusicBoxAction.Previous in actions)
    assertTrue(MusicBoxAction.Next in actions)
    assertTrue(MusicBoxAction.SeekTo(500) in actions)
    assertTrue(MusicBoxAction.JumpToQueueItem(0) in actions)
    assertTrue(MusicBoxAction.RemoveQueueItem(0) in actions)
  }

  @Test
  fun playerQueue_allowsRepeatedTrackIdsWithoutDuplicateLazyKeys() {
    val one = track("one")
    composeRule.setContent {
      YinYueHeTheme {
        LibraryScreen(
          screenState(one).copy(
            activeDestination = MusicBoxDestination.PLAYER,
            playback = PlaybackState(queueTrackIds = listOf(one.id, one.id)),
          ),
          onAction = {},
        )
      }
    }

    composeRule.onNodeWithTag("player-queue").assertIsDisplayed()
    composeRule.waitForIdle()
  }

  @Test
  fun playlists_dispatchesFavoriteAndRecentCollectionActions() {
    val actions = mutableListOf<MusicBoxAction>()
    val one = track("one")
    composeRule.setContent {
      YinYueHeTheme {
        LibraryScreen(
          screenState(one).copy(
            activeDestination = MusicBoxDestination.PLAYLISTS,
            favoriteTrackIds = setOf(one.id),
            favoriteTracks = listOf(one),
            recentTracks = listOf(one),
          ),
          actions::add,
        )
      }
    }

    composeRule.onNodeWithTag("favorites-play-all").performClick()
    composeRule.onNodeWithTag("favorites-play-random").performClick()
    composeRule.onNodeWithTag("recent-play-all").performClick()
    composeRule.onNodeWithTag("recent-play-random").performClick()
    composeRule.onNodeWithTag("playlists-favorite-one").performClick()

    assertEquals(
      listOf(
        MusicBoxAction.PlayAll(TrackCollection.FAVORITES),
        MusicBoxAction.PlayRandom(TrackCollection.FAVORITES),
        MusicBoxAction.PlayAll(TrackCollection.RECENT),
        MusicBoxAction.PlayRandom(TrackCollection.RECENT),
        MusicBoxAction.ToggleFavorite(one.id),
      ),
      actions,
    )
  }

  private fun screenState(vararg tracks: Track) =
    LibraryUiState(
      isLoading = false,
      libraryTracks = tracks.toList().ifEmpty { listOf(track("one")) },
    )

  private fun track(id: String) =
    Track(TrackId(id), id, "Artist", null, 1_000, null, "uri:$id", true)
}
