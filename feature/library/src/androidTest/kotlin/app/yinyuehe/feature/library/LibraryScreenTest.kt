package app.yinyuehe.feature.library

import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.yinyuehe.core.common.analytics.PlaybackEvent
import app.yinyuehe.core.common.analytics.PlaybackEventRecorder
import app.yinyuehe.core.common.model.LibraryContent
import app.yinyuehe.core.common.model.LibrarySource
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.common.playback.PlaybackConnectionError
import app.yinyuehe.core.common.playback.PlaybackError
import app.yinyuehe.core.common.playback.PlaybackErrorType
import app.yinyuehe.core.common.playback.PlaybackNotice
import app.yinyuehe.core.common.playback.PlaybackRepeatMode
import app.yinyuehe.core.data.TrackRepository
import app.yinyuehe.core.data.scan.LibraryScanner
import app.yinyuehe.core.data.scan.ScanResult
import app.yinyuehe.core.designsystem.theme.YinYueHeTheme
import app.yinyuehe.core.player.PlaybackController
import app.yinyuehe.core.player.PlaybackConnection
import app.yinyuehe.core.player.PlaybackState
import app.yinyuehe.core.player.PlaybackToggleAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
      YinYueHeTheme {
        LibraryScreen(
          screenState(one).copy(playback = PlaybackState(canChangeQueue = true)),
          actions::add,
        )
      }
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
                canChangeQueue = true,
                canSkipToQueueItem = true,
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
  fun repeatAndShuffle_renderCallbackStateDispatchActionsAndRespectCapabilities() {
    val actions = mutableListOf<MusicBoxAction>()
    var state by
      mutableStateOf(
        screenState().copy(
          activeDestination = MusicBoxDestination.PLAYER,
          playback =
            PlaybackState(
              repeatMode = PlaybackRepeatMode.ALL,
              shuffleEnabled = true,
              canSetRepeatMode = true,
              canSetShuffle = true,
            ),
        )
      )
    composeRule.setContent {
      YinYueHeTheme { LibraryScreen(state, actions::add) }
    }

    composeRule.onNodeWithTag("player-repeat").assertIsEnabled().assertHeightIsAtLeast(48.dp)
    composeRule.onNodeWithTag("player-repeat").assertWidthIsAtLeast(48.dp).performClick()
    composeRule.onNodeWithTag("player-shuffle").assertIsEnabled().assertHeightIsAtLeast(48.dp)
    composeRule.onNodeWithTag("player-shuffle").assertWidthIsAtLeast(48.dp).performClick()
    composeRule.onNodeWithText("重复：全部").assertIsDisplayed()
    composeRule.onNodeWithText("随机：开").assertIsDisplayed()
    assertEquals(
      listOf(MusicBoxAction.CycleRepeatMode, MusicBoxAction.ToggleShuffle),
      actions,
    )

    state =
      state.copy(
        playback =
          state.playback.copy(
            repeatMode = PlaybackRepeatMode.ONE,
            shuffleEnabled = false,
            canSetRepeatMode = false,
            canSetShuffle = false,
          )
      )

    composeRule.onNodeWithText("重复：单曲").assertIsDisplayed()
    composeRule.onNodeWithText("随机：关").assertIsDisplayed()
    composeRule.onNodeWithTag("player-repeat").assertIsNotEnabled()
    composeRule.onNodeWithTag("player-shuffle").assertIsNotEnabled()
  }

  @Test
  fun limitedQueue_disablesEditsButKeepsTransportAndFullReplacementAvailable() {
    val one = track("one")
    val actions = mutableListOf<MusicBoxAction>()
    val limitedPlayback =
      PlaybackState(
        connection = PlaybackConnection.CONNECTED,
        currentTrackId = one.id,
        currentIndex = 0,
        queueTrackIds = listOf(one.id, one.id),
        queuePersistenceLimited = true,
        canTogglePlayPause = true,
        canPrevious = true,
        canNext = true,
        canChangeQueue = true,
        canSkipToQueueItem = true,
      )
    var state by mutableStateOf(screenState(one).copy(playback = limitedPlayback))
    composeRule.setContent {
      YinYueHeTheme { LibraryScreen(state, actions::add) }
    }

    composeRule.onNodeWithTag("home-play-all").assertIsEnabled().performClick()
    composeRule.onNodeWithTag("home-play-random").assertIsEnabled().performClick()
    composeRule.onNodeWithTag("home-play-one").performClick()
    composeRule.onNodeWithTag("home-add-queue-one").assertIsNotEnabled()
    assertTrue(MusicBoxAction.PlayAll(TrackCollection.LIBRARY) in actions)
    assertTrue(MusicBoxAction.PlayRandom(TrackCollection.LIBRARY) in actions)
    assertTrue(MusicBoxAction.PlayTrack(one.id, TrackCollection.LIBRARY) in actions)

    state = state.copy(activeDestination = MusicBoxDestination.PLAYER)

    composeRule.onNodeWithTag("player-toggle").assertIsEnabled().performClick()
    composeRule.onNodeWithTag("player-previous").assertIsEnabled()
    composeRule.onNodeWithTag("player-next").assertIsEnabled()
    composeRule.onNodeWithTag("player-queue-remove-0").assertIsNotEnabled()
    composeRule.onNodeWithTag("player-queue-move-up-0").assertIsNotEnabled()
    composeRule.onNodeWithTag("player-queue-move-down-0").assertIsNotEnabled()
    composeRule.onNodeWithText("重新播放全部可替代受保护的旧队列").assertIsDisplayed()
    assertTrue(MusicBoxAction.TogglePlayPause in actions)
  }

  @Test
  fun movementControls_haveOccurrenceBoundsLocalizedLabelsAndMinimumTouchTargets() {
    val one = track("one")
    val actions = mutableListOf<MusicBoxAction>()
    composeRule.setContent {
      YinYueHeTheme {
        LibraryScreen(
          screenState(one).copy(
            activeDestination = MusicBoxDestination.PLAYER,
            playback =
              PlaybackState(
                queueTrackIds = listOf(one.id, one.id),
                canChangeQueue = true,
              ),
          ),
          actions::add,
        )
      }
    }

    composeRule.onAllNodesWithContentDescription("上移one").assertCountEquals(2)
    composeRule.onAllNodesWithContentDescription("下移one").assertCountEquals(2)
    composeRule.onNodeWithTag("player-queue-move-up-0").assertIsNotEnabled()
      .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    composeRule.onNodeWithTag("player-queue-move-down-0").assertIsEnabled()
      .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
    composeRule.onNodeWithTag("player-queue-move-up-1").assertIsEnabled().performClick()
    composeRule.onNodeWithTag("player-queue-move-down-1").assertIsNotEnabled()

    assertTrue(MusicBoxAction.MoveQueueItem(0, QueueMoveDirection.DOWN) in actions)
    assertTrue(MusicBoxAction.MoveQueueItem(1, QueueMoveDirection.UP) in actions)
  }

  @Test
  fun jumpCapability_isIndependentFromQueueEditingCapability() {
    val one = track("one")
    var playback by
      mutableStateOf(
        PlaybackState(
          queueTrackIds = listOf(one.id, one.id),
          canChangeQueue = false,
          canSkipToQueueItem = true,
        )
      )
    composeRule.setContent {
      YinYueHeTheme {
        LibraryScreen(
          screenState(one).copy(
            activeDestination = MusicBoxDestination.PLAYER,
            playback = playback,
          ),
          onAction = {},
        )
      }
    }

    composeRule.onNodeWithTag("player-queue-jump-0").assertIsEnabled()
    composeRule.onNodeWithTag("player-queue-remove-0").assertIsNotEnabled()
    composeRule.onNodeWithTag("player-queue-move-down-0").assertIsNotEnabled()

    playback = playback.copy(canChangeQueue = true, canSkipToQueueItem = false)

    composeRule.onNodeWithTag("player-queue-jump-0").assertIsNotEnabled()
    composeRule.onNodeWithTag("player-queue-remove-0").assertIsEnabled()
    composeRule.onNodeWithTag("player-queue-move-down-0").assertIsEnabled()
  }

  @Test
  fun typedTerminalErrorsAndRecoveryGuidance_areLocalizedAndSanitized() {
    var playback by
      mutableStateOf(
        PlaybackState(
          playbackError = playbackError(PlaybackErrorType.SOURCE_UNAVAILABLE),
        )
      )
    composeRule.setContent {
      YinYueHeTheme {
        LibraryScreen(
          screenState().copy(
            activeDestination = MusicBoxDestination.PLAYER,
            playback = playback,
          ),
          onAction = {},
        )
      }
    }

    composeRule.onNodeWithText("音频源不可用，请检查文件后重试").assertIsDisplayed()
    playback = playback.copy(playbackError = playbackError(PlaybackErrorType.UNSUPPORTED_FORMAT))
    composeRule.onNodeWithText("音频格式不受支持").assertIsDisplayed()
    playback = playback.copy(playbackError = playbackError(PlaybackErrorType.DECODER))
    composeRule.onNodeWithText("音频解码失败，请重试").assertIsDisplayed()
    playback = playback.copy(playbackError = playbackError(PlaybackErrorType.UNKNOWN))
    composeRule.onNodeWithText("播放失败，请重试").assertIsDisplayed()

    playback =
      playback.copy(
        playbackError = null,
        connectionError = PlaybackConnectionError.RETRIES_EXHAUSTED,
        queuePersistenceLimited = true,
      )
    composeRule.onNodeWithText("播放器连接多次失败，请检查后重试").assertIsDisplayed()
    composeRule.onNodeWithText("重新播放全部可替代受保护的旧队列").assertIsDisplayed()
    composeRule.onNodeWithText("9876", substring = true).assertDoesNotExist()
    composeRule.onNodeWithText("private/track/path", substring = true).assertDoesNotExist()
    composeRule.onNodeWithText("private-track-id", substring = true).assertDoesNotExist()
  }

  @Test
  fun routeShowsSkippedTrackEffectOnceAndDoesNotReplayAfterCollectorRestart() {
    val controller = RoutePlaybackController()
    val viewModel =
      LibraryViewModel(
        repository = RouteTrackRepository(track("one")),
        playbackController = controller,
        libraryScanner = RouteLibraryScanner,
        playbackEventRecorder = RoutePlaybackEventRecorder,
      )
    var routeGeneration by mutableIntStateOf(0)
    composeRule.setContent {
      YinYueHeTheme {
        key(routeGeneration) {
          LibraryRoute(
            viewModel = viewModel,
            hasAudioPermission = false,
            permissionResultVersion = 0,
            onRequestAudioPermission = {},
          )
        }
      }
    }
    composeRule.waitForIdle()

    controller.emitNotice(
      PlaybackNotice.TrackSkipped(playbackError(PlaybackErrorType.DECODER))
    )
    composeRule.onNodeWithText("已跳过：音频解码失败").assertIsDisplayed()

    composeRule.runOnIdle { routeGeneration += 1 }
    composeRule.onNodeWithText("已跳过：音频解码失败").assertDoesNotExist()
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

  private fun playbackError(type: PlaybackErrorType) =
    PlaybackError(type, 9876, TrackId("private-track-id"))
}

private class RoutePlaybackController : PlaybackController {
  private val mutableState = MutableStateFlow(PlaybackState())
  private val mutableNotices = MutableSharedFlow<PlaybackNotice>(extraBufferCapacity = 1)
  override val state = mutableState
  override val notices: Flow<PlaybackNotice> = mutableNotices

  fun emitNotice(notice: PlaybackNotice) {
    check(mutableNotices.tryEmit(notice))
  }

  override suspend fun play(tracks: List<Track>, startIndex: Int, shuffle: Boolean) = true
  override fun togglePlayPause() = Unit
  override fun seekTo(positionMs: Long) = Unit
  override fun seekToPrevious() = Unit
  override fun seekToNext() = Unit
  override fun addToQueue(track: Track) = Unit
  override fun removeQueueItem(index: Int) = Unit
  override fun skipToQueueItem(index: Int) = Unit
  override fun setShuffleEnabled(enabled: Boolean) = Unit
  override fun setRepeatMode(mode: PlaybackRepeatMode) = Unit
  override fun moveQueueItem(fromIndex: Int, toIndex: Int) = Unit
}

private class RouteTrackRepository(
  private val track: Track,
) : TrackRepository {
  override fun observeAvailableLocalTracks() = flowOf(emptyList<Track>())
  override fun demoTracks() = listOf(track)
  override fun observeLibrary() = flowOf(LibraryContent(LibrarySource.DEMO, listOf(track)))
  override fun observeFavoriteTrackIds() = flowOf(emptySet<TrackId>())
  override fun observeFavoriteTracks() = flowOf(emptyList<Track>())
  override fun observeRecentTracks() = flowOf(emptyList<Track>())
  override suspend fun setFavorite(trackId: TrackId, favorite: Boolean) = true
  override suspend fun recordRecent(trackId: TrackId, positionMs: Long?) = true
}

private object RouteLibraryScanner : LibraryScanner {
  override suspend fun scan() = Result.success(ScanResult(0, 0, 0))
}

private object RoutePlaybackEventRecorder : PlaybackEventRecorder {
  override suspend fun record(event: PlaybackEvent) = Unit
}
