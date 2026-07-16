package app.yinyuehe

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M3AQueueRecoveryDeviceTest {
  @Test
  fun phaseOne_persistsDuplicateQueueState() = runBlocking {
    M3ADeviceProtocol.requireHostDriven()
    val context = M3ADeviceProtocol.context
    val demos = M3ADeviceEntryPoint.from(context).trackRepository().demoTracks()
    val morning = demos.single { it.id.value == MORNING_ID }
    val night = demos.single { it.id.value == NIGHT_ID }
    val expectedIds = listOf(MORNING_ID, MORNING_ID, NIGHT_ID)
    val harness = M3AMediaControllerHarness(context)
    val controller = harness.connect()

    try {
      harness.replaceQueue(
        controller = controller,
        tracks = listOf(morning, morning, night),
        startIndex = 1,
        shuffle = true,
        repeatMode = Player.REPEAT_MODE_ALL,
        play = false,
      )
      val persisted =
        harness.awaitSnapshot(controller) { snapshot ->
          snapshot.mediaIds == expectedIds &&
            snapshot.currentIndex == 1 &&
            snapshot.shuffleEnabled &&
            snapshot.repeatMode == Player.REPEAT_MODE_ALL &&
            !snapshot.playWhenReady &&
            !snapshot.isPlaying
        }
      delay(600)
      M3ADeviceProtocol.writeResult(
        "queue-phase-one",
        mapOf(
          "mediaIds" to persisted.mediaIds.joinToString(","),
          "currentIndex" to persisted.currentIndex,
          "repeatMode" to persisted.repeatMode,
          "shuffleEnabled" to persisted.shuffleEnabled,
          "playWhenReady" to persisted.playWhenReady,
          "isPlaying" to persisted.isPlaying,
        ),
      )
    } finally {
      harness.close()
    }
  }

  @Test
  fun phaseTwo_restoresExactDuplicateOccurrencesPaused() = runBlocking {
    M3ADeviceProtocol.requireHostDriven()
    val expectedIds = listOf(MORNING_ID, MORNING_ID, NIGHT_ID)
    val harness = M3AMediaControllerHarness(M3ADeviceProtocol.context)
    val controller = harness.connect()

    try {
      val restored =
        harness.awaitSnapshot(controller) { snapshot ->
          snapshot.mediaIds == expectedIds && snapshot.currentIndex == 1
        }
      assertEquals(expectedIds, restored.mediaIds)
      assertEquals(2, restored.mediaIds.count { it == MORNING_ID })
      assertEquals(1, restored.currentIndex)
      assertEquals(Player.REPEAT_MODE_ALL, restored.repeatMode)
      assertTrue(restored.shuffleEnabled)
      assertFalse(restored.playWhenReady)
      assertFalse(restored.isPlaying)
      M3ADeviceProtocol.writeResult(
        "queue-phase-two",
        mapOf(
          "mediaIds" to restored.mediaIds.joinToString(","),
          "morningOccurrences" to restored.mediaIds.count { it == MORNING_ID },
          "currentIndex" to restored.currentIndex,
          "repeatMode" to restored.repeatMode,
          "shuffleEnabled" to restored.shuffleEnabled,
          "playWhenReady" to restored.playWhenReady,
          "isPlaying" to restored.isPlaying,
        ),
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
