package app.yinyuehe

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M3ASnapshotSafetyDeviceTest {
  @Test
  fun setupKnownV1MorningSnapshot() = runBlocking {
    M3ADeviceProtocol.requireHostDriven()
    val context = M3ADeviceProtocol.context
    val morning =
      M3ADeviceEntryPoint.from(context)
        .trackRepository()
        .demoTracks()
        .single { it.id.value == MORNING_ID }
    val harness = M3AMediaControllerHarness(context)
    val controller = harness.connect()

    try {
      harness.replaceQueue(controller, listOf(morning), startIndex = 0, play = false)
      val snapshot =
        harness.awaitSnapshot(controller) { state ->
          state.mediaIds == listOf(MORNING_ID) && state.currentIndex == 0
        }
      delay(600)
      M3ADeviceProtocol.writeResult(
        "snapshot-known-v1",
        mapOf(
          "mediaIds" to snapshot.mediaIds.joinToString(","),
          "currentIndex" to snapshot.currentIndex,
        ),
      )
    } finally {
      harness.close()
    }
  }

  @Test
  fun beforeApply_fullReplacementSupersedesBlockedRestore() = runBlocking {
    M3ADeviceProtocol.requireHostDriven()
    val context = M3ADeviceProtocol.context
    val night =
      M3ADeviceEntryPoint.from(context)
        .trackRepository()
        .demoTracks()
        .single { it.id.value == NIGHT_ID }
    val harness = M3AMediaControllerHarness(context)
    val controller = harness.connect()

    try {
      harness.awaitSnapshot(controller) { it.mediaIds.isEmpty() }
      harness.replaceQueue(controller, listOf(night), startIndex = 0, play = false)
      val replacement =
        harness.awaitSnapshot(controller) { state ->
          state.mediaIds == listOf(NIGHT_ID) && state.currentIndex == 0
        }
      delay(600)
      M3ADeviceProtocol.writeResult(
        "snapshot-before-apply-replacement",
        mapOf(
          "mediaIds" to replacement.mediaIds.joinToString(","),
          "currentIndex" to replacement.currentIndex,
        ),
      )
    } finally {
      harness.close()
    }
  }

  @Test
  fun beforeApply_stalePlanCannotOverwriteReplacement() = runBlocking {
    M3ADeviceProtocol.requireHostDriven()
    val harness = M3AMediaControllerHarness(M3ADeviceProtocol.context)
    val controller = harness.connect()

    try {
      val stable =
        harness.awaitSnapshot(controller) { state ->
          state.mediaIds == listOf(NIGHT_ID) && state.currentIndex == 0
        }
      delay(800)
      val afterStaleWindow = harness.snapshot(controller)
      assertEquals(stable.mediaIds, afterStaleWindow.mediaIds)
      assertEquals(0, afterStaleWindow.currentIndex)
      M3ADeviceProtocol.writeResult(
        "snapshot-before-apply-verified",
        mapOf(
          "mediaIds" to afterStaleWindow.mediaIds.joinToString(","),
          "currentIndex" to afterStaleWindow.currentIndex,
        ),
      )
    } finally {
      harness.close()
    }
  }

  @Test
  fun corruptBytes_recoverEmptyThenPersistNewQueue() = runBlocking {
    M3ADeviceProtocol.requireHostDriven()
    val context = M3ADeviceProtocol.context
    val night =
      M3ADeviceEntryPoint.from(context)
        .trackRepository()
        .demoTracks()
        .single { it.id.value == NIGHT_ID }
    val harness = M3AMediaControllerHarness(context)
    val controller = harness.connect()

    try {
      harness.awaitSnapshot(controller) { it.mediaIds.isEmpty() }
      delay(500)
      assertEquals(emptyList<String>(), harness.snapshot(controller).mediaIds)
      harness.replaceQueue(controller, listOf(night), startIndex = 0, play = false)
      val persisted =
        harness.awaitSnapshot(controller) { state ->
          state.mediaIds == listOf(NIGHT_ID) && state.currentIndex == 0
        }
      delay(600)
      M3ADeviceProtocol.writeResult(
        "snapshot-corrupt-recovered",
        mapOf(
          "recoveredEmpty" to true,
          "mediaIds" to persisted.mediaIds.joinToString(","),
          "currentIndex" to persisted.currentIndex,
        ),
      )
    } finally {
      harness.close()
    }
  }

  @Test
  fun corruptReplacement_survivesColdRestart() = runBlocking {
    M3ADeviceProtocol.requireHostDriven()
    val harness = M3AMediaControllerHarness(M3ADeviceProtocol.context)
    val controller = harness.connect()

    try {
      val restored =
        harness.awaitSnapshot(controller) { state ->
          state.mediaIds == listOf(NIGHT_ID) && state.currentIndex == 0
        }
      assertFalse(restored.playWhenReady)
      assertFalse(restored.isPlaying)
      M3ADeviceProtocol.writeResult(
        "snapshot-corrupt-restart",
        mapOf(
          "mediaIds" to restored.mediaIds.joinToString(","),
          "currentIndex" to restored.currentIndex,
          "playWhenReady" to restored.playWhenReady,
          "isPlaying" to restored.isPlaying,
        ),
      )
    } finally {
      harness.close()
    }
  }

  @Test
  fun schema99_coldStartRemainsEmpty() = runBlocking {
    M3ADeviceProtocol.requireHostDriven()
    val harness = M3AMediaControllerHarness(M3ADeviceProtocol.context)
    val controller = harness.connect()

    try {
      val empty = harness.awaitSnapshot(controller) { it.mediaIds.isEmpty() }
      delay(500)
      assertEquals(emptyList<String>(), harness.snapshot(controller).mediaIds)
      M3ADeviceProtocol.writeResult(
        "snapshot-schema99",
        mapOf("mediaIds" to "", "currentIndex" to empty.currentIndex),
      )
    } finally {
      harness.close()
    }
  }

  private companion object {
    const val MORNING_ID = "demo:morning-pulse"
    const val NIGHT_ID = "demo:night-drive"
  }
}
