package app.yinyuehe

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPermissionBoundaryTest {
  @Test
  fun api33AndAbove_usesReadMediaAudio() {
    assertEquals(Manifest.permission.READ_MEDIA_AUDIO, requiredAudioPermission(33))
    assertEquals(Manifest.permission.READ_MEDIA_AUDIO, requiredAudioPermission(36))
  }

  @Test
  fun api26Through32_usesReadExternalStorage() {
    assertEquals(Manifest.permission.READ_EXTERNAL_STORAGE, requiredAudioPermission(26))
    assertEquals(Manifest.permission.READ_EXTERNAL_STORAGE, requiredAudioPermission(32))
  }
}
