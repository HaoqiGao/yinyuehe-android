package app.yinyuehe.feature.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import app.yinyuehe.core.designsystem.theme.YinYueHeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LibraryScreenTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun trackRow_isVisibleAndClickable() {
    var selected: TrackId? = null
    val track = Track(TrackId("demo:test"), "晨间节拍", "Demo Band", null, 3_200, null, "uri", true)
    composeRule.setContent {
      YinYueHeTheme {
        LibraryScreen(LibraryUiState(tracks = listOf(track))) { selected = it }
      }
    }

    composeRule.onNodeWithText("晨间节拍").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("播放晨间节拍").performClick()
    assertEquals(TrackId("demo:test"), selected)
  }
}
