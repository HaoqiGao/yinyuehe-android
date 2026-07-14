package app.yinyuehe.core.common.model

@JvmInline
value class TrackId(val value: String) {
  init {
    require(value.isNotBlank()) { "TrackId must not be blank" }
  }
}

data class Track(
  val id: TrackId,
  val title: String?,
  val artist: String?,
  val album: String?,
  val durationMs: Long?,
  val artworkUri: String?,
  val sourceUri: String,
  val isDemo: Boolean,
  val displayName: String? = null,
  val albumId: Long? = null,
  val mimeType: String? = null,
  val sizeBytes: Long? = null,
  val folderKey: String? = null,
  val folderDisplayName: String? = null,
  val dateAddedSeconds: Long? = null,
  val dateModifiedSeconds: Long? = null,
) {
  init {
    require(title == null || title.isNotBlank()) { "Track title must be null or non-blank" }
    require(sourceUri.isNotBlank()) { "Track sourceUri must not be blank" }
    require(durationMs == null || durationMs >= 0) { "Track duration must not be negative" }
    require(sizeBytes == null || sizeBytes >= 0) { "Track size must not be negative" }
    require(dateAddedSeconds == null || dateAddedSeconds >= 0) {
      "Track dateAddedSeconds must not be negative"
    }
    require(dateModifiedSeconds == null || dateModifiedSeconds >= 0) {
      "Track dateModifiedSeconds must not be negative"
    }
  }
}
