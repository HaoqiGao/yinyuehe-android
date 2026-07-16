package app.yinyuehe.core.player.service

internal interface RestorablePlayer {
  val isQueueEmpty: Boolean

  fun apply(plan: PlaybackRestorePlan)

  fun queueFingerprint(): PlayerQueueFingerprint
}
