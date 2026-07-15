package app.yinyuehe.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yinyuehe.core.common.playback.PlaybackErrorType

@Composable
fun LibraryRoute(
  viewModel: LibraryViewModel,
  hasAudioPermission: Boolean,
  permissionResultVersion: Int,
  onRequestAudioPermission: () -> Unit,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val context = LocalContext.current
  LaunchedEffect(hasAudioPermission, permissionResultVersion) {
    viewModel.onAction(
      MusicBoxAction.AudioPermissionResult(
        granted = hasAudioPermission,
        userInitiated = permissionResultVersion > 0,
      )
    )
  }
  LaunchedEffect(viewModel) {
    viewModel.effects.collect { effect ->
      val message =
        when (effect) {
          is MusicBoxEffect.TrackSkipped ->
            context.getString(effect.errorType.skippedMessageResource())
        }
      snackbarHostState.showSnackbar(message)
    }
  }
  LibraryScreenContent(
    state = state,
    snackbarHostState = snackbarHostState,
    onAction = { action ->
      viewModel.onAction(action)
      if (action == MusicBoxAction.RequestAudioPermission) onRequestAudioPermission()
    },
  )
}

@Composable
fun LibraryScreen(
  state: LibraryUiState,
  onAction: (MusicBoxAction) -> Unit,
) {
  LibraryScreenContent(
    state = state,
    snackbarHostState = remember { SnackbarHostState() },
    onAction = onAction,
  )
}

@Composable
private fun LibraryScreenContent(
  state: LibraryUiState,
  snackbarHostState: SnackbarHostState,
  onAction: (MusicBoxAction) -> Unit,
) {
  Scaffold(
    snackbarHost = { SnackbarHost(snackbarHostState) },
    bottomBar = {
      NavigationBar {
        MusicBoxDestination.entries.forEach { destination ->
          NavigationBarItem(
            selected = state.activeDestination == destination,
            onClick = { onAction(MusicBoxAction.SelectDestination(destination)) },
            icon = { Text(destination.symbol) },
            label = { Text(destination.label) },
            modifier = Modifier.testTag(destination.testTag),
          )
        }
      }
    }
  ) { innerPadding ->
    Column(Modifier.fillMaxSize().padding(innerPadding)) {
      state.errorCode?.let { error ->
        Text(
          text = error.message,
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
        )
      }
      Box(Modifier.fillMaxSize().weight(1f)) {
        val consumedInsets = PaddingValues()
        when (state.activeDestination) {
          MusicBoxDestination.HOME -> HomeScreen(state, consumedInsets, onAction)
          MusicBoxDestination.PLAYER -> PlayerScreen(state, consumedInsets, onAction)
          MusicBoxDestination.PLAYLISTS -> PlaylistsScreen(state, consumedInsets, onAction)
        }
      }
    }
  }
}

private val MusicBoxDestination.label: String
  get() =
    when (this) {
      MusicBoxDestination.HOME -> "曲库"
      MusicBoxDestination.PLAYER -> "播放"
      MusicBoxDestination.PLAYLISTS -> "歌单"
    }

private val MusicBoxDestination.symbol: String
  get() =
    when (this) {
      MusicBoxDestination.HOME -> "⌂"
      MusicBoxDestination.PLAYER -> "▶"
      MusicBoxDestination.PLAYLISTS -> "≡"
    }

private val MusicBoxDestination.testTag: String
  get() = "destination-${name.lowercase()}"

private val LibraryErrorCode.message: String
  get() =
    when (this) {
      LibraryErrorCode.CONNECTION_FAILED -> "播放器连接失败，请重试"
      LibraryErrorCode.PLAYBACK_FAILED -> "播放失败，请重试"
      LibraryErrorCode.PERMISSION_REQUIRED -> "未授权本地音频，仍可播放示例音乐"
      LibraryErrorCode.SCAN_FAILED -> "本地曲库扫描失败，请重试"
      LibraryErrorCode.FAVORITE_UPDATE_FAILED -> "收藏更新失败，请重试"
    }

internal fun PlaybackErrorType.terminalMessageResource(): Int =
  when (this) {
    PlaybackErrorType.SOURCE_UNAVAILABLE -> R.string.playback_error_source_unavailable
    PlaybackErrorType.UNSUPPORTED_FORMAT -> R.string.playback_error_unsupported_format
    PlaybackErrorType.DECODER -> R.string.playback_error_decoder
    PlaybackErrorType.UNKNOWN -> R.string.playback_error_unknown
  }

internal fun PlaybackErrorType.skippedMessageResource(): Int =
  when (this) {
    PlaybackErrorType.SOURCE_UNAVAILABLE -> R.string.playback_skipped_source_unavailable
    PlaybackErrorType.UNSUPPORTED_FORMAT -> R.string.playback_skipped_unsupported_format
    PlaybackErrorType.DECODER -> R.string.playback_skipped_decoder
    PlaybackErrorType.UNKNOWN -> R.string.playback_skipped_unknown
  }
