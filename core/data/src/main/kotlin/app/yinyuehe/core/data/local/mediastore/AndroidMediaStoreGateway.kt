package app.yinyuehe.core.data.local.mediastore

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidMediaStoreGateway @Inject constructor(
  @ApplicationContext private val context: Context,
) : MediaStoreGateway {
  override suspend fun externalVolumeNames(): List<String> =
    withContext(Dispatchers.IO) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.getExternalVolumeNames(context).sorted()
      } else {
        listOf(LEGACY_EXTERNAL_VOLUME)
      }
    }

  override suspend fun readVolume(volumeName: String): List<MediaStoreAudio> =
    withContext(Dispatchers.IO) {
      val collection =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          MediaStore.Audio.Media.getContentUri(volumeName)
        } else {
          MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
      val projection =
        buildList {
          add(MediaStore.Audio.Media._ID)
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(MediaStore.Audio.Media.DISPLAY_NAME)
          }
          add(MediaStore.Audio.Media.TITLE)
          add(MediaStore.Audio.Media.ARTIST)
          add(MediaStore.Audio.Media.ALBUM)
          add(MediaStore.Audio.Media.ALBUM_ID)
          add(MediaStore.Audio.Media.DURATION)
          add(MediaStore.Audio.Media.MIME_TYPE)
          add(MediaStore.Audio.Media.SIZE)
          add(MediaStore.Audio.Media.DATE_ADDED)
          add(MediaStore.Audio.Media.DATE_MODIFIED)
        }.toTypedArray()
      val cursor =
        context.contentResolver.query(
          collection,
          projection,
          "${MediaStore.Audio.Media.IS_MUSIC} != 0",
          null,
          "${MediaStore.Audio.Media.DATE_ADDED} DESC",
        ) ?: error("MediaStore returned a null Cursor")
      cursor.use {
        val rows = ArrayList<MediaStoreAudio>(it.count.coerceAtLeast(0))
        while (it.moveToNext()) {
          val id = it.requiredLong(MediaStore.Audio.Media._ID)
          val albumId = it.optionalLong(MediaStore.Audio.Media.ALBUM_ID)
          rows +=
            MediaStoreAudio(
              volumeName = volumeName,
              mediaStoreId = id,
              contentUri = ContentUris.withAppendedId(collection, id).toString(),
              displayName = it.optionalText(MediaStore.Audio.Media.DISPLAY_NAME),
              title = it.optionalText(MediaStore.Audio.Media.TITLE),
              artist = it.optionalText(MediaStore.Audio.Media.ARTIST),
              album = it.optionalText(MediaStore.Audio.Media.ALBUM),
              albumId = albumId,
              artworkUri = null,
              durationMs = it.optionalLong(MediaStore.Audio.Media.DURATION) ?: 0,
              mimeType = it.optionalText(MediaStore.Audio.Media.MIME_TYPE),
              sizeBytes = it.optionalLong(MediaStore.Audio.Media.SIZE) ?: 0,
              dateAddedSeconds = it.optionalLong(MediaStore.Audio.Media.DATE_ADDED) ?: 0,
              dateModifiedSeconds = it.optionalLong(MediaStore.Audio.Media.DATE_MODIFIED) ?: 0,
            )
        }
        rows
      }
    }
}

private fun Cursor.requiredLong(columnName: String): Long = getLong(getColumnIndexOrThrow(columnName))

private fun Cursor.optionalLong(columnName: String): Long? {
  val index = getColumnIndex(columnName)
  return if (index < 0 || isNull(index)) null else getLong(index)
}

private fun Cursor.optionalText(columnName: String): String? {
  val index = getColumnIndex(columnName)
  return if (index < 0 || isNull(index)) null else cleanMediaStoreText(getString(index))
}

private const val LEGACY_EXTERNAL_VOLUME = "external"
