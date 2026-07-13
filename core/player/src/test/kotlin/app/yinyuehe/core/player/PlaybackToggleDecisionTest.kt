package app.yinyuehe.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackToggleDecisionTest {
  @Test
  fun toggle_pausesWhenPlaybackWasRequested() {
    assertTrue(shouldPauseForToggle(playWhenReady = true))
  }

  @Test
  fun toggle_playsWhenPlaybackWasNotRequested() {
    assertFalse(shouldPauseForToggle(playWhenReady = false))
  }
}
