package app.yinyuehe.core.data

import app.yinyuehe.core.data.repository.RoomTrackRepository
import app.yinyuehe.core.data.local.mediastore.AndroidMediaStoreGateway
import app.yinyuehe.core.data.local.mediastore.MediaStoreGateway
import app.yinyuehe.core.data.scan.DefaultLibraryScanner
import app.yinyuehe.core.data.scan.LibraryScanner
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
  @Binds
  @Singleton
  abstract fun bindTrackRepository(repository: RoomTrackRepository): TrackRepository

  @Binds
  @Singleton
  abstract fun bindLibraryScanner(scanner: DefaultLibraryScanner): LibraryScanner

  @Binds
  @Singleton
  internal abstract fun bindMediaStoreGateway(
    gateway: AndroidMediaStoreGateway
  ): MediaStoreGateway
}
