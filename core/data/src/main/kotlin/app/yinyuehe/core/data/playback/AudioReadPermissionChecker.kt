package app.yinyuehe.core.data.playback

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

internal fun interface AudioReadPermissionChecker {
  fun hasAudioReadPermission(): Boolean
}

@Singleton
internal class AndroidAudioReadPermissionChecker @Inject constructor(
  @ApplicationContext private val context: Context,
) : AudioReadPermissionChecker {
  override fun hasAudioReadPermission(): Boolean =
    context.checkSelfPermission(requiredAudioReadPermission(Build.VERSION.SDK_INT)) ==
      PackageManager.PERMISSION_GRANTED
}

@SuppressLint("InlinedApi")
internal fun requiredAudioReadPermission(sdkInt: Int): String =
  if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
    Manifest.permission.READ_MEDIA_AUDIO
  } else {
    Manifest.permission.READ_EXTERNAL_STORAGE
  }
