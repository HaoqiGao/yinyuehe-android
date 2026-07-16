package app.yinyuehe

import android.content.Context
import app.yinyuehe.core.data.TrackRepository
import app.yinyuehe.core.data.scan.LibraryScanner
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface M3ADeviceEntryPoint {
  fun libraryScanner(): LibraryScanner

  fun trackRepository(): TrackRepository

  companion object {
    fun from(context: Context): M3ADeviceEntryPoint =
      EntryPointAccessors.fromApplication(
        context.applicationContext,
        M3ADeviceEntryPoint::class.java,
      )
  }
}
