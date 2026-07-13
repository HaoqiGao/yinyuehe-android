package app.yinyuehe.core.player

import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenableFutureFailureTest {
  @Test
  fun immediateSuccess_doesNotNeedRebuild() {
    assertFalse(Futures.immediateFuture("controller").hasFailed())
  }

  @Test
  fun immediateFailure_needsRebuild() {
    assertTrue(Futures.immediateFailedFuture<String>(IllegalStateException("failed")).hasFailed())
  }

  @Test
  fun cancelledFuture_needsRebuild() {
    val future = SettableFuture.create<String>()
    future.cancel(false)

    assertTrue(future.hasFailed())
  }

  @Test
  fun pendingFuture_doesNotNeedRebuild() {
    assertFalse(SettableFuture.create<String>().hasFailed())
  }
}
