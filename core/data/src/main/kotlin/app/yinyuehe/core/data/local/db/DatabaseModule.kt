package app.yinyuehe.core.data.local.db

import android.content.Context
import androidx.room.Room
import app.yinyuehe.core.data.local.db.dao.FavoriteDao
import app.yinyuehe.core.data.local.db.dao.RecentPlayDao
import app.yinyuehe.core.data.local.db.dao.ScanCheckpointDao
import app.yinyuehe.core.data.local.db.dao.TrackDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
  @Provides
  @Singleton
  fun provideDatabase(@ApplicationContext context: Context): YinYueHeDatabase =
    Room.databaseBuilder(context, YinYueHeDatabase::class.java, "yinyuehe.db").build()

  @Provides
  fun provideTrackDao(database: YinYueHeDatabase): TrackDao = database.trackDao()

  @Provides
  fun provideFavoriteDao(database: YinYueHeDatabase): FavoriteDao = database.favoriteDao()

  @Provides
  fun provideRecentPlayDao(database: YinYueHeDatabase): RecentPlayDao = database.recentPlayDao()

  @Provides
  fun provideScanCheckpointDao(database: YinYueHeDatabase): ScanCheckpointDao =
    database.scanCheckpointDao()
}
