#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=m3a-device/lib.sh
source "$SCRIPT_DIR/m3a-device/lib.sh"

SELECTED_CASE=""
if test "$#" -gt 0; then
  test "$#" -eq 2 && test "$1" = "--case" || die "usage: $0 [--case position|queue|permission|snapshot-safety|reconnect]"
  SELECTED_CASE="$2"
  case "$SELECTED_CASE" in
    position|queue|permission|snapshot-safety|reconnect) ;;
    *) die "unknown case: $SELECTED_CASE" ;;
  esac
fi

require_host_environment
RESULT_DIR="$M3A_REPO_ROOT/build/m3a-device/$(date -u +%Y%m%dT%H%M%SZ)-${ANDROID_SERIAL}"
mkdir -p "$RESULT_DIR"
export RESULT_DIR
exec > >(tee "$RESULT_DIR/stdout.txt") 2>&1

CLEANUP_STARTED=false
MAIN_SUCCEEDED=false

write_final_hashes() {
  local temporary
  temporary="$(mktemp "${TMPDIR:-/tmp}/m3a-hashes.XXXXXX")"
  if ! find "$RESULT_DIR" -type f \
      ! -name stdout.txt \
      ! -name hashes.sha256 \
      ! -name completion.result \
      -print \
      | LC_ALL=C sort \
      | while IFS= read -r evidence_file; do
          shasum -a 256 "$evidence_file"
        done \
      > "$temporary"; then
    rm -f "$temporary"
    return 1
  fi
  if ! mv "$temporary" "$RESULT_DIR/hashes.sha256"; then
    rm -f "$temporary"
    return 1
  fi
}

cleanup() {
  local original_status=$?
  test "$CLEANUP_STARTED" = false || return
  CLEANUP_STARTED=true
  trap - EXIT INT TERM
  set +e
  local cleanup_status=0
  adb_serial shell run-as "$M3A_PACKAGE" mkdir -p "$M3A_PROTOCOL_DIR" \
    >/dev/null 2>&1 || cleanup_status=1
  adb_serial shell run-as "$M3A_PACKAGE" touch \
    "$M3A_PROTOCOL_DIR/release-before-read" \
    "$M3A_PROTOCOL_DIR/release-before-apply" \
    >/dev/null 2>&1 || cleanup_status=1
  adb_serial shell am force-stop "$M3A_PACKAGE" >/dev/null 2>&1 || cleanup_status=1
  adb_serial shell pm grant --user "$M3A_DEVICE_USER_ID" "$M3A_PACKAGE" android.permission.READ_MEDIA_AUDIO \
    >/dev/null 2>&1 || cleanup_status=1
  adb_serial shell pm grant --user "$M3A_DEVICE_USER_ID" "$M3A_PACKAGE" android.permission.POST_NOTIFICATIONS \
    >/dev/null 2>&1 || cleanup_status=1
  local cleanup_instrument_status=0
  local cleanup_output_file="$RESULT_DIR/cleanup.instrumentation.txt"
  local -a cleanup_command=(
    "$ANDROID_HOME/platform-tools/adb" -s "$ANDROID_SERIAL"
    shell am instrument -w -r
    -e m3aHostDriven true
    -e class app.yinyuehe.M3APositionRecoveryDeviceTest#cleanupFixtureByFixedDisplayName
    "$M3A_RUNNER"
  )
  run_with_host_timeout \
    "$M3A_HOST_TIMEOUT_SECONDS" "$cleanup_output_file" "${cleanup_command[@]}" \
    || cleanup_instrument_status=$?
  test "$cleanup_instrument_status" -eq 0 || cleanup_status=1
  local cleanup_output
  cleanup_output="$(cat "$cleanup_output_file")" || cleanup_status=1
  printf '%s\n' "$cleanup_output" | tr -d '\r' | grep -Eq '^OK \([1-9][0-9]* tests?\)$' \
    || cleanup_status=1
  if printf '%s\n' "$cleanup_output" \
      | tr -d '\r' \
      | grep -Eiq 'skipped|assumption|FAILURES|INSTRUMENTATION_FAILED'; then
    cleanup_status=1
  fi
  adb_serial shell am force-stop "$M3A_PACKAGE" >/dev/null 2>&1 || cleanup_status=1
  adb_serial shell am force-stop "${M3A_PACKAGE}.test" >/dev/null 2>&1 || cleanup_status=1
  host_delete_fixture_rows || cleanup_status=1
  remove_private_protocol_files || cleanup_status=1
  if ! permission_is_granted android.permission.READ_MEDIA_AUDIO || \
      ! permission_is_granted android.permission.POST_NOTIFICATIONS; then
    cleanup_status=1
  fi
  if test "$cleanup_status" -eq 0; then
    printf 'M3A_CLEANUP permissions=restored fixture=deleted protocolFiles=removed\n' \
      | tee "$RESULT_DIR/cleanup.txt"
  else
    printf 'M3A_CLEANUP_FAILED cleanupExit=%s\n' "$cleanup_status" \
      | tee "$RESULT_DIR/cleanup.txt"
  fi
  local hash_status=0
  write_final_hashes || {
    hash_status=1
    cleanup_status=1
  }
  local completion_status=failed
  if test "$original_status" -eq 0 && \
      test "$cleanup_status" -eq 0 && \
      test "$MAIN_SUCCEEDED" = true; then
    completion_status=passed
  fi
  local completion_temporary="$RESULT_DIR/.completion.result.tmp"
  local completion_write_status=0
  {
    printf 'status=%s\n' "$completion_status"
    printf 'mainExit=%s\n' "$original_status"
    printf 'cleanupExit=%s\n' "$cleanup_status"
    printf 'hashExit=%s\n' "$hash_status"
    printf 'stdoutExcludedFromHashes=true\n'
    printf 'completionExcludedFromHashes=true\n'
  } > "$completion_temporary" || completion_write_status=1
  if test "$completion_write_status" -eq 0; then
    mv "$completion_temporary" "$RESULT_DIR/completion.result" \
      || completion_write_status=1
  fi
  if test "$completion_write_status" -ne 0; then
    cleanup_status=1
    rm -f "$completion_temporary" "$RESULT_DIR/completion.result"
  fi
  if test "$completion_write_status" -eq 0 && test "$completion_status" = passed; then
    printf 'M3A_ACCEPTANCE_PASS cases=%s resultDir=%s\n' \
      "${SELECTED_CASE:-all}" "$RESULT_DIR"
  fi
  set -e
  if test "$original_status" -ne 0; then
    exit "$original_status"
  fi
  test "$cleanup_status" -eq 0 || exit 1
  exit 0
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

cd "$M3A_REPO_ROOT"
{
  printf 'serial=%s\n' "$ANDROID_SERIAL"
  printf 'api=%s\n' "$M3A_API_LEVEL"
  printf 'user=%s\n' "$M3A_DEVICE_USER_ID"
  printf 'javaHome=%s\n' "$JAVA_HOME"
  "$JAVA_HOME/bin/java" -version 2>&1
  adb_serial shell getprop ro.build.fingerprint | tr -d '\r'
} > "$RESULT_DIR/device-build-metadata.txt"

./gradlew \
  :app:assembleDebug :app:assembleDebugAndroidTest \
  :feature:library:assembleDebugAndroidTest \
  :core:data:assembleDebugAndroidTest \
  --stacktrace | tee "$RESULT_DIR/gradle-build.txt"
install_debug_apks
grant_and_verify_runtime_permissions

PROTOC_SOURCE="$(find "$HOME/.gradle/caches/modules-2/files-2.1/com.google.protobuf/protoc/4.32.1" \
  -type f -name 'protoc-4.32.1-*' | head -n 1)"
test -f "$PROTOC_SOURCE" || die "Gradle-downloaded protoc 4.32.1 not found"
cp "$PROTOC_SOURCE" "$RESULT_DIR/protoc-4.32.1"
chmod 700 "$RESULT_DIR/protoc-4.32.1"
PROTOC="$RESULT_DIR/protoc-4.32.1"
export PROTOC
test "$($PROTOC --version)" = "libprotoc 32.1" || die "unexpected protoc banner"
printf '%s\n' "$($PROTOC --version)" > "$RESULT_DIR/protoc-version.txt"

run_position_case() {
  reset_case_state
  instrument app.yinyuehe.M3APositionRecoveryDeviceTest#phaseOne_persistsLongTrackPosition
  copy_protocol_result position-phase-one
  copy_private_file "$M3A_SNAPSHOT_PATH" "$RESULT_DIR/position-playback_snapshot.pb"
  decode_snapshot \
    "$RESULT_DIR/position-playback_snapshot.pb" \
    "$RESULT_DIR/position-playback_snapshot.txt"
  local persisted_position_ms actual_position_ms position_delta_ms
  persisted_position_ms="$(decoded_field_or_default "$RESULT_DIR/position-playback_snapshot.txt" position_ms 0)"
  actual_position_ms="$(adb_serial exec-out run-as "$M3A_PACKAGE" cat "$M3A_PROTOCOL_DIR/actual_position_ms" | tr -d '\r\n')"
  test -n "$persisted_position_ms" && test -n "$actual_position_ms" \
    || die "position capture was incomplete"
  position_delta_ms=$((actual_position_ms > persisted_position_ms \
    ? actual_position_ms - persisted_position_ms \
    : persisted_position_ms - actual_position_ms))
  test "$persisted_position_ms" -ge 15000 || die "persisted position below 15 seconds"
  test "$position_delta_ms" -le 6000 || die "persisted/actual position delta exceeds 6 seconds"
  printf 'M3A_POSITION_CAPTURE actual=%s persisted=%s delta=%s\n' \
    "$actual_position_ms" "$persisted_position_ms" "$position_delta_ms"
  adb_serial shell am force-stop "$M3A_PACKAGE"
  instrument \
    app.yinyuehe.M3APositionRecoveryDeviceTest#phaseTwo_restoresCapturedPositionPaused \
    expectedPersistedPositionMs "$persisted_position_ms"
  copy_protocol_result position-phase-two
  test "$(protocol_value "$RESULT_DIR/position-phase-two.result" playWhenReady)" = "false"
  test "$(protocol_value "$RESULT_DIR/position-phase-two.result" isPlaying)" = "false"
  test "$(protocol_value "$RESULT_DIR/position-phase-two.result" sessionReportsActivePlayback)" = "false"
  test "$(protocol_value "$RESULT_DIR/position-phase-two.result" notificationIsAbsentOrOffersPlayNotPause)" = "true"
  local restore_delta_ms
  restore_delta_ms="$(protocol_value "$RESULT_DIR/position-phase-two.result" restoreDeltaMs)"
  test "$restore_delta_ms" -le 1000 || die "restored position delta exceeds 1 second"
  printf 'M3A_CASE_POSITION_PASS persisted=%s actual=%s captureDelta=%s restoreDelta=%s\n' \
    "$persisted_position_ms" "$actual_position_ms" "$position_delta_ms" "$restore_delta_ms"
}

run_queue_case() {
  reset_case_state
  instrument app.yinyuehe.M3AQueueRecoveryDeviceTest#phaseOne_persistsDuplicateQueueState
  copy_protocol_result queue-phase-one
  copy_private_file "$M3A_SNAPSHOT_PATH" "$RESULT_DIR/queue-playback_snapshot.pb"
  decode_snapshot \
    "$RESULT_DIR/queue-playback_snapshot.pb" \
    "$RESULT_DIR/queue-playback_snapshot.txt"
  local decoded_ids decoded_index decoded_repeat decoded_shuffle
  decoded_ids="$(decoded_media_ids "$RESULT_DIR/queue-playback_snapshot.txt")"
  decoded_index="$(decoded_field_or_default "$RESULT_DIR/queue-playback_snapshot.txt" current_index 0)"
  decoded_repeat="$(decoded_field_or_default "$RESULT_DIR/queue-playback_snapshot.txt" repeat_mode PLAYBACK_REPEAT_MODE_OFF)"
  decoded_shuffle="$(decoded_field_or_default "$RESULT_DIR/queue-playback_snapshot.txt" shuffle_enabled false)"
  test "$decoded_ids" = "demo:morning-pulse,demo:morning-pulse,demo:night-drive" \
    || die "decoded duplicate queue order changed: $decoded_ids"
  test "$decoded_index" = "1" || die "decoded queue index was not occurrence 1"
  test "$decoded_repeat" = "PLAYBACK_REPEAT_MODE_ALL" || die "decoded repeat was not ALL"
  test "$decoded_shuffle" = "true" || die "decoded shuffle was not enabled"
  force_stop_app
  instrument app.yinyuehe.M3AQueueRecoveryDeviceTest#phaseTwo_restoresExactDuplicateOccurrencesPaused
  copy_protocol_result queue-phase-two
  test "$(protocol_value "$RESULT_DIR/queue-phase-two.result" mediaIds)" = \
    "demo:morning-pulse,demo:morning-pulse,demo:night-drive"
  test "$(protocol_value "$RESULT_DIR/queue-phase-two.result" morningOccurrences)" = "2"
  test "$(protocol_value "$RESULT_DIR/queue-phase-two.result" currentIndex)" = "1"
  test "$(protocol_value "$RESULT_DIR/queue-phase-two.result" repeatMode)" = "2"
  test "$(protocol_value "$RESULT_DIR/queue-phase-two.result" shuffleEnabled)" = "true"
  test "$(protocol_value "$RESULT_DIR/queue-phase-two.result" playWhenReady)" = "false"
  test "$(protocol_value "$RESULT_DIR/queue-phase-two.result" isPlaying)" = "false"
  printf 'M3A_CASE_QUEUE_PASS ids=%s index=1 repeat=ALL shuffle=true paused=true\n' "$decoded_ids"
}

run_permission_case() {
  local preserve_before="$RESULT_DIR/permission-preserve-before.pb"
  local preserve_after="$RESULT_DIR/permission-preserve-after.pb"
  local replacement_before="$RESULT_DIR/permission-replacement-before.pb"
  local replacement_after="$RESULT_DIR/permission-replacement-after.pb"
  local preserve_local_id replacement_local_id

  reset_case_state
  instrument \
    app.yinyuehe.M3APermissionRecoveryDeviceTest#preserveSetup_persistsGeneratedLocalOccurrence
  copy_protocol_result permission-preserve-setup
  preserve_local_id="$(protocol_value "$RESULT_DIR/permission-preserve-setup.result" localTrackId)"
  wait_for_snapshot_ids \
    "$preserve_local_id" \
    "$preserve_before" \
    "$RESULT_DIR/permission-preserve-before.txt" \
    10
  force_stop_app
  revoke_permission android.permission.READ_MEDIA_AUDIO
  instrument \
    app.yinyuehe.M3APermissionRecoveryDeviceTest#preserveLimited_incrementalEditsCannotPersist
  permission_is_granted android.permission.READ_MEDIA_AUDIO \
    || die "preserve branch did not re-grant audio permission in finally"
  copy_protocol_result permission-preserve-limited
  wait_for_snapshot_ids \
    "$preserve_local_id" \
    "$preserve_after" \
    "$RESULT_DIR/permission-preserve-after.txt" \
    10
  cmp -s "$preserve_before" "$preserve_after" \
    || die "protected Proto changed after incremental add/remove/move"
  force_stop_app
  instrument \
    app.yinyuehe.M3APermissionRecoveryDeviceTest#preserveRestore_localOccurrenceReturnsAfterGrant
  copy_protocol_result permission-preserve-restore
  local restored_local_id
  restored_local_id="$(protocol_value "$RESULT_DIR/permission-preserve-restore.result" localTrackId)"
  test "$restored_local_id" = "$preserve_local_id" \
    || die "protected local occurrence did not return after grant"

  reset_case_state
  instrument \
    app.yinyuehe.M3APermissionRecoveryDeviceTest#replacementSetup_persistsGeneratedLocalOccurrence
  copy_protocol_result permission-replacement-setup
  replacement_local_id="$(protocol_value "$RESULT_DIR/permission-replacement-setup.result" localTrackId)"
  wait_for_snapshot_ids \
    "$replacement_local_id" \
    "$replacement_before" \
    "$RESULT_DIR/permission-replacement-before.txt" \
    10
  force_stop_app
  revoke_permission android.permission.READ_MEDIA_AUDIO
  instrument \
    app.yinyuehe.M3APermissionRecoveryDeviceTest#replacementLimited_fullSetMediaItemsCanReplaceProtectedQueue
  permission_is_granted android.permission.READ_MEDIA_AUDIO \
    || die "replacement branch did not re-grant audio permission in finally"
  copy_protocol_result permission-replacement-limited
  wait_for_snapshot_ids \
    "demo:night-drive" \
    "$replacement_after" \
    "$RESULT_DIR/permission-replacement-after.txt" \
    10
  if cmp -s "$replacement_before" "$replacement_after"; then
    die "own-app full setMediaItems did not replace protected Proto"
  fi
  local replacement_ids
  replacement_ids="$(decoded_media_ids "$RESULT_DIR/permission-replacement-after.txt")"
  test "$replacement_ids" = "demo:night-drive" \
    || die "full replacement Proto did not contain only Demo night"
  force_stop_app
  instrument \
    app.yinyuehe.M3APermissionRecoveryDeviceTest#replacementRestore_fullReplacementSurvivesRestart
  copy_protocol_result permission-replacement-restore
  test "$(protocol_value "$RESULT_DIR/permission-replacement-restore.result" mediaIds)" = \
    "demo:night-drive"

  reset_case_state
  instrument \
    app.yinyuehe.M3APermissionRecoveryDeviceTest#permanentMissingSetup_deletesRealWavAndRunsProductionScan
  copy_protocol_result permission-missing-setup
  local missing_id unavailable
  missing_id="$(protocol_value "$RESULT_DIR/permission-missing-setup.result" localTrackId)"
  unavailable="$(protocol_value "$RESULT_DIR/permission-missing-setup.result" scanUnavailable)"
  test "$unavailable" -ge 1 || die "production scanner did not report unavailable local WAV"
  test "$(protocol_value "$RESULT_DIR/permission-missing-setup.result" productionLibraryContainsLocal)" = \
    "false"
  wait_for_snapshot_ids \
    "demo:morning-pulse,$missing_id,demo:night-drive" \
    "$RESULT_DIR/permission-missing-before.pb" \
    "$RESULT_DIR/permission-missing-before.txt" \
    10
  local missing_before_ids
  missing_before_ids="$(decoded_media_ids "$RESULT_DIR/permission-missing-before.txt")"
  test "$missing_before_ids" = "demo:morning-pulse,$missing_id,demo:night-drive" \
    || die "pre-restart missing queue did not contain the real local occurrence"
  force_stop_app
  instrument \
    app.yinyuehe.M3APermissionRecoveryDeviceTest#permanentMissingRestore_removesOnlyMissingOccurrence
  copy_protocol_result permission-missing-restore
  wait_for_snapshot_ids \
    "demo:morning-pulse,demo:night-drive" \
    "$RESULT_DIR/permission-missing-normalized.pb" \
    "$RESULT_DIR/permission-missing-normalized.txt" \
    10
  local normalized_ids normalized_index
  normalized_ids="$(decoded_media_ids "$RESULT_DIR/permission-missing-normalized.txt")"
  normalized_index="$(decoded_field_or_default "$RESULT_DIR/permission-missing-normalized.txt" current_index 0)"
  test "$normalized_ids" = "demo:morning-pulse,demo:night-drive" \
    || die "normalized Proto did not preserve only the two Demo survivors"
  test "$normalized_index" = "1" || die "normalized current index was not repaired to Demo night"
  test "$(protocol_value "$RESULT_DIR/permission-missing-restore.result" missingTrackId)" = "$missing_id"
  test "$(protocol_value "$RESULT_DIR/permission-missing-restore.result" mediaIds)" = \
    "demo:morning-pulse,demo:night-drive"
  test "$(protocol_value "$RESULT_DIR/permission-missing-restore.result" currentIndex)" = "1"

  local preserve_hash replacement_before_hash replacement_after_hash
  preserve_hash="$(sha256_file "$preserve_before")"
  replacement_before_hash="$(sha256_file "$replacement_before")"
  replacement_after_hash="$(sha256_file "$replacement_after")"
  {
    printf 'preserveBeforeSha256=%s\n' "$preserve_hash"
    printf 'preserveAfterSha256=%s\n' "$(sha256_file "$preserve_after")"
    printf 'preserveBytes=%s\n' "$(wc -c < "$preserve_before" | tr -d ' ')"
    printf 'replacementBeforeSha256=%s\n' "$replacement_before_hash"
    printf 'replacementAfterSha256=%s\n' "$replacement_after_hash"
    printf 'replacementBeforeBytes=%s\n' "$(wc -c < "$replacement_before" | tr -d ' ')"
    printf 'replacementAfterBytes=%s\n' "$(wc -c < "$replacement_after" | tr -d ' ')"
    printf 'missingTrackId=%s\n' "$missing_id"
    printf 'missingUnavailable=%s\n' "$unavailable"
  } > "$RESULT_DIR/permission-host-evidence.result"
  printf 'M3A_CASE_PERMISSION_PASS preserveSha=%s replacementBeforeSha=%s replacementAfterSha=%s missing=%s normalized=%s\n' \
    "$preserve_hash" "$replacement_before_hash" "$replacement_after_hash" \
    "$missing_id" "$normalized_ids"
}

run_snapshot_safety_case() {
  local before_read_v1="$RESULT_DIR/snapshot-before-read-v1.pb"
  local before_read_blocked="$RESULT_DIR/snapshot-before-read-blocked.pb"
  local before_read_destroyed="$RESULT_DIR/snapshot-before-read-destroyed.pb"

  reset_case_state
  instrument app.yinyuehe.M3ASnapshotSafetyDeviceTest#setupKnownV1MorningSnapshot
  copy_protocol_result snapshot-known-v1
  copy_private_file "$M3A_SNAPSHOT_PATH" "$before_read_v1"
  force_stop_app
  prepare_restore_barrier before-read
  start_playback_service \
    | tee "$RESULT_DIR/snapshot-before-read-launch.txt"
  wait_for_private_file "$M3A_PROTOCOL_DIR/before-read-blocked" 10
  copy_private_file \
    "$M3A_PROTOCOL_DIR/before-read-blocked" \
    "$RESULT_DIR/snapshot-before-read-blocked.marker"
  copy_private_file "$M3A_SNAPSHOT_PATH" "$before_read_blocked"
  cmp -s "$before_read_v1" "$before_read_blocked" \
    || die "initial empty callback changed v1 bytes while BEFORE_READ was blocked"
  stop_playback_service "$RESULT_DIR/snapshot-before-read"
  wait_for_private_file "$M3A_PROTOCOL_DIR/before-read-cancelled" 5
  copy_private_file \
    "$M3A_PROTOCOL_DIR/before-read-cancelled" \
    "$RESULT_DIR/snapshot-before-read-cancelled.marker"
  release_restore_barrier before-read
  copy_private_file "$M3A_SNAPSHOT_PATH" "$before_read_destroyed"
  cmp -s "$before_read_v1" "$before_read_destroyed" \
    || die "onDestroy cancellation changed v1 bytes"

  reset_case_state
  instrument app.yinyuehe.M3ASnapshotSafetyDeviceTest#setupKnownV1MorningSnapshot
  copy_private_file "$M3A_SNAPSHOT_PATH" "$RESULT_DIR/snapshot-before-apply-morning.pb"
  force_stop_app
  prepare_restore_barrier before-apply
  adb_serial shell am start -W -n "$M3A_MAIN_ACTIVITY_COMPONENT" \
    | tee "$RESULT_DIR/snapshot-before-apply-launch.txt"
  wait_for_private_file "$M3A_PROTOCOL_DIR/before-apply-blocked" 10
  copy_private_file \
    "$M3A_PROTOCOL_DIR/before-apply-blocked" \
    "$RESULT_DIR/snapshot-before-apply-blocked.marker"
  instrument_no_restart \
    app.yinyuehe.M3ASnapshotSafetyDeviceTest#beforeApply_fullReplacementSupersedesBlockedRestore
  copy_protocol_result snapshot-before-apply-replacement
  wait_for_snapshot_ids \
    "demo:night-drive" \
    "$RESULT_DIR/snapshot-before-apply-night-before-release.pb" \
    "$RESULT_DIR/snapshot-before-apply-night-before-release.txt" \
    10
  release_restore_barrier before-apply
  instrument_no_restart \
    app.yinyuehe.M3ASnapshotSafetyDeviceTest#beforeApply_stalePlanCannotOverwriteReplacement
  copy_protocol_result snapshot-before-apply-verified
  wait_for_snapshot_ids \
    "demo:night-drive" \
    "$RESULT_DIR/snapshot-before-apply-after-release.pb" \
    "$RESULT_DIR/snapshot-before-apply-after-release.txt" \
    10
  local after_release_ids
  after_release_ids="$(decoded_media_ids "$RESULT_DIR/snapshot-before-apply-after-release.txt")"
  test "$after_release_ids" = "demo:night-drive" \
    || die "stale morning restore overwrote the night replacement"

  reset_case_state
  force_stop_app
  printf '\200' > "$RESULT_DIR/snapshot-corrupt-input.pb"
  install_private_file_atomically "$RESULT_DIR/snapshot-corrupt-input.pb" "$M3A_SNAPSHOT_PATH"
  copy_private_file "$M3A_SNAPSHOT_PATH" "$RESULT_DIR/snapshot-corrupt-injected.pb"
  cmp -s "$RESULT_DIR/snapshot-corrupt-input.pb" "$RESULT_DIR/snapshot-corrupt-injected.pb" \
    || die "corrupt 0x80 snapshot injection was not exact"
  instrument app.yinyuehe.M3ASnapshotSafetyDeviceTest#corruptBytes_recoverEmptyThenPersistNewQueue
  copy_protocol_result snapshot-corrupt-recovered
  wait_for_snapshot_ids \
    "demo:night-drive" \
    "$RESULT_DIR/snapshot-corrupt-recovered.pb" \
    "$RESULT_DIR/snapshot-corrupt-recovered.txt" \
    10
  force_stop_app
  instrument app.yinyuehe.M3ASnapshotSafetyDeviceTest#corruptReplacement_survivesColdRestart
  copy_protocol_result snapshot-corrupt-restart

  reset_case_state
  force_stop_app
  printf 'schema_version: 99\nmedia_ids: "demo:morning-pulse"\ncurrent_index: 0\n' \
    > "$RESULT_DIR/snapshot-schema99-input.txt"
  "$PROTOC" \
    --proto_path="$M3A_REPO_ROOT/core/data/src/main/proto" \
    --encode=app.yinyuehe.core.data.playback.proto.PlaybackSnapshotProto \
    "$M3A_REPO_ROOT/core/data/src/main/proto/playback_snapshot.proto" \
    < "$RESULT_DIR/snapshot-schema99-input.txt" \
    > "$RESULT_DIR/snapshot-schema99-input.pb"
  install_private_file_atomically "$RESULT_DIR/snapshot-schema99-input.pb" "$M3A_SNAPSHOT_PATH"
  copy_private_file "$M3A_SNAPSHOT_PATH" "$RESULT_DIR/snapshot-schema99-before.pb"
  cmp -s "$RESULT_DIR/snapshot-schema99-input.pb" "$RESULT_DIR/snapshot-schema99-before.pb" \
    || die "schema99 snapshot injection was not exact"
  start_playback_service | tee "$RESULT_DIR/snapshot-schema99-service-start.txt"
  instrument_no_restart app.yinyuehe.M3ASnapshotSafetyDeviceTest#schema99_coldStartRemainsEmpty
  copy_protocol_result snapshot-schema99
  stop_playback_service "$RESULT_DIR/snapshot-schema99"
  copy_private_file "$M3A_SNAPSHOT_PATH" "$RESULT_DIR/snapshot-schema99-after.pb"
  cmp -s "$RESULT_DIR/snapshot-schema99-before.pb" "$RESULT_DIR/snapshot-schema99-after.pb" \
    || die "schema99 bytes changed across cold start and onDestroy"

  local before_read_hash schema99_hash
  before_read_hash="$(sha256_file "$before_read_v1")"
  schema99_hash="$(sha256_file "$RESULT_DIR/snapshot-schema99-before.pb")"
  {
    printf 'beforeReadV1Sha256=%s\n' "$before_read_hash"
    printf 'beforeReadBlockedSha256=%s\n' "$(sha256_file "$before_read_blocked")"
    printf 'beforeReadDestroyedSha256=%s\n' "$(sha256_file "$before_read_destroyed")"
    printf 'beforeApplyAfterReleaseIds=%s\n' "$after_release_ids"
    printf 'corruptInputSha256=%s\n' "$(sha256_file "$RESULT_DIR/snapshot-corrupt-input.pb")"
    printf 'corruptRecoveredSha256=%s\n' "$(sha256_file "$RESULT_DIR/snapshot-corrupt-recovered.pb")"
    printf 'schema99BeforeSha256=%s\n' "$schema99_hash"
    printf 'schema99AfterSha256=%s\n' "$(sha256_file "$RESULT_DIR/snapshot-schema99-after.pb")"
  } > "$RESULT_DIR/snapshot-safety-host-evidence.result"
  printf 'M3A_CASE_SNAPSHOT_SAFETY_PASS beforeReadSha=%s staleIds=%s schema99Sha=%s\n' \
    "$before_read_hash" "$after_release_ids" "$schema99_hash"
}

run_reconnect_case() {
  local generation_one_private="$M3A_PROTOCOL_DIR/reconnect-generation-1.result"
  local pre_kill_ready_private="$M3A_PROTOCOL_DIR/reconnect-pre-kill-ready.result"
  local disconnected_private="$M3A_PROTOCOL_DIR/reconnect-disconnected.result"
  local generation_two_private="$M3A_PROTOCOL_DIR/reconnect-generation-2.result"
  local post_reconnect_ready_private="$M3A_PROTOCOL_DIR/reconnect-post-reconnect-ready.result"
  local live_private="$M3A_PROTOCOL_DIR/reconnect-live.result"

  reset_case_state
  instrument app.yinyuehe.M3AReconnectDeviceTest#setup_persistsMorningSnapshot
  copy_protocol_result reconnect-setup
  wait_for_snapshot_ids \
    "demo:morning-pulse" \
    "$RESULT_DIR/reconnect-setup-morning.pb" \
    "$RESULT_DIR/reconnect-setup-morning.txt" \
    10
  force_stop_app

  adb_serial shell am start -W -n "$M3A_PROBE_ACTIVITY_COMPONENT" \
    | tee "$RESULT_DIR/reconnect-probe-launch.txt"
  wait_for_private_files 15 "$generation_one_private" "$pre_kill_ready_private"
  copy_private_file "$generation_one_private" "$RESULT_DIR/reconnect-generation-1.result"
  copy_private_file "$pre_kill_ready_private" "$RESULT_DIR/reconnect-pre-kill-ready.result"

  local main_pid_before probe_pid_before marker_probe_pid controller_identity
  main_pid_before="$(single_process_pid "$M3A_PACKAGE")"
  probe_pid_before="$(single_process_pid "$M3A_PACKAGE:m3a_controller")"
  test "$main_pid_before" != "$probe_pid_before" || die "main and probe PIDs were identical"
  marker_probe_pid="$(protocol_value "$RESULT_DIR/reconnect-generation-1.result" probePid)"
  controller_identity="$(protocol_value "$RESULT_DIR/reconnect-generation-1.result" controllerIdentity)"
  test "$marker_probe_pid" = "$probe_pid_before" || die "generation 1 marker PID was not probe PID"
  test "$(protocol_value "$RESULT_DIR/reconnect-generation-1.result" connectedGeneration)" = "1"
  test "$(protocol_value "$RESULT_DIR/reconnect-pre-kill-ready.result" connectedGeneration)" = "1"
  test "$(protocol_value "$RESULT_DIR/reconnect-pre-kill-ready.result" mediaIds)" = \
    "demo:morning-pulse"
  test "$(protocol_value "$RESULT_DIR/reconnect-generation-1.result" processName)" = \
    "$M3A_PACKAGE:m3a_controller"
  wait_for_snapshot_ids \
    "demo:morning-pulse" \
    "$RESULT_DIR/reconnect-pre-kill-morning.pb" \
    "$RESULT_DIR/reconnect-pre-kill-morning.txt" \
    10
  adb_serial shell ps -A \
    | tr -d '\r' \
    | grep -E "(^|[[:space:]])($main_pid_before|$probe_pid_before)([[:space:]]|$)" \
    > "$RESULT_DIR/reconnect-processes-before-kill.txt"

  {
    printf 'validatedMainPid=%s\n' "$main_pid_before"
    printf 'validatedProbePid=%s\n' "$probe_pid_before"
    adb_serial shell run-as "$M3A_PACKAGE" kill -9 "$main_pid_before"
    printf 'killExit=0\n'
  } > "$RESULT_DIR/reconnect-kill-main.result"
  local probe_pid_immediately_after
  probe_pid_immediately_after="$(single_process_pid "$M3A_PACKAGE:m3a_controller")"
  test "$probe_pid_immediately_after" = "$probe_pid_before" \
    || die "probe PID changed immediately after killing only main PID"

  wait_for_private_files \
    15 \
    "$disconnected_private" \
    "$generation_two_private" \
    "$post_reconnect_ready_private"
  copy_private_file "$disconnected_private" "$RESULT_DIR/reconnect-disconnected.result"
  copy_private_file "$generation_two_private" "$RESULT_DIR/reconnect-generation-2.result"
  copy_private_file \
    "$post_reconnect_ready_private" \
    "$RESULT_DIR/reconnect-post-reconnect-ready.result"

  local main_pid_after probe_pid_after generation_two_identity
  main_pid_after="$(single_process_pid "$M3A_PACKAGE")"
  probe_pid_after="$(single_process_pid "$M3A_PACKAGE:m3a_controller")"
  test "$main_pid_after" != "$main_pid_before" || die "main process PID did not change after SIGKILL"
  test "$main_pid_after" != "$probe_pid_after" || die "new main and probe PIDs were identical"
  test "$probe_pid_after" = "$probe_pid_before" || die "probe PID changed during reconnect"
  generation_two_identity="$(protocol_value "$RESULT_DIR/reconnect-generation-2.result" controllerIdentity)"
  test "$(protocol_value "$RESULT_DIR/reconnect-generation-2.result" connectedGeneration)" = "2"
  test "$generation_two_identity" = "$controller_identity" \
    || die "production PlaybackController identity changed across reconnect"
  test "$(protocol_value "$RESULT_DIR/reconnect-generation-2.result" probePid)" = "$probe_pid_before"
  test "$(protocol_value "$RESULT_DIR/reconnect-disconnected.result" disconnectEdges)" -ge 1
  test "$(protocol_value "$RESULT_DIR/reconnect-post-reconnect-ready.result" mediaIds)" = \
    "demo:morning-pulse"
  test "$(protocol_value "$RESULT_DIR/reconnect-post-reconnect-ready.result" connectedGeneration)" = "2"

  instrument_no_restart app.yinyuehe.M3AReconnectDeviceTest#probeReconnectedWithoutStaleState
  copy_protocol_result reconnect-final
  local probe_pid_after_final main_pid_after_final
  probe_pid_after_final="$(single_process_pid "$M3A_PACKAGE:m3a_controller")"
  main_pid_after_final="$(single_process_pid "$M3A_PACKAGE")"
  test "$probe_pid_after_final" = "$probe_pid_before" \
    || die "probe PID changed during no-restart final assertion"
  test "$main_pid_after_final" = "$main_pid_after" \
    || die "no-restart instrumentation restarted the main process"

  wait_for_private_result_values \
    "$live_private" \
    10 \
    connectedGeneration 2 \
    controllerIdentity "$controller_identity" \
    probePid "$probe_pid_before" \
    mediaIds "demo:night-drive" \
    currentIndex 0
  copy_private_file "$live_private" "$RESULT_DIR/reconnect-live-after-night.result"
  local generation_two_emissions live_emissions
  generation_two_emissions="$(protocol_value "$RESULT_DIR/reconnect-generation-2.result" connectedEmissionCount)"
  live_emissions="$(protocol_value "$RESULT_DIR/reconnect-live-after-night.result" connectedEmissionCount)"
  test "$live_emissions" -gt "$generation_two_emissions" \
    || die "ordinary connected callbacks did not produce proof without incrementing generation"

  wait_for_snapshot_ids \
    "demo:night-drive" \
    "$RESULT_DIR/reconnect-post-night.pb" \
    "$RESULT_DIR/reconnect-post-night.txt" \
    10
  sleep 1
  copy_private_file "$M3A_SNAPSHOT_PATH" "$RESULT_DIR/reconnect-settled-night.pb"
  decode_snapshot \
    "$RESULT_DIR/reconnect-settled-night.pb" \
    "$RESULT_DIR/reconnect-settled-night.txt"
  test "$(decoded_media_ids "$RESULT_DIR/reconnect-settled-night.txt")" = "demo:night-drive" \
    || die "settled reconnect snapshot was not Demo night"
  cmp -s "$RESULT_DIR/reconnect-post-night.pb" "$RESULT_DIR/reconnect-settled-night.pb" \
    || die "stale pre-kill snapshot overwrote the settled reconnect replacement"
  if cmp -s "$RESULT_DIR/reconnect-pre-kill-morning.pb" "$RESULT_DIR/reconnect-post-night.pb"; then
    die "post-reconnect full replacement did not change Proto bytes"
  fi

  local pre_hash post_hash
  pre_hash="$(sha256_file "$RESULT_DIR/reconnect-pre-kill-morning.pb")"
  post_hash="$(sha256_file "$RESULT_DIR/reconnect-post-night.pb")"
  {
    printf 'mainPidBefore=%s\n' "$main_pid_before"
    printf 'mainPidAfter=%s\n' "$main_pid_after"
    printf 'probePidBefore=%s\n' "$probe_pid_before"
    printf 'probePidAfter=%s\n' "$probe_pid_after_final"
    printf 'controllerIdentityGeneration1=%s\n' "$controller_identity"
    printf 'controllerIdentityGeneration2=%s\n' "$generation_two_identity"
    printf 'disconnectEdges=%s\n' \
      "$(protocol_value "$RESULT_DIR/reconnect-disconnected.result" disconnectEdges)"
    printf 'generationTwoEmissionCount=%s\n' "$generation_two_emissions"
    printf 'liveNightEmissionCount=%s\n' "$live_emissions"
    printf 'preKillMorningSha256=%s\n' "$pre_hash"
    printf 'postReconnectNightSha256=%s\n' "$post_hash"
    printf 'settledNightSha256=%s\n' \
      "$(sha256_file "$RESULT_DIR/reconnect-settled-night.pb")"
  } > "$RESULT_DIR/reconnect-host-evidence.result"
  printf 'M3A_CASE_RECONNECT_PASS mainPid=%s->%s probePid=%s identity=%s generation=1->2 emissions=%s->%s preSha=%s postSha=%s\n' \
    "$main_pid_before" "$main_pid_after" "$probe_pid_before" "$controller_identity" \
    "$generation_two_emissions" "$live_emissions" "$pre_hash" "$post_hash"
}

run_requested_cases() {
  case "${SELECTED_CASE:-all}" in
    position) run_position_case ;;
    queue) run_queue_case ;;
    permission) run_permission_case ;;
    snapshot-safety) run_snapshot_safety_case ;;
    reconnect) run_reconnect_case ;;
    all)
      run_position_case
      run_queue_case
      run_permission_case
      run_snapshot_safety_case
      run_reconnect_case
      ;;
  esac
}

run_requested_cases
MAIN_SUCCEEDED=true
