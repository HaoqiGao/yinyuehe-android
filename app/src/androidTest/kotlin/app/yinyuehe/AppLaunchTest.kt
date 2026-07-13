package app.yinyuehe

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLaunchTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun launch_displaysDemoCatalog() {
    composeRule.onNodeWithText("晨间节拍").assertIsDisplayed()
    composeRule.onNodeWithText("夜行模式").assertIsDisplayed()
  }
}
