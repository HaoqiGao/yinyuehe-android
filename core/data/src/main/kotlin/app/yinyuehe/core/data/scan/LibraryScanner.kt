package app.yinyuehe.core.data.scan

data class ScanResult(
  val discovered: Int,
  val unavailable: Int,
  val volumeCount: Int,
)

interface LibraryScanner {
  suspend fun scan(): Result<ScanResult>
}
