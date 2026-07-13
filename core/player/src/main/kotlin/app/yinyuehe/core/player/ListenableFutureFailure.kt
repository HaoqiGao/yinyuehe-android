package app.yinyuehe.core.player

import com.google.common.util.concurrent.ListenableFuture

internal fun ListenableFuture<*>.hasFailed(): Boolean {
  if (!isDone) return false
  if (isCancelled) return true
  return runCatching { get() }.isFailure
}
