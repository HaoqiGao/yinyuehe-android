package app.yinyuehe.core.player.service

internal enum class RestoreGateStatus {
  RESTORE_PENDING,
  APPLIED,
  SUPERSEDED,
  INCOMPATIBLE,
  FAILED,
}

internal enum class RestoreFailureReason { PERMISSION_DENIED, TRANSIENT }

internal data class ControllerIdentity(val packageName: String, val uid: Int)

internal data class PlayerQueueFingerprint(
  val occurrenceKeys: List<String>,
  val mediaIds: List<String>,
  val currentIndex: Int,
) {
  companion object {
    val EMPTY = PlayerQueueFingerprint(emptyList(), emptyList(), -1)
  }
}

internal data class PendingQueueReplacement(
  val caller: ControllerIdentity,
  val expectedMediaIds: List<String>,
  val startIndex: Int,
  val generation: Long,
)

internal class RestorePersistenceGate(
  private val applicationController: ControllerIdentity,
) {
  var status: RestoreGateStatus = RestoreGateStatus.RESTORE_PENDING
    private set

  var mutationGeneration: Long = 0
    private set

  var failureReason: RestoreFailureReason? = null
    private set

  var isApplyingRestore: Boolean = false
    private set

  private var lastFingerprint = PlayerQueueFingerprint.EMPTY
  private var pendingReplacement: PendingQueueReplacement? = null

  val canPersist: Boolean
    get() = status == RestoreGateStatus.APPLIED || status == RestoreGateStatus.SUPERSEDED

  val queuePersistenceLimited: Boolean
    get() = status == RestoreGateStatus.FAILED && failureReason == RestoreFailureReason.PERMISSION_DENIED

  fun recordSetMediaItems(
    caller: ControllerIdentity,
    expectedMediaIds: List<String>,
    startIndex: Int,
  ) {
    pendingReplacement = null
    if (caller != applicationController) return
    pendingReplacement =
      PendingQueueReplacement(
        caller = caller,
        expectedMediaIds = expectedMediaIds.toList(),
        startIndex = startIndex,
        generation = mutationGeneration,
      )
  }

  fun onConfirmedTimeline(fingerprint: PlayerQueueFingerprint): Boolean {
    if (fingerprint.occurrenceKeys == lastFingerprint.occurrenceKeys) {
      lastFingerprint = fingerprint.toDefensiveSnapshot()
      return false
    }
    if (isApplyingRestore) {
      lastFingerprint = fingerprint.toDefensiveSnapshot()
      return false
    }

    val generationBeforeChange = mutationGeneration
    val replacement = pendingReplacement
    val matchingReplacement =
      replacement != null &&
        replacement.caller == applicationController &&
        replacement.generation == generationBeforeChange &&
        replacement.expectedMediaIds == fingerprint.mediaIds &&
        replacement.startIndex == fingerprint.currentIndex
    mutationGeneration += 1
    lastFingerprint = fingerprint.toDefensiveSnapshot()
    pendingReplacement = null

    val opened =
      when (status) {
        RestoreGateStatus.RESTORE_PENDING,
        RestoreGateStatus.INCOMPATIBLE -> true
        RestoreGateStatus.FAILED ->
          failureReason != RestoreFailureReason.PERMISSION_DENIED || matchingReplacement
        RestoreGateStatus.APPLIED,
        RestoreGateStatus.SUPERSEDED -> false
      }
    if (opened) {
      status = RestoreGateStatus.SUPERSEDED
      failureReason = null
    }
    return opened
  }

  fun tryBeginRestoreApply(expectedGeneration: Long, playerIsEmpty: Boolean): Boolean {
    if (
      !isPendingGeneration(expectedGeneration) ||
        !playerIsEmpty ||
        isApplyingRestore
    ) {
      return false
    }
    isApplyingRestore = true
    return true
  }

  fun finishApplied(
    expectedGeneration: Long,
    appliedFingerprint: PlayerQueueFingerprint,
  ): Boolean {
    if (!isCurrentApply(expectedGeneration)) return false
    isApplyingRestore = false
    status = RestoreGateStatus.APPLIED
    failureReason = null
    lastFingerprint = appliedFingerprint.toDefensiveSnapshot()
    pendingReplacement = null
    return true
  }

  fun finishIncompatible(expectedGeneration: Long): Boolean {
    if (!isPendingGeneration(expectedGeneration) || isApplyingRestore) return false
    status = RestoreGateStatus.INCOMPATIBLE
    pendingReplacement = null
    return true
  }

  fun finishFailed(
    expectedGeneration: Long,
    reason: RestoreFailureReason,
    appliedFingerprint: PlayerQueueFingerprint = lastFingerprint,
  ): Boolean {
    if (!isPendingGeneration(expectedGeneration)) return false
    isApplyingRestore = false
    status = RestoreGateStatus.FAILED
    failureReason = reason
    lastFingerprint = appliedFingerprint.toDefensiveSnapshot()
    pendingReplacement = null
    return true
  }

  fun abortApply(expectedGeneration: Long): Boolean {
    if (!isCurrentApply(expectedGeneration)) return false
    return finishFailed(expectedGeneration, RestoreFailureReason.TRANSIENT)
  }

  private fun isPendingGeneration(expectedGeneration: Long): Boolean =
    status == RestoreGateStatus.RESTORE_PENDING && mutationGeneration == expectedGeneration

  private fun isCurrentApply(expectedGeneration: Long): Boolean =
    isApplyingRestore && isPendingGeneration(expectedGeneration)
}

private fun PlayerQueueFingerprint.toDefensiveSnapshot(): PlayerQueueFingerprint =
  copy(
    occurrenceKeys = occurrenceKeys.toList(),
    mediaIds = mediaIds.toList(),
  )
