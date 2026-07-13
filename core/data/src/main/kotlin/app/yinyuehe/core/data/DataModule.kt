package app.yinyuehe.core.data

import app.yinyuehe.core.data.repository.RoomTrackRepository
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
}
