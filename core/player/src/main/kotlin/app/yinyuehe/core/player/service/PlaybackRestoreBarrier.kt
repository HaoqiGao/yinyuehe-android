package app.yinyuehe.core.player.service

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

enum class PlaybackRestoreBarrierPhase { BEFORE_READ, BEFORE_APPLY }

fun interface PlaybackRestoreBarrier {
  suspend fun awaitPhase(phase: PlaybackRestoreBarrierPhase)
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PlaybackRestoreBarrierBindings {
  @Multibinds
  abstract fun playbackRestoreBarriers(): Set<PlaybackRestoreBarrier>
}
