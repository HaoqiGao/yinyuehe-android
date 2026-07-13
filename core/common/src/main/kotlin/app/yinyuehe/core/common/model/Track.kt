package app.yinyuehe.core.common.model

@JvmInline
value class TrackId(val value: String) {
  init {
    require(value.isNotBlank()) { "TrackId must not be blank" }
  }
}

data class Track(
  val id: TrackId,
  val title: String,
  val artist: String?,
  val album: String?,
  val durationMs: Long?,
  val artworkUri: String?,
  val sourceUri: String,
  val isDemo: Boolean,
) {
  init {
    require(title.isNotBlank()) { "Track title must not be blank" }
    require(sourceUri.isNotBlank()) { "Track sourceUri must not be blank" }
    require(durationMs == null || durationMs >= 0) { "Track duration must not be negative" }
  }
}
