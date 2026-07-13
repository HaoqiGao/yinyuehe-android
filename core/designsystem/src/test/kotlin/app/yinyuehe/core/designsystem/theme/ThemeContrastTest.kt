package app.yinyuehe.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
  @Test
  fun primaryContent_meetsWcagAa() {
    assertTrue(contrast(OnLightPrimary, LightPrimary) >= 4.5)
    assertTrue(contrast(OnDarkPrimary, DarkPrimary) >= 4.5)
  }

  @Test
  fun backgroundContent_meetsWcagAa() {
    assertTrue(contrast(LightOnBackground, LightBackground) >= 4.5)
    assertTrue(contrast(DarkOnBackground, DarkBackground) >= 4.5)
  }

  private fun contrast(foreground: Color, background: Color): Float {
    val lighter = maxOf(foreground.luminance(), background.luminance())
    val darker = minOf(foreground.luminance(), background.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
  }
}
