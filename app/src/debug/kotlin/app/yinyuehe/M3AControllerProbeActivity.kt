package app.yinyuehe

import android.os.Bundle
import android.os.Process
import android.util.AtomicFile
import android.view.View
import androidx.activity.ComponentActivity
import app.yinyuehe.core.player.PlaybackConnection
import app.yinyuehe.core.player.PlaybackController
import app.yinyuehe.core.player.PlaybackState
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@AndroidEntryPoint
class M3AControllerProbeActivity : ComponentActivity() {
  @Inject lateinit var playbackController: PlaybackController

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var previousConnection = PlaybackConnection.CONNECTING
  private var connectedGeneration = 0
  private var disconnectEdges = 0
  private var connectedEmissionCount = 0
  private var preKillReadyWritten = false
  private var postReconnectReadyWritten = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(View(this))
    scope.launch { playbackController.state.collect(::recordState) }
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  private fun recordState(state: PlaybackState) {
    val connection = state.connection
    if (connection == PlaybackConnection.CONNECTED) connectedEmissionCount += 1
    if (
      previousConnection != PlaybackConnection.CONNECTED &&
        connection == PlaybackConnection.CONNECTED
    ) {
      connectedGeneration += 1
      check(connectedGeneration <= 2) { "Unexpected third connection generation" }
      writeResult(
        if (connectedGeneration == 1) GENERATION_ONE_RESULT else GENERATION_TWO_RESULT,
        state.markerValues(),
      )
    } else if (
      previousConnection == PlaybackConnection.CONNECTED &&
        connection != PlaybackConnection.CONNECTED
    ) {
      disconnectEdges += 1
      if (disconnectEdges == 1) writeResult(DISCONNECTED_RESULT, state.markerValues())
    }
    previousConnection = connection

    if (
      connectedGeneration == 1 &&
        !preKillReadyWritten &&
        connection == PlaybackConnection.CONNECTED &&
        state.queueTrackIds.map { it.value } == listOf(MORNING_ID) &&
        state.currentIndex == 0
    ) {
      preKillReadyWritten = true
      writeResult(PRE_KILL_READY_RESULT, state.markerValues())
    }
    if (
      connectedGeneration == 2 &&
        !postReconnectReadyWritten &&
        connection == PlaybackConnection.CONNECTED &&
        state.queueTrackIds.map { it.value } == listOf(MORNING_ID) &&
        state.currentIndex == 0
    ) {
      postReconnectReadyWritten = true
      writeResult(POST_RECONNECT_READY_RESULT, state.markerValues())
    }
    writeResult(LIVE_RESULT, state.markerValues())
  }

  private fun PlaybackState.markerValues(): Map<String, Any> =
    linkedMapOf(
      "connectedGeneration" to connectedGeneration,
      "disconnectEdges" to disconnectEdges,
      "connectedEmissionCount" to connectedEmissionCount,
      "controllerIdentity" to System.identityHashCode(playbackController),
      "controllerClass" to playbackController.javaClass.name,
      "probePid" to Process.myPid(),
      "processName" to currentProcessName(),
      "connection" to connection.name,
      "mediaIds" to queueTrackIds.joinToString(",") { it.value },
      "currentIndex" to currentIndex,
    )

  private fun writeResult(name: String, values: Map<String, Any>) {
    val directory = File(filesDir, "m3a-device").apply(File::mkdirs)
    val atomicFile = AtomicFile(File(directory, name))
    val output = atomicFile.startWrite()
    try {
      val content = buildString {
        values.forEach { (key, value) -> appendLine("$key=$value") }
      }
      output.write(content.encodeToByteArray())
      atomicFile.finishWrite(output)
    } catch (failure: Throwable) {
      atomicFile.failWrite(output)
      throw failure
    }
  }

  private fun currentProcessName(): String =
    File("/proc/self/cmdline").readText().trimEnd('\u0000')

  companion object {
    const val GENERATION_ONE_RESULT = "reconnect-generation-1.result"
    const val PRE_KILL_READY_RESULT = "reconnect-pre-kill-ready.result"
    const val DISCONNECTED_RESULT = "reconnect-disconnected.result"
    const val GENERATION_TWO_RESULT = "reconnect-generation-2.result"
    const val POST_RECONNECT_READY_RESULT = "reconnect-post-reconnect-ready.result"
    const val LIVE_RESULT = "reconnect-live.result"

    private const val MORNING_ID = "demo:morning-pulse"
  }
}
