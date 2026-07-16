package app.yinyuehe

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M3AReconnectDeviceTest {
  @Test
  fun setup_persistsMorningSnapshot() = runBlocking {
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
      val persisted =
        harness.awaitSnapshot(controller) { state ->
          state.mediaIds == listOf(MORNING_ID) && state.currentIndex == 0
        }
      delay(600)
      M3ADeviceProtocol.writeResult(
        "reconnect-setup",
        mapOf(
          "mediaIds" to persisted.mediaIds.joinToString(","),
          "currentIndex" to persisted.currentIndex,
        ),
      )
    } finally {
      harness.close()
    }
  }

  @Test
  fun probeReconnectedWithoutStaleState() = runBlocking {
    M3ADeviceProtocol.requireHostDriven()
    val generationOne = readProbeResult(M3AControllerProbeActivity.GENERATION_ONE_RESULT)
    val preKillReady = readProbeResult(M3AControllerProbeActivity.PRE_KILL_READY_RESULT)
    val disconnected = readProbeResult(M3AControllerProbeActivity.DISCONNECTED_RESULT)
    val generationTwo = readProbeResult(M3AControllerProbeActivity.GENERATION_TWO_RESULT)
    val postReconnectReady =
      readProbeResult(M3AControllerProbeActivity.POST_RECONNECT_READY_RESULT)

    assertEquals("1", generationOne.required("connectedGeneration"))
    assertEquals("1", preKillReady.required("connectedGeneration"))
    assertEquals("2", generationTwo.required("connectedGeneration"))
    assertEquals(
      generationOne.required("controllerIdentity"),
      generationTwo.required("controllerIdentity"),
    )
    assertEquals(generationOne.required("probePid"), generationTwo.required("probePid"))
    assertTrue(disconnected.required("disconnectEdges").toInt() >= 1)
    assertTrue(postReconnectReady.required("disconnectEdges").toInt() >= 1)
    assertEquals("2", postReconnectReady.required("connectedGeneration"))
    assertEquals(MORNING_ID, postReconnectReady.required("mediaIds"))
    assertEquals("0", postReconnectReady.required("currentIndex"))

    val night =
      M3ADeviceEntryPoint.from(M3ADeviceProtocol.context)
        .trackRepository()
        .demoTracks()
        .single { it.id.value == NIGHT_ID }
    val harness = M3AMediaControllerHarness(M3ADeviceProtocol.context)
    val controller = harness.connect()
    try {
      val restoredMorning =
        harness.awaitSnapshot(controller) { state ->
          state.mediaIds == listOf(MORNING_ID) && state.currentIndex == 0
        }
      delay(350)
      assertEquals(restoredMorning.mediaIds, harness.snapshot(controller).mediaIds)
      harness.replaceQueue(controller, listOf(night), startIndex = 0, play = false)
      harness.awaitSnapshot(controller) { state ->
        state.mediaIds == listOf(NIGHT_ID) && state.currentIndex == 0
      }
      delay(1_000)
      val stable = harness.snapshot(controller)
      assertEquals(listOf(NIGHT_ID), stable.mediaIds)
      assertEquals(0, stable.currentIndex)
      assertFalse(stable.queuePersistenceLimited)
      M3ADeviceProtocol.writeResult(
        "reconnect-final",
        mapOf(
          "generationOne" to generationOne.required("connectedGeneration"),
          "generationTwo" to generationTwo.required("connectedGeneration"),
          "disconnectEdges" to postReconnectReady.required("disconnectEdges"),
          "controllerIdentity" to postReconnectReady.required("controllerIdentity"),
          "probePid" to postReconnectReady.required("probePid"),
          "mediaIds" to stable.mediaIds.joinToString(","),
          "currentIndex" to stable.currentIndex,
        ),
      )
    } finally {
      harness.close()
    }
  }

  private fun readProbeResult(name: String): Map<String, String> {
    val file = File(File(M3ADeviceProtocol.context.filesDir, "m3a-device"), name)
    check(file.isFile) { "Missing probe result ${file.absolutePath}" }
    return file.readLines().associate { line ->
      val separator = line.indexOf('=')
      check(separator > 0) { "Malformed probe result line: $line" }
      line.substring(0, separator) to line.substring(separator + 1)
    }
  }

  private fun Map<String, String>.required(key: String): String =
    get(key) ?: error("Missing probe result key $key")

  private companion object {
    const val MORNING_ID = "demo:morning-pulse"
    const val NIGHT_ID = "demo:night-drive"
  }
}
