package app.yinyuehe

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import app.yinyuehe.core.common.model.Track
import app.yinyuehe.core.data.scan.LibraryScanner
import java.io.OutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

class M3ADeviceAudioFixture(private val context: Context) {
  lateinit var createdContentUri: Uri
    private set

  suspend fun createAndScan(scanner: LibraryScanner): Track {
    deleteByFixedDisplayName()
    val uri = insertPendingWav()
    createdContentUri = uri
    try {
      context.contentResolver.openOutputStream(uri, "w")!!.use { output ->
        output.writeWavHeader()
        val zeroes = ByteArray(8 * 1024)
        var remaining = PCM_BYTES
        while (remaining > 0) {
          val count = minOf(remaining, zeroes.size)
          output.write(zeroes, 0, count)
          remaining -= count
        }
      }
      val published =
        context.contentResolver.update(
          uri,
          ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
          null,
          null,
        )
      check(published == 1) { "Unable to publish $DISPLAY_NAME" }

      val repository = M3ADeviceEntryPoint.from(context).trackRepository()
      return withTimeout(SCAN_TIMEOUT_MS) {
        while (true) {
          scanner.scan().getOrThrow()
          val insertedTrack =
            repository
            .observeAvailableLocalTracks()
            .first()
            .firstOrNull { track ->
              track.sourceUri == uri.toString()
            }
          if (insertedTrack != null) {
            check(insertedTrack.displayName == DISPLAY_NAME) {
              "MediaStore renamed $DISPLAY_NAME to ${insertedTrack.displayName}"
            }
            if ((insertedTrack.durationMs ?: 0L) >= MIN_DURATION_MS) {
              return@withTimeout insertedTrack
            }
          }
          delay(SCAN_POLL_MS)
        }
        error("unreachable")
      }
    } catch (failure: Throwable) {
      context.contentResolver.delete(uri, null, null)
      throw failure
    }
  }

  fun deleteByFixedDisplayName(): Int =
    context.contentResolver.delete(
      MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
      "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND " +
        "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
      arrayOf(DISPLAY_NAME, context.packageName),
    )

  fun delete(track: Track): Int {
    check(track.sourceUri == createdContentUri.toString()) {
      "Refusing to delete a MediaStore row not created by this fixture"
    }
    return context.contentResolver.delete(createdContentUri, null, null)
  }

  private fun insertPendingWav(): Uri =
    checkNotNull(
      context.contentResolver.insert(
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        ContentValues().apply {
          put(MediaStore.Audio.Media.DISPLAY_NAME, DISPLAY_NAME)
          put(MediaStore.Audio.Media.TITLE, "YinYueHe M3A 35 second fixture")
          put(MediaStore.Audio.Media.ARTIST, "YinYueHe Test")
          put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
          put(MediaStore.Audio.Media.IS_MUSIC, 1)
          put(MediaStore.Audio.Media.IS_PENDING, 1)
          put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/YinYueHeM3A")
        },
      )
    ) { "Unable to insert $DISPLAY_NAME into MediaStore" }

  private fun OutputStream.writeWavHeader() {
    write("RIFF".encodeToByteArray())
    writeLeInt(36 + PCM_BYTES)
    write("WAVEfmt ".encodeToByteArray())
    writeLeInt(16)
    writeLeShort(1)
    writeLeShort(1)
    writeLeInt(SAMPLE_RATE)
    writeLeInt(SAMPLE_RATE * 2)
    writeLeShort(2)
    writeLeShort(16)
    write("data".encodeToByteArray())
    writeLeInt(PCM_BYTES)
  }

  private fun OutputStream.writeLeInt(value: Int) {
    write(
      byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
      )
    )
  }

  private fun OutputStream.writeLeShort(value: Int) {
    write(byteArrayOf(value.toByte(), (value ushr 8).toByte()))
  }

  companion object {
    const val DISPLAY_NAME = "yinyuehe_m3a_35s.wav"
    private const val SAMPLE_RATE = 8_000
    private const val DURATION_SECONDS = 35
    private const val PCM_BYTES = SAMPLE_RATE * DURATION_SECONDS * 2
    private const val MIN_DURATION_MS = 30_000L
    private const val SCAN_TIMEOUT_MS = 30_000L
    private const val SCAN_POLL_MS = 250L
  }
}
