package app.yinyuehe.core.common.analytics

interface PlaybackEventRecorder {
  suspend fun record(event: PlaybackEvent)
}
