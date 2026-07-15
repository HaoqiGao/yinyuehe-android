package app.yinyuehe.core.data.local.mediastore

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import app.yinyuehe.core.data.local.db.toDomain
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
class AndroidMediaStoreGatewayTest {
  @Test
  @Config(sdk = [28])
  fun api28Query_readsDisplayNameForUnknownTitleFallback() = runTest {
    val provider = LegacyAudioProvider()
    ShadowContentResolver.registerProviderInternal("media", provider)
    val context = ApplicationProvider.getApplicationContext<Context>()

    val row = AndroidMediaStoreGateway(context).readVolume("external").single()
    val track = row.toTrackEntity("scan").toDomain()

    assertTrue(provider.lastProjection.contains(MediaStore.Audio.Media.DISPLAY_NAME))
    assertEquals("legacy-file.mp3", row.displayName)
    assertNull(row.title)
    assertNull(track.title)
    assertEquals("legacy-file.mp3", track.displayName)
  }
}

private class LegacyAudioProvider : ContentProvider() {
  var lastProjection: List<String> = emptyList()
    private set

  override fun onCreate(): Boolean = true

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor {
    val columns = requireNotNull(projection)
    lastProjection = columns.toList()
    return MatrixCursor(columns).apply {
      addRow(
        columns.map { column ->
          when (column) {
            MediaStore.Audio.Media._ID -> 7L
            MediaStore.Audio.Media.DISPLAY_NAME -> "legacy-file.mp3"
            MediaStore.Audio.Media.TITLE -> MediaStore.UNKNOWN_STRING
            MediaStore.Audio.Media.ARTIST -> "Legacy artist"
            MediaStore.Audio.Media.ALBUM -> "Legacy album"
            MediaStore.Audio.Media.ALBUM_ID -> 11L
            MediaStore.Audio.Media.DURATION -> 1_000L
            MediaStore.Audio.Media.MIME_TYPE -> "audio/mpeg"
            MediaStore.Audio.Media.SIZE -> 2_000L
            MediaStore.Audio.Media.DATE_ADDED -> 3_000L
            MediaStore.Audio.Media.DATE_MODIFIED -> 4_000L
            else -> null
          }
        }
      )
    }
  }

  override fun getType(uri: Uri): String? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?,
  ): Int = 0
}
