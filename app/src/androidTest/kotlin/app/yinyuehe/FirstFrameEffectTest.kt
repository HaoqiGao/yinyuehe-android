package app.yinyuehe

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FirstFrameEffectTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun firstRenderedFrame_reportsExactlyOnce() {
    var reportCount = 0
    composeRule.setContent {
      ReportFirstFrame { reportCount += 1 }
    }

    composeRule.waitForIdle()

    assertEquals(1, reportCount)
  }
}
