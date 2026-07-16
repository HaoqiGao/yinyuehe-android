package app.yinyuehe.core.player.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestorePersistenceGateTest {
  private val app = ControllerIdentity("app.yinyuehe", 10_001)

  @Test
  fun confirmedTimeline_defensivelySnapshotsMutableFingerprintLists() {
    val gate = RestorePersistenceGate(app)
    val mutable = mutableFingerprint()
    assertTrue(gate.onConfirmedTimeline(mutable.fingerprint))

    mutable.mutateAfterSave()

    assertFalse(gate.onConfirmedTimeline(originalFingerprint()))
    assertEquals(1L, gate.mutationGeneration)
    assertEquals(RestoreGateStatus.SUPERSEDED, gate.status)
  }

  @Test
  fun appliedRestore_defensivelySnapshotsMutableFingerprintLists() {
    val gate = RestorePersistenceGate(app)
    val mutable = mutableFingerprint()
    assertTrue(gate.tryBeginRestoreApply(expectedGeneration = 0, playerIsEmpty = true))
    assertTrue(gate.finishApplied(expectedGeneration = 0, appliedFingerprint = mutable.fingerprint))

    mutable.mutateAfterSave()

    assertFalse(gate.onConfirmedTimeline(originalFingerprint()))
    assertEquals(0L, gate.mutationGeneration)
    assertEquals(RestoreGateStatus.APPLIED, gate.status)
  }

  @Test
  fun permissionFailure_defensivelySnapshotsMutableAppliedFingerprintLists() {
    val gate = RestorePersistenceGate(app)
    val mutable = mutableFingerprint()
    assertTrue(gate.tryBeginRestoreApply(expectedGeneration = 0, playerIsEmpty = true))
    assertTrue(
      gate.finishFailed(
        expectedGeneration = 0,
        reason = RestoreFailureReason.PERMISSION_DENIED,
        appliedFingerprint = mutable.fingerprint,
      )
    )

    mutable.mutateAfterSave()

    assertFalse(gate.onConfirmedTimeline(originalFingerprint()))
    assertEquals(0L, gate.mutationGeneration)
    assertEquals(RestoreGateStatus.FAILED, gate.status)
  }

  @Test
  fun initialEmptyTimelineAndDestroyCannotOpenTheGate() {
    val gate = RestorePersistenceGate(app)

    assertFalse(gate.onConfirmedTimeline(PlayerQueueFingerprint.EMPTY))
    assertEquals(RestoreGateStatus.RESTORE_PENDING, gate.status)
    assertFalse(gate.canPersist)
    assertEquals(0L, gate.mutationGeneration)
  }

  @Test
  fun confirmedUserQueueDuringSlowRestore_supersedesTheReadGeneration() {
    val gate = RestorePersistenceGate(app)
    val userQueue = fingerprint(listOf("occurrence-1"), listOf("demo:one"), 0)

    assertTrue(gate.onConfirmedTimeline(userQueue))

    assertEquals(RestoreGateStatus.SUPERSEDED, gate.status)
    assertEquals(1L, gate.mutationGeneration)
    assertTrue(gate.canPersist)
    assertFalse(gate.tryBeginRestoreApply(expectedGeneration = 0, playerIsEmpty = false))
  }

  @Test
  fun timelineCallbacksInsideRestoreApplyRemainPendingUntilFinishApplied() {
    val gate = RestorePersistenceGate(app)
    val restored = fingerprint(listOf("restored-occurrence"), listOf("demo:stored"), 0)

    assertTrue(gate.tryBeginRestoreApply(expectedGeneration = 0, playerIsEmpty = true))
    assertFalse(gate.onConfirmedTimeline(restored))
    assertEquals(0L, gate.mutationGeneration)
    assertEquals(RestoreGateStatus.RESTORE_PENDING, gate.status)
    assertTrue(gate.isApplyingRestore)
    assertFalse(gate.canPersist)

    assertTrue(gate.finishApplied(expectedGeneration = 0, appliedFingerprint = restored))
    assertEquals(RestoreGateStatus.APPLIED, gate.status)
    assertFalse(gate.isApplyingRestore)
    assertTrue(gate.canPersist)
  }

  @Test
  fun permissionFailure_addRemoveMoveAndCurrentCallbacksCannotOpenTheGate() {
    val gate = permissionLimitedGate()

    assertFalse(
      gate.onConfirmedTimeline(
        fingerprint(
          listOf("demo-one", "demo-two", "added"),
          listOf("demo:one", "demo:two", "demo:added"),
          0,
        )
      )
    )
    assertFalse(
      gate.onConfirmedTimeline(
        fingerprint(
          listOf("demo-one", "added"),
          listOf("demo:one", "demo:added"),
          0,
        )
      )
    )
    assertFalse(
      gate.onConfirmedTimeline(
        fingerprint(
          listOf("added", "demo-one"),
          listOf("demo:added", "demo:one"),
          0,
        )
      )
    )
    assertFalse(
      gate.onConfirmedTimeline(
        fingerprint(
          listOf("added", "demo-one"),
          listOf("demo:added", "demo:one"),
          1,
        )
      )
    )

    assertEquals(3L, gate.mutationGeneration)
    assertEquals(RestoreGateStatus.FAILED, gate.status)
    assertTrue(gate.queuePersistenceLimited)
    assertFalse(gate.canPersist)
  }

  @Test
  fun permissionFailure_onlyExactAppReplacementAtCurrentGenerationCanOpenTheGate() {
    val gate = permissionLimitedGate()

    gate.recordSetMediaItems(app, listOf("demo:wrong-id"), startIndex = 0)
    assertFalse(
      gate.onConfirmedTimeline(
        fingerprint(listOf("wrong-id"), listOf("demo:different"), 0)
      )
    )

    gate.recordSetMediaItems(app, listOf("demo:wrong-start"), startIndex = 1)
    assertFalse(
      gate.onConfirmedTimeline(
        fingerprint(listOf("wrong-start"), listOf("demo:wrong-start"), 0)
      )
    )

    gate.recordSetMediaItems(app, listOf("demo:stale-generation"), startIndex = 0)
    assertFalse(
      gate.onConfirmedTimeline(
        fingerprint(listOf("intervening"), listOf("demo:intervening"), 0)
      )
    )
    assertFalse(
      gate.onConfirmedTimeline(
        fingerprint(listOf("late"), listOf("demo:stale-generation"), 0)
      )
    )

    gate.recordSetMediaItems(app, listOf("demo:replacement"), startIndex = 0)
    assertTrue(
      gate.onConfirmedTimeline(
        fingerprint(listOf("replacement"), listOf("demo:replacement"), 0)
      )
    )

    assertEquals(RestoreGateStatus.SUPERSEDED, gate.status)
    assertNull(gate.failureReason)
    assertTrue(gate.canPersist)
    assertFalse(gate.queuePersistenceLimited)
  }

  @Test
  fun everyLaterSetMediaItemsIncludingExternalInvalidatesAnOlderAppMarker() {
    val gate = permissionLimitedGate()

    gate.recordSetMediaItems(app, listOf("demo:replacement"), startIndex = 0)
    gate.recordSetMediaItems(
      caller = ControllerIdentity("external.controller", 20_002),
      expectedMediaIds = listOf("demo:external"),
      startIndex = 0,
    )

    assertFalse(
      gate.onConfirmedTimeline(
        fingerprint(listOf("late-app"), listOf("demo:replacement"), 0)
      )
    )
    assertEquals(RestoreGateStatus.FAILED, gate.status)
    assertTrue(gate.queuePersistenceLimited)
  }

  @Test
  fun externalControllerCannotCreateAReplacementMarker() {
    val gate = permissionLimitedGate()

    gate.recordSetMediaItems(
      caller = ControllerIdentity("external.controller", app.uid),
      expectedMediaIds = listOf("demo:external"),
      startIndex = 0,
    )

    assertFalse(
      gate.onConfirmedTimeline(
        fingerprint(listOf("external"), listOf("demo:external"), 0)
      )
    )
    assertFalse(gate.canPersist)
  }

  @Test
  fun incompatibleAndTransientFailurePreserveWritesUntilAConfirmedUserQueue() {
    val incompatible = RestorePersistenceGate(app)
    assertTrue(incompatible.finishIncompatible(expectedGeneration = 0))
    assertFalse(incompatible.canPersist)
    assertTrue(
      incompatible.onConfirmedTimeline(
        fingerprint(listOf("new-occurrence"), listOf("demo:new"), 0)
      )
    )
    assertEquals(RestoreGateStatus.SUPERSEDED, incompatible.status)

    val transient = RestorePersistenceGate(app)
    assertTrue(transient.finishFailed(0, RestoreFailureReason.TRANSIENT))
    assertFalse(transient.canPersist)
    assertTrue(
      transient.onConfirmedTimeline(
        fingerprint(listOf("retry-occurrence"), listOf("demo:retry"), 0)
      )
    )
    assertEquals(RestoreGateStatus.SUPERSEDED, transient.status)
  }

  @Test
  fun staleApplyCompletionCannotChangeASupersededGate() {
    val gate = RestorePersistenceGate(app)
    val userQueue = fingerprint(listOf("user"), listOf("demo:user"), 0)
    val staleRestore = fingerprint(listOf("restore"), listOf("demo:restore"), 0)
    assertTrue(gate.onConfirmedTimeline(userQueue))

    assertFalse(gate.finishApplied(0, staleRestore))
    assertFalse(gate.finishIncompatible(0))
    assertFalse(gate.finishFailed(0, RestoreFailureReason.TRANSIENT, staleRestore))
    assertFalse(gate.abortApply(0))

    assertEquals(RestoreGateStatus.SUPERSEDED, gate.status)
    assertEquals(1L, gate.mutationGeneration)
    assertTrue(gate.canPersist)
    assertNull(gate.failureReason)
  }

  private fun permissionLimitedGate(): RestorePersistenceGate =
    RestorePersistenceGate(app).also { gate ->
      val safeSubset =
        fingerprint(
          listOf("demo-one", "demo-two"),
          listOf("demo:one", "demo:two"),
          0,
        )
      assertTrue(gate.tryBeginRestoreApply(0, playerIsEmpty = true))
      assertTrue(
        gate.finishFailed(
          expectedGeneration = 0,
          reason = RestoreFailureReason.PERMISSION_DENIED,
          appliedFingerprint = safeSubset,
        )
      )
      assertTrue(gate.queuePersistenceLimited)
    }
}

private fun fingerprint(keys: List<String>, ids: List<String>, index: Int) =
  PlayerQueueFingerprint(keys, ids, index)

private fun mutableFingerprint(): MutableFingerprint {
  val occurrenceKeys = mutableListOf("stable-occurrence")
  val mediaIds = mutableListOf("demo:stable")
  return MutableFingerprint(
    occurrenceKeys = occurrenceKeys,
    mediaIds = mediaIds,
    fingerprint = fingerprint(occurrenceKeys, mediaIds, index = 0),
  )
}

private fun originalFingerprint() =
  fingerprint(listOf("stable-occurrence"), listOf("demo:stable"), index = 0)

private data class MutableFingerprint(
  val occurrenceKeys: MutableList<String>,
  val mediaIds: MutableList<String>,
  val fingerprint: PlayerQueueFingerprint,
) {
  fun mutateAfterSave() {
    occurrenceKeys[0] = "mutated-occurrence"
    mediaIds[0] = "demo:mutated"
  }
}
