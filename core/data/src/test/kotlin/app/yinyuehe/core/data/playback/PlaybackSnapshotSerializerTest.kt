package app.yinyuehe.core.data.playback

import androidx.datastore.core.CorruptionException
import app.yinyuehe.core.data.playback.proto.PlaybackSnapshotProto
import com.google.protobuf.InvalidProtocolBufferException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSnapshotSerializerTest {
  @Test
  fun invalidProto_isReportedAsCorruption() = runTest {
    val failure =
      runCatching {
        PlaybackSnapshotSerializer.readFrom(
          ByteArrayInputStream(byteArrayOf(0x0A, 0x02, 0x01))
        )
      }.exceptionOrNull()

    assertTrue(failure is CorruptionException)
    assertTrue(failure?.cause is InvalidProtocolBufferException)
  }

  @Test
  fun ordinaryIoFailure_isNotReclassifiedAsCorruption() = runTest {
    val input =
      object : ByteArrayInputStream(byteArrayOf()) {
        override fun read(): Int = throw IOException("read failed")

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
          throw IOException("read failed")
      }

    val failure = runCatching { PlaybackSnapshotSerializer.readFrom(input) }.exceptionOrNull()

    assertTrue(failure is IOException)
    assertFalse(failure is CorruptionException)
  }

  @Test
  fun writeTo_emitsExactlyTheProtoBytes() = runTest {
    val proto = PlaybackSnapshotProto.newBuilder().setSchemaVersion(1).setCurrentIndex(-1).build()
    val output = ByteArrayOutputStream()

    PlaybackSnapshotSerializer.writeTo(proto, output)

    assertArrayEquals(proto.toByteArray(), output.toByteArray())
    assertEquals(PlaybackSnapshotProto.getDefaultInstance(), PlaybackSnapshotSerializer.defaultValue)
  }
}
