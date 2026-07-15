package app.yinyuehe.core.data.playback

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import app.yinyuehe.core.data.playback.proto.PlaybackSnapshotProto
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

internal object PlaybackSnapshotSerializer : Serializer<PlaybackSnapshotProto> {
  override val defaultValue: PlaybackSnapshotProto = PlaybackSnapshotProto.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): PlaybackSnapshotProto {
    val bytes = input.readBytes()
    return try {
      PlaybackSnapshotProto.parseFrom(bytes)
    } catch (failure: InvalidProtocolBufferException) {
      throw CorruptionException("Unable to read playback snapshot proto", failure)
    }
  }

  override suspend fun writeTo(t: PlaybackSnapshotProto, output: OutputStream) {
    t.writeTo(output)
  }
}
