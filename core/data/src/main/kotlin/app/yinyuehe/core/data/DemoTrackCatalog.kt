package app.yinyuehe.core.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.common.model.TrackId
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

internal class DemoTrackCatalog @Inject constructor(
  @ApplicationContext private val context: Context,
) {
  fun tracks(): List<Track> =
    listOf(
      track("demo:morning-pulse", "晨间节拍", 3_200L, R.raw.demo_morning_pulse),
      track("demo:city-walk", "城市漫步", 3_500L, R.raw.demo_city_walk),
      track("demo:soft-echo", "轻回声", 3_800L, R.raw.demo_soft_echo),
      track("demo:night-drive", "夜行模式", 4_000L, R.raw.demo_night_drive),
    )

  private fun track(id: String, title: String, durationMs: Long, resourceId: Int): Track =
    Track(
      id = TrackId(id),
      title = title,
      artist = "音悦盒 Demo Band",
      album = "Compose Sessions",
      durationMs = durationMs,
      artworkUri = null,
      sourceUri =
        Uri.Builder()
          .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
          .authority(context.packageName)
          .appendPath(resourceId.toString())
          .build()
          .toString(),
      isDemo = true,
    )
}
