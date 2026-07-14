package app.yinyuehe.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.designsystem.theme.YinYueHeTheme
import app.yinyuehe.core.player.PlaybackConnection
import app.yinyuehe.core.player.PlaybackState
import app.yinyuehe.core.player.PlaybackToggleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryScreenTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun playerToggle_usesDerivedPlayPauseLabelsAndAvailability() {
    var state by
      mutableStateOf(
        screenState().copy(
          activeDestination = MusicBoxDestination.PLAYER,
          playback =
            PlaybackState(
              toggleAction = PlaybackToggleAction.PAUSE,
              canTogglePlayPause = true,
            ),
        )
      )
    composeRule.setContent {
      YinYueHeTheme { LibraryScreen(state, onAction = {}) }
    }

    composeRule.onNodeWithContentDescription("暂停").assertIsEnabled()

    state =
      state.copy(
        playback =
          PlaybackState(
            toggleAction = PlaybackToggleAction.PLAY,
            canTogglePlayPause = false,
          )
      )

    composeRule.onNodeWithContentDescription("播放").assertIsNotEnabled()

    state =
      state.copy(
        playback =
          PlaybackState(
            toggleAction = PlaybackToggleAction.PLAY,
            canTogglePlayPause = true,
          )
      )

    composeRule.onNodeWithContentDescription("播放").assertIsEnabled()
  }

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
    composeRule.onNodeWithContentDescription("收藏one").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("将one加入播放队列").assertIsDisplayed()
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
                canTogglePlayPause = true,
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
    composeRule.onNodeWithTag("player-seek").performTouchInput {
      click(percentOffset(x = 0.75f, y = 0.5f))
    }
    composeRule.onNodeWithContentDescription("跳转到one").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("从队列移除one").assertIsDisplayed()
    composeRule.onNodeWithTag("player-queue-jump-0").performClick()
    composeRule.onNodeWithTag("player-queue-remove-0").performClick()

    assertTrue(MusicBoxAction.TogglePlayPause in actions)
    assertTrue(MusicBoxAction.Previous in actions)
    assertTrue(MusicBoxAction.Next in actions)
    assertEquals(1, actions.filterIsInstance<MusicBoxAction.SeekTo>().size)
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
    composeRule.onAllNodesWithContentDescription("取消收藏one").assertCountEquals(2)
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

  @Test
  fun pendingPermissionRequest_disablesRequestButton() {
    composeRule.setContent {
      YinYueHeTheme {
        LibraryScreen(
          screenState().copy(permissionRequestPending = true),
          onAction = {},
        )
      }
    }

    composeRule.onNodeWithTag("home-request-permission").assertIsNotEnabled()
  }

  private fun screenState(vararg tracks: Track) =
    tracks.toList().ifEmpty { listOf(track("one")) }.let { catalog ->
      LibraryUiState(
        isLoading = false,
        libraryTracks = catalog,
        trackCatalog = catalog.associateBy(Track::id),
      )
    }

  private fun track(id: String) =
    Track(TrackId(id), id, "Artist", null, 1_000, null, "uri:$id", true)
}
