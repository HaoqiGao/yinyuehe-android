package app.yinyuehe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M3APositionRecoveryDeviceTest {
  @Test
  fun fixture_isAtLeastThirtySeconds() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val fixture = M3ADeviceAudioFixture(context)

    try {
      val track = fixture.createAndScan(M3ADeviceEntryPoint.from(context).libraryScanner())
      assertEquals(M3ADeviceAudioFixture.DISPLAY_NAME, track.displayName)
      assertEquals(fixture.createdContentUri.toString(), track.sourceUri)
      assertTrue(
        "Expected a real WAV duration >= 30 seconds, got ${track.durationMs}",
        (track.durationMs ?: 0L) >= 30_000L,
      )
    } finally {
      fixture.deleteByFixedDisplayName()
    }
  }

  @Test
  fun phaseOne_persistsLongTrackPosition() = runBlocking {
    M3ADeviceProtocol.requireHostDriven()
    val context = M3ADeviceProtocol.context
    assertEquals(
      PackageManager.PERMISSION_GRANTED,
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS),
    )
    val fixture = M3ADeviceAudioFixture(context)
    val entryPoint = M3ADeviceEntryPoint.from(context)
    val track = fixture.createAndScan(entryPoint.libraryScanner())
    val harness = M3AMediaControllerHarness(context)
    val controller = harness.connect()

    try {
      harness.replaceQueue(
        controller = controller,
        tracks = listOf(track),
        startIndex = 0,
        startPositionMs = 18_000,
        repeatMode = Player.REPEAT_MODE_OFF,
        play = true,
      )
      val started =
        harness.awaitSnapshot(controller) { snapshot ->
          snapshot.mediaIds == listOf(track.id.value) &&
            snapshot.currentIndex == 0 &&
            snapshot.positionMs >= 18_000 &&
            snapshot.isPlaying
        }
      val recordUntil = android.os.SystemClock.elapsedRealtime() + 6_200L
      var latest = started
      while (android.os.SystemClock.elapsedRealtime() < recordUntil) {
        latest = harness.snapshot(controller)
        M3ADeviceProtocol.writeAtomic("actual_position_ms", latest.positionMs.toString())
        delay(100)
      }
      assertTrue(latest.positionMs >= 23_000L)
      M3ADeviceProtocol.writeResult(
        "position-phase-one",
        mapOf(
          "actualPositionMs" to latest.positionMs,
          "isPlaying" to latest.isPlaying,
          "trackId" to track.id.value,
        ),
      )
    } finally {
      harness.close()
    }
  }

  @Test
  fun phaseTwo_restoresCapturedPositionPaused() = runBlocking {
    M3ADeviceProtocol.requireHostDriven()
    val expected = M3ADeviceProtocol.requiredArgument("expectedPersistedPositionMs").toLong()
    val expectedTrackId = M3ADeviceProtocol.result("position-phase-one").getValue("trackId")
    val harness = M3AMediaControllerHarness(M3ADeviceProtocol.context)
    val controller = harness.connect()

    try {
      val restored =
        harness.awaitSnapshot(controller) { snapshot ->
          snapshot.mediaIds == listOf(expectedTrackId) && snapshot.positionMs >= 15_000L
        }
      val sessionReportsActivePlayback = harness.sessionReportsActivePlayback()
      val notificationSafe = harness.mediaNotificationIsAbsentOrOffersPlayNotPause()
      assertTrue(abs(restored.positionMs - expected) <= 1_000L)
      assertFalse(restored.playWhenReady)
      assertFalse(restored.isPlaying)
      assertFalse(sessionReportsActivePlayback)
      assertTrue(notificationSafe)
      M3ADeviceProtocol.writeResult(
        "position-phase-two",
        mapOf(
          "persistedPositionMs" to expected,
          "restoredPositionMs" to restored.positionMs,
          "restoreDeltaMs" to abs(restored.positionMs - expected),
          "playWhenReady" to restored.playWhenReady,
          "isPlaying" to restored.isPlaying,
          "sessionReportsActivePlayback" to sessionReportsActivePlayback,
          "notificationIsAbsentOrOffersPlayNotPause" to notificationSafe,
          "trackId" to restored.mediaIds.single(),
        ),
      )
    } finally {
      harness.close()
    }
  }

  @Test
  fun cleanupFixtureByFixedDisplayName() {
    M3ADeviceProtocol.requireHostDriven()
    val deleted = M3ADeviceAudioFixture(M3ADeviceProtocol.context).deleteByFixedDisplayName()
    M3ADeviceProtocol.writeResult("cleanup", mapOf("deletedRows" to deleted))
  }
}
