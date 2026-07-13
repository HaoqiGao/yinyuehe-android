package app.yinyuehe

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import app.yinyuehe.core.designsystem.theme.YinYueHeTheme
import app.yinyuehe.feature.library.LibraryRoute
import app.yinyuehe.feature.library.LibraryViewModel

@Composable
fun YinYueHeApp() {
  YinYueHeTheme {
    LibraryRoute(viewModel<LibraryViewModel>())
  }
}
