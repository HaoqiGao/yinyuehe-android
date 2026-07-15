package app.yinyuehe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M3APermissionRecoveryDeviceTest {
  @Test
  fun preserveSetup_persistsGeneratedLocalOccurrence() = runBlocking {
    M3ADeviceProtocol.requireHostDriven()
    assertAudioPermission(granted = true)
    persistGeneratedLocalQueue("permission-preserve-setup")
  }

  @Test
  fun preserveLimited_incrementalEditsCannotPersist() = runBlocking {
    M3ADeviceProtocol.requireHostDriven()
    assertAudioPermission(granted = false)
    val context = M3ADeviceProtocol.context
    val demos = M3ADeviceEntryPoint.from(context).trackRepository().demoTracks()
    val morning = demos.single { it.id.value == MORNING_ID }
    val night = demos.single { it.id.value == NIGHT_ID }
    val harness = M3AMediaControllerHarness(context)
    val controller = harness.connect()

    try {
      harness.awaitSnapshot(controller) { snapshot ->
        snapshot.mediaIds.isEmpty() && snapshot.queuePersistenceLimited
      }
      harness.add(controller, morning)
      harness.awaitSnapshot(controller) { it.mediaIds == listOf(MORNING_ID) }
      harness.add(controller, night)
      harness.awaitSnapshot(controller) { it.mediaIds == listOf(MORNING_ID, NIGHT_ID) }
      harness.move(controller, fromIndex = 1, toIndex = 0)
      harness.awaitSnapshot(controller) { it.mediaIds == listOf(NIGHT_ID, MORNING_ID) }
      harness.remove(controller, index = 1)
      val edited =
        harness.awaitSnapshot(controller) { snapshot ->
          snapshot.mediaIds == listOf(NIGHT_ID) && snapshot.queuePersistenceLimited
        }
      delay(800)
      M3ADeviceProtocol.writeResult(
        "permission-preserve-limited",
        mapOf(
          "attemptedAdd" to true,
          "attemptedMove" to true,
          "attemptedRemove" to true,
          "finalMediaIds" to edited.mediaIds.joinToString(","),
          "queuePersistenceLimited" to edited.queuePersistenceLimited,
        ),
      )
    } finally {
      try {
        harness.close()
      } finally {
        grantAudioPermissionAndWait()
      }
    }
  }

  @Test
  fun preserveRestore_localOccurrenceReturnsAfterGrant() = runBlocking {
    M3ADeviceProtocol.requireHostDriven()
    assertAudioPermission(granted = true)
    val expectedId =
      M3ADeviceProtocol.result("permission-preserve-setup").getValue("localTrackId")
    val harness = M3AMediaControllerHarness(M3ADeviceProtocol.context)
    val controller = harness.connect()

    try {
      val restored =
        harness.awaitSnapshot(controller) { snapshot ->
          snapshot.mediaIds == listOf(expectedId) && snapshot.currentIndex == 0
        }
      assertFalse(restored.playWhenReady)
      assertFalse(restored.isPlaying)
      M3ADeviceProtocol.writeResult(
        "permission-preserve-restore",
        mapOf(
          "localTrackId" to restored.mediaIds.single(),
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
  fun replacementSetup_persistsGeneratedLocalOccurrence() = runBlocking {
    M3ADeviceProtocol.requireHostDriven()
    assertAudioPermission(granted = true)
    persistGeneratedLocalQueue("permission-replacement-setup")
  }

  @Test
  fun replacementLimited_fullSetMediaItemsCanReplaceProtectedQueue() = runBlocking {
    M3ADeviceProtocol.requireHostDriven()
    assertAudioPermission(granted = false)
    val context = M3ADeviceProtocol.context
    val night =
      M3ADeviceEntryPoint.from(context)
        .trackRepository()
        .demoTracks()
        .single { it.id.value == NIGHT_ID }
    val harness = M3AMediaControllerHarness(context)
    val controller = harness.connect()

    try {
      harness.awaitSnapshot(controller) { snapshot ->
        snapshot.mediaIds.isEmpty() && snapshot.queuePersistenceLimited
      }
      harness.replaceQueue(
        controller = controller,
        tracks = listOf(night),
        startIndex = 0,
        play = false,
      )
      val replacement =
        harness.awaitSnapshot(controller) { snapshot ->
          snapshot.mediaIds == listOf(NIGHT_ID) && !snapshot.queuePersistenceLimited
        }
      delay(800)
      M3ADeviceProtocol.writeResult(
        "permission-replacement-limited",
        mapOf(
          "fullReplacement" to true,
          "mediaIds" to replacement.mediaIds.joinToString(","),
          "queuePersistenceLimited" to replacement.queuePersistenceLimited,
        ),
      )
    } finally {
      try {
        harness.close()
      } finally {
        grantAudioPermissionAndWait()
      }
    }
  }

  @Test
  fun replacementRestore_fullReplacementSurvivesRestart() = runBlocking {
    M3ADeviceProtocol.requireHostDriven()
    assertAudioPermission(granted = true)
    val harness = M3AMediaControllerHarness(M3ADeviceProtocol.context)
    val controller = harness.connect()

    try {
      val restored =
        harness.awaitSnapshot(controller) { snapshot ->
          snapshot.mediaIds == listOf(NIGHT_ID) && snapshot.currentIndex == 0
        }
      assertFalse(restored.playWhenReady)
      assertFalse(restored.isPlaying)
      M3ADeviceProtocol.writeResult(
        "permission-replacement-restore",
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
  fun permanentMissingSetup_deletesRealWavAndRunsProductionScan() = runBlocking {
    M3ADeviceProtocol.requireHostDriven()
    assertAudioPermission(granted = true)
    val context = M3ADeviceProtocol.context
    val entryPoint = M3ADeviceEntryPoint.from(context)
    val fixture = M3ADeviceAudioFixture(context)
    val local = fixture.createAndScan(entryPoint.libraryScanner())
    val demos = entryPoint.trackRepository().demoTracks()
    val morning = demos.single { it.id.value == MORNING_ID }
    val night = demos.single { it.id.value == NIGHT_ID }
    val expected = listOf(MORNING_ID, local.id.value, NIGHT_ID)
    val harness = M3AMediaControllerHarness(context)
    val controller = harness.connect()

    try {
      harness.replaceQueue(
        controller = controller,
        tracks = listOf(morning, local, night),
        startIndex = 1,
        play = false,
      )
      harness.awaitSnapshot(controller) { snapshot ->
        snapshot.mediaIds == expected && snapshot.currentIndex == 1
      }
      delay(600)
      val deletedRows = fixture.delete(local)
      assertTrue("Expected the generated MediaStore row to be deleted", deletedRows >= 1)
      val scanResult = entryPoint.libraryScanner().scan().getOrThrow()
      assertTrue("Expected production scan to mark the WAV unavailable", scanResult.unavailable >= 1)
      withTimeout(10.seconds) {
        entryPoint.trackRepository().observeAvailableLocalTracks().first { tracks ->
          tracks.none { track -> track.id == local.id }
        }
      }
      withTimeout(10.seconds) {
        entryPoint.trackRepository().observeTracks().first { tracks ->
          tracks.none { track -> track.id == local.id }
        }
      }
      M3ADeviceProtocol.writeResult(
        "permission-missing-setup",
        mapOf(
          "deletedRows" to deletedRows,
          "localTrackId" to local.id.value,
          "originalMediaIds" to expected.joinToString(","),
          "scanUnavailable" to scanResult.unavailable,
          "productionLibraryContainsLocal" to false,
          "productionTracksContainLocal" to false,
        ),
      )
    } finally {
      harness.close()
    }
  }

  @Test
  fun permanentMissingRestore_removesOnlyMissingOccurrence() = runBlocking {
    M3ADeviceProtocol.requireHostDriven()
    assertAudioPermission(granted = true)
    val missingId = M3ADeviceProtocol.result("permission-missing-setup").getValue("localTrackId")
    val harness = M3AMediaControllerHarness(M3ADeviceProtocol.context)
    val controller = harness.connect()

    try {
      val restored =
        harness.awaitSnapshot(controller) { snapshot ->
          snapshot.mediaIds == listOf(MORNING_ID, NIGHT_ID) && snapshot.currentIndex == 1
        }
      assertEquals(listOf(MORNING_ID, NIGHT_ID), restored.mediaIds)
      assertFalse(missingId in restored.mediaIds)
      assertEquals(1, restored.currentIndex)
      assertFalse(restored.playWhenReady)
      assertFalse(restored.isPlaying)
      delay(600)
      M3ADeviceProtocol.writeResult(
        "permission-missing-restore",
        mapOf(
          "missingTrackId" to missingId,
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

  private suspend fun persistGeneratedLocalQueue(marker: String) {
    val context = M3ADeviceProtocol.context
    val entryPoint = M3ADeviceEntryPoint.from(context)
    val local = M3ADeviceAudioFixture(context).createAndScan(entryPoint.libraryScanner())
    val harness = M3AMediaControllerHarness(context)
    val controller = harness.connect()
    try {
      harness.replaceQueue(controller, listOf(local), startIndex = 0, play = false)
      val snapshot =
        harness.awaitSnapshot(controller) { state ->
          state.mediaIds == listOf(local.id.value) && state.currentIndex == 0
        }
      delay(600)
      M3ADeviceProtocol.writeResult(
        marker,
        mapOf(
          "localTrackId" to local.id.value,
          "sourceUri" to local.sourceUri,
          "displayName" to local.displayName,
          "mediaIds" to snapshot.mediaIds.joinToString(","),
        ),
      )
    } finally {
      harness.close()
    }
  }

  private fun assertAudioPermission(granted: Boolean) {
    val actual =
      ContextCompat.checkSelfPermission(
        M3ADeviceProtocol.context,
        Manifest.permission.READ_MEDIA_AUDIO,
      ) == PackageManager.PERMISSION_GRANTED
    assertEquals("Unexpected READ_MEDIA_AUDIO state", granted, actual)
  }

  private suspend fun grantAudioPermissionAndWait() {
    val context: Context = M3ADeviceProtocol.context
    InstrumentationRegistry.getInstrumentation()
      .uiAutomation
      .grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_AUDIO)
    withTimeout(5.seconds) {
      while (
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) !=
          PackageManager.PERMISSION_GRANTED
      ) {
        delay(25)
      }
    }
  }

  private companion object {
    const val MORNING_ID = "demo:morning-pulse"
    const val NIGHT_ID = "demo:night-drive"
  }
}
