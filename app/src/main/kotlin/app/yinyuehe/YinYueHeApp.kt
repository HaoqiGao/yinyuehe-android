package app.yinyuehe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.viewmodel.compose.viewModel
import app.yinyuehe.core.designsystem.theme.YinYueHeTheme
import app.yinyuehe.feature.library.LibraryRoute
import app.yinyuehe.feature.library.LibraryViewModel

@Composable
fun YinYueHeApp(
  hasAudioPermission: Boolean,
  permissionResultVersion: Int,
  onRequestAudioPermission: () -> Unit,
  onFirstFrame: () -> Unit,
) {
  YinYueHeTheme {
    ReportFirstFrame(onFirstFrame)
    LibraryRoute(
      viewModel = viewModel<LibraryViewModel>(),
      hasAudioPermission = hasAudioPermission,
      permissionResultVersion = permissionResultVersion,
      onRequestAudioPermission = onRequestAudioPermission,
    )
  }
}

@Composable
internal fun ReportFirstFrame(onFirstFrame: () -> Unit) {
  val latestCallback = rememberUpdatedState(onFirstFrame)
  LaunchedEffect(Unit) {
    withFrameNanos { }
    latestCallback.value()
  }
}
