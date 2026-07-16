package app.yinyuehe.core.data.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.yinyuehe.core.data.playback.proto.PlaybackSnapshotProto
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object PlaybackPersistenceModule {
  @Provides
  @Singleton
  fun providePlaybackSnapshotDataStore(
    @ApplicationContext context: Context,
  ): DataStore<PlaybackSnapshotProto> =
    createPlaybackSnapshotDataStore(
      produceFile = { context.dataStoreFile(PLAYBACK_SNAPSHOT_FILE_NAME) },
      scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )
}

internal fun createPlaybackSnapshotDataStore(
  produceFile: () -> File,
  scope: CoroutineScope,
): DataStore<PlaybackSnapshotProto> =
  DataStoreFactory.create(
    serializer = PlaybackSnapshotSerializer,
    corruptionHandler =
      ReplaceFileCorruptionHandler {
        PlaybackSnapshotSerializer.defaultValue
      },
    scope = scope,
    produceFile = produceFile,
  )

internal const val PLAYBACK_SNAPSHOT_FILE_NAME: String = "playback_snapshot.pb"
