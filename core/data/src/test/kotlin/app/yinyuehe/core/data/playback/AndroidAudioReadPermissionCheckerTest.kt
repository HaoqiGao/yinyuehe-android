package app.yinyuehe.core.data.playback

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class AndroidAudioReadPermissionCheckerTest {
  @Test
  fun permissionName_switchesAtApi33() {
    assertEquals(
      Manifest.permission.READ_EXTERNAL_STORAGE,
      requiredAudioReadPermission(32),
    )
    assertEquals(
      Manifest.permission.READ_MEDIA_AUDIO,
      requiredAudioReadPermission(33),
    )
    assertEquals(
      Manifest.permission.READ_MEDIA_AUDIO,
      requiredAudioReadPermission(36),
    )
  }

  @Test
  @Config(sdk = [33])
  fun checkerReflectsDeniedThenGrantedRuntimePermission() {
    val application = ApplicationProvider.getApplicationContext<Application>()
    val checker = AndroidAudioReadPermissionChecker(application)

    assertFalse(checker.hasAudioReadPermission())
    shadowOf(application).grantPermissions(Manifest.permission.READ_MEDIA_AUDIO)
    assertTrue(checker.hasAudioReadPermission())
  }
}
