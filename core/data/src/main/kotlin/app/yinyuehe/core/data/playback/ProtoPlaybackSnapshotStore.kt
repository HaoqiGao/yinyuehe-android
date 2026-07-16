package app.yinyuehe.core.data.playback

import androidx.datastore.core.DataStore
import app.yinyuehe.core.common.playback.PlaybackSnapshot
import app.yinyuehe.core.common.playback.PlaybackSnapshotReadResult
import app.yinyuehe.core.common.playback.PlaybackSnapshotStore
import app.yinyuehe.core.data.playback.proto.PlaybackSnapshotProto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
internal class ProtoPlaybackSnapshotStore @Inject constructor(
  private val dataStore: DataStore<PlaybackSnapshotProto>,
) : PlaybackSnapshotStore {
  override suspend fun read(): PlaybackSnapshotReadResult =
    dataStore.data.first().toReadResult()

  override suspend fun write(snapshot: PlaybackSnapshot) {
    dataStore.updateData { snapshot.toProto() }
  }
}
