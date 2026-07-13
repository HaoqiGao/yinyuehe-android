package app.yinyuehe.core.common.model

enum class LibrarySource { DEMO, LOCAL }

data class LibraryContent(
  val source: LibrarySource,
  val tracks: List<Track>,
) {
  init {
    require(tracks.all { it.isDemo == (source == LibrarySource.DEMO) }) {
      "Demo and local tracks must not be mixed"
    }
  }
}
