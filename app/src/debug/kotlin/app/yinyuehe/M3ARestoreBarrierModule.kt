package app.yinyuehe

import android.content.Context
import app.yinyuehe.core.player.service.PlaybackRestoreBarrier
import app.yinyuehe.core.player.service.PlaybackRestoreBarrierPhase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@Module
@InstallIn(SingletonComponent::class)
object M3ARestoreBarrierModule {
  @Provides
  @IntoSet
  fun provideFileBarrier(
    @ApplicationContext context: Context,
  ): PlaybackRestoreBarrier =
    PlaybackRestoreBarrier { phase ->
      withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "m3a-device").apply(File::mkdirs)
        val key =
          when (phase) {
            PlaybackRestoreBarrierPhase.BEFORE_READ -> "before-read"
            PlaybackRestoreBarrierPhase.BEFORE_APPLY -> "before-apply"
        }
        if (!File(directory, "hold-$key").exists()) return@withContext
        writeAtomicMarker(directory, "$key-blocked")
        try {
          withTimeout(BARRIER_TIMEOUT_MS) {
            while (!File(directory, "release-$key").exists()) delay(BARRIER_POLL_MS)
          }
        } catch (timeout: TimeoutCancellationException) {
          throw timeout
        } catch (cancellation: CancellationException) {
          withContext(NonCancellable) {
            writeAtomicMarker(directory, "$key-cancelled")
          }
          throw cancellation
        }
      }
    }

  private fun writeAtomicMarker(directory: File, name: String) {
    val target = File(directory, name)
    val temporary = File(directory, ".$name-${android.os.Process.myPid()}.tmp")
    FileOutputStream(temporary).use { output ->
      output.write("blocked".encodeToByteArray())
      output.fd.sync()
    }
    check(temporary.renameTo(target)) { "Unable to publish restore barrier marker $name" }
  }

  private const val BARRIER_TIMEOUT_MS = 10_000L
  private const val BARRIER_POLL_MS = 25L
}
