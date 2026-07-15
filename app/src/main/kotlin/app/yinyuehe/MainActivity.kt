package app.yinyuehe

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
  @Inject internal lateinit var firstFrameAnalytics: ProcessFirstFrameAnalytics

  private val activityStartElapsedMs = SystemClock.elapsedRealtime()
  private var permissionState by mutableStateOf(AudioPermissionState())
  private lateinit var audioPermission: String

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    audioPermission = requiredAudioPermission(Build.VERSION.SDK_INT)
    val launcher =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionState =
          AudioPermissionState(
            granted = granted,
            resultVersion = permissionState.resultVersion + 1,
          )
      }
    refreshAudioPermission()

    setContent {
      YinYueHeApp(
        hasAudioPermission = permissionState.granted,
        permissionResultVersion = permissionState.resultVersion,
        onRequestAudioPermission = { launcher.launch(audioPermission) },
        onFirstFrame = ::recordFirstFrame,
      )
    }
  }

  override fun onResume() {
    super.onResume()
    if (::audioPermission.isInitialized) refreshAudioPermission()
  }

  private fun refreshAudioPermission() {
    val granted =
      ContextCompat.checkSelfPermission(this, audioPermission) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    if (permissionState.granted != granted) {
      permissionState = permissionState.copy(granted = granted)
    }
  }

  private fun recordFirstFrame() {
    val durationMs = (SystemClock.elapsedRealtime() - activityStartElapsedMs).coerceAtLeast(0)
    firstFrameAnalytics.recordOnce(durationMs)
  }
}

internal data class AudioPermissionState(
  val granted: Boolean = false,
  val resultVersion: Int = 0,
)

@SuppressLint("InlinedApi") // The value is an inlined String and selection is covered by sdkInt.
internal fun requiredAudioPermission(sdkInt: Int): String =
  if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
    Manifest.permission.READ_MEDIA_AUDIO
  } else {
    Manifest.permission.READ_EXTERNAL_STORAGE
  }
