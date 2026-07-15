#!/usr/bin/env bash

set -euo pipefail

M3A_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
M3A_REPO_ROOT="$(cd "$M3A_LIB_DIR/../.." && pwd -P)"
M3A_PACKAGE="app.yinyuehe"
M3A_RUNNER="app.yinyuehe.test/androidx.test.runner.AndroidJUnitRunner"
M3A_PROTOCOL_DIR="files/m3a-device"
M3A_SNAPSHOT_PATH="files/datastore/playback_snapshot.pb"
M3A_SERVICE_COMPONENT="app.yinyuehe/app.yinyuehe.core.player.service.PlaybackService"
M3A_MAIN_ACTIVITY_COMPONENT="app.yinyuehe/app.yinyuehe.MainActivity"
M3A_PROBE_ACTIVITY_COMPONENT="app.yinyuehe/app.yinyuehe.M3AControllerProbeActivity"
M3A_REMOTE_STAGING_PATH="/data/local/tmp/m3a-playback-snapshot-$$.pb"
M3A_PRIVATE_TEMPORARY_PATH="files/datastore/.playback_snapshot.pb.m3a-$$"
M3A_INSTRUMENTATION_INVOCATION=0
M3A_HOST_TIMEOUT_SECONDS=90
M3A_FIXTURE_DISPLAY_NAME="yinyuehe_m3a_35s.wav"
M3A_FIXTURE_OWNER_PACKAGE="$M3A_PACKAGE"
M3A_FIXTURE_CONTENT_URI="content://media/external/audio/media"

die() {
  printf 'M3A_ERROR %s\n' "$*" >&2
  exit 1
}

adb_serial() {
  "${ANDROID_HOME:?ANDROID_HOME is required}/platform-tools/adb" -s "$ANDROID_SERIAL" "$@"
}

run_with_host_timeout() {
  local timeout_seconds="$1"
  local output_file="$2"
  shift 2
  case "$timeout_seconds" in
    ''|*[!0-9]*) die "host timeout must be a positive integer" ;;
  esac
  test "$timeout_seconds" -gt 0 || die "host timeout must be greater than zero"
  test "$#" -gt 0 || die "host timeout requires a command"

  : > "$output_file"
  local previous_int_trap previous_term_trap
  previous_int_trap="$(trap -p INT)"
  previous_term_trap="$(trap -p TERM)"

  "$@" > "$output_file" 2>&1 &
  local command_pid=$!
  local interrupted_status=0
  trap 'interrupted_status=130; kill -TERM "$command_pid" 2>/dev/null || true' INT
  trap 'interrupted_status=143; kill -TERM "$command_pid" 2>/dev/null || true' TERM

  local deadline=$((SECONDS + timeout_seconds))
  local timed_out=false
  while kill -0 "$command_pid" 2>/dev/null; do
    if test "$interrupted_status" -ne 0; then
      break
    fi
    if test "$SECONDS" -ge "$deadline"; then
      timed_out=true
      break
    fi
    sleep 0.1
  done

  if test "$timed_out" = true || test "$interrupted_status" -ne 0; then
    kill -TERM "$command_pid" 2>/dev/null || true
    local kill_deadline=$((SECONDS + 2))
    while kill -0 "$command_pid" 2>/dev/null && test "$SECONDS" -lt "$kill_deadline"; do
      sleep 0.1
    done
    if kill -0 "$command_pid" 2>/dev/null; then
      kill -KILL "$command_pid" 2>/dev/null || true
    fi
  fi

  local command_status=0
  if wait "$command_pid"; then
    command_status=0
  else
    command_status=$?
  fi

  local result_status="$command_status"
  if test "$timed_out" = true; then
    printf '\nM3A_HOST_TIMEOUT seconds=%s localPid=%s\n' \
      "$timeout_seconds" "$command_pid" >> "$output_file"
    result_status=124
  elif test "$interrupted_status" -ne 0; then
    printf '\nM3A_HOST_INTERRUPTED exit=%s localPid=%s\n' \
      "$interrupted_status" "$command_pid" >> "$output_file"
    result_status="$interrupted_status"
  fi

  if test -n "$previous_int_trap"; then
    eval "$previous_int_trap"
  else
    trap - INT
  fi
  if test -n "$previous_term_trap"; then
    eval "$previous_term_trap"
  else
    trap - TERM
  fi
  return "$result_status"
}

require_host_environment() {
  test -n "${ANDROID_SERIAL:-}" || die "set exactly one explicit ANDROID_SERIAL"
  test -n "${JAVA_HOME:-}" || die "JAVA_HOME must point to JDK 17"
  test -x "$JAVA_HOME/bin/java" || die "JAVA_HOME/bin/java is not executable"
  local java_version
  java_version="$($JAVA_HOME/bin/java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version =/ { print $2; exit }')"
  test "$java_version" = "17" || die "JDK 17 required, got ${java_version:-unknown}"
  test -x "${ANDROID_HOME:?ANDROID_HOME is required}/platform-tools/adb" || die "adb not found"

  local matching_devices
  matching_devices="$("$ANDROID_HOME/platform-tools/adb" devices | awk -v serial="$ANDROID_SERIAL" '$1 == serial && $2 == "device" { count++ } END { print count + 0 }')"
  test "$matching_devices" = "1" || die "ANDROID_SERIAL must name exactly one healthy device"
  test "$(adb_serial get-state | tr -d '\r\n')" = "device" || die "device is not healthy"

  local boot_completed=""
  for _ in $(seq 1 120); do
    boot_completed="$(adb_serial shell getprop sys.boot_completed | tr -d '\r\n')"
    test "$boot_completed" = "1" && break
    sleep 1
  done
  test "$boot_completed" = "1" || die "device did not finish booting"
  M3A_API_LEVEL="$(adb_serial shell getprop ro.build.version.sdk | tr -d '\r\n')"
  test "$M3A_API_LEVEL" = "36" || die "API exactly 36 required, got $M3A_API_LEVEL"
  M3A_DEVICE_USER_ID="$(adb_serial shell am get-current-user | tr -d '\r\n')"
  test -n "$M3A_DEVICE_USER_ID" || die "unable to determine current Android user"
  export M3A_API_LEVEL M3A_DEVICE_USER_ID
}

permission_is_granted() {
  local permission="$1"
  adb_serial shell dumpsys package "$M3A_PACKAGE" \
    | tr -d '\r' \
    | awk -v expected="$permission:" \
        '$1 == expected && /granted=true/ { found=1 } END { exit !found }'
}

permission_is_denied() {
  ! permission_is_granted "$1"
}

grant_permission() {
  local permission="$1"
  adb_serial shell pm grant --user "$M3A_DEVICE_USER_ID" "$M3A_PACKAGE" "$permission"
  permission_is_granted "$permission" || die "permission grant did not stick: $permission"
}

revoke_permission() {
  local permission="$1"
  adb_serial shell pm revoke --user "$M3A_DEVICE_USER_ID" "$M3A_PACKAGE" "$permission"
  permission_is_denied "$permission" || die "permission revoke did not stick: $permission"
}

grant_and_verify_runtime_permissions() {
  grant_permission android.permission.READ_MEDIA_AUDIO
  grant_permission android.permission.POST_NOTIFICATIONS
}

install_debug_apks() {
  local app_apk="$M3A_REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
  local test_apk="$M3A_REPO_ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
  test -f "$app_apk" || die "missing debug APK: $app_apk"
  test -f "$test_apk" || die "missing instrumentation APK: $test_apk"
  adb_serial install -r -t "$app_apk"
  adb_serial install -r -t "$test_apk"
}

reset_case_state() {
  host_delete_fixture_rows
  adb_serial shell am force-stop "$M3A_PACKAGE"
  adb_serial shell pm clear --user "$M3A_DEVICE_USER_ID" "$M3A_PACKAGE" \
    | tr -d '\r' \
    | grep -qx 'Success'
  grant_and_verify_runtime_permissions
}

host_delete_fixture_rows() {
  local selection="_display_name = '$M3A_FIXTURE_DISPLAY_NAME' AND owner_package_name = '$M3A_FIXTURE_OWNER_PACKAGE'"
  local delete_output query_output normalized_delete normalized_query
  if ! delete_output="$(adb_serial shell \
      "content delete --uri $M3A_FIXTURE_CONTENT_URI --where \"$selection\"" 2>&1)"; then
    printf 'M3A_FIXTURE_DELETE_FAILED selection=%s output=%s\n' \
      "$selection" "$delete_output" >&2
    return 1
  fi
  normalized_delete="$(printf '%s\n' "$delete_output" | tr -d '\r\n')"
  if test -n "$normalized_delete"; then
    printf 'M3A_FIXTURE_DELETE_FAILED selection=%s output=%s\n' \
      "$selection" "$normalized_delete" >&2
    return 1
  fi
  if ! query_output="$(adb_serial shell \
      "content query --uri $M3A_FIXTURE_CONTENT_URI --projection _id:_display_name:owner_package_name --where \"$selection\"" 2>&1)"; then
    printf 'M3A_FIXTURE_QUERY_FAILED selection=%s output=%s\n' \
      "$selection" "$query_output" >&2
    return 1
  fi
  normalized_query="$(printf '%s\n' "$query_output" | tr -d '\r')"
  if test "$normalized_query" != "No result found."; then
    printf 'M3A_FIXTURE_RESIDUE selection=%s output=%s\n' \
      "$selection" "$normalized_query" >&2
    return 1
  fi
  printf 'M3A_FIXTURE_DELETE_SAFE displayName=%s owner=%s remaining=0\n' \
    "$M3A_FIXTURE_DISPLAY_NAME" "$M3A_FIXTURE_OWNER_PACKAGE"
}

force_stop_app() {
  adb_serial shell am force-stop "$M3A_PACKAGE"
}

start_playback_service() {
  local output normalized
  output="$(adb_serial shell am startservice \
    --user "$M3A_DEVICE_USER_ID" \
    -n "$M3A_SERVICE_COMPONENT" 2>&1)" \
    || die "unable to start PlaybackService"
  normalized="$(printf '%s\n' "$output" | tr -d '\r')"
  printf '%s\n' "$normalized" | grep -Eq '^Starting service:' \
    || die "PlaybackService did not report a successful explicit start"
  printf '%s\n' "$output"
}

playback_service_is_registered() {
  adb_serial shell dumpsys activity services "$M3A_PACKAGE" \
    | tr -d '\r' \
    | grep -Eq '^[[:space:]]*\* ServiceRecord\{.*PlaybackService'
}

stop_playback_service() {
  local evidence_prefix="$1"
  local output command_status normalized deadline
  set +e
  output="$(adb_serial shell am stopservice \
    --user "$M3A_DEVICE_USER_ID" \
    -n "$M3A_SERVICE_COMPONENT" 2>&1)"
  command_status=$?
  set -e
  {
    printf 'commandExit=%s\n' "$command_status"
    printf '%s\n' "$output"
  } | tee "$evidence_prefix-service-stop.txt"
  normalized="$(printf '%s\n' "$output" | tr -d '\r')"
  if test "$command_status" -ne 0 && test "$command_status" -ne 255; then
    die "unexpected stopservice exit $command_status"
  fi
  printf '%s\n' "$normalized" | grep -Fxq 'Service stopped' \
    || die "PlaybackService did not report a successful explicit stop"

  deadline=$((SECONDS + 5))
  while test "$SECONDS" -lt "$deadline" && playback_service_is_registered; do
    sleep 0.1
  done
  adb_serial shell dumpsys activity services "$M3A_PACKAGE" \
    > "$evidence_prefix-services-after-stop.txt"
  if playback_service_is_registered; then
    die "PlaybackService remained registered after explicit stop"
  fi
}

safe_test_name() {
  printf '%s' "$1" | tr '#./:' '____'
}

run_instrumentation() {
  local restart_mode="$1"
  local test_name="$2"
  shift 2
  M3A_INSTRUMENTATION_INVOCATION=$((M3A_INSTRUMENTATION_INVOCATION + 1))
  local invocation
  invocation="$(printf '%03d' "$M3A_INSTRUMENTATION_INVOCATION")"
  local output_file="$RESULT_DIR/$invocation-$(safe_test_name "$test_name").instrumentation.txt"
  local -a command=("$ANDROID_HOME/platform-tools/adb" -s "$ANDROID_SERIAL" shell am instrument)
  if test "$restart_mode" = "no-restart"; then
    command+=(--no-restart)
  fi
  command+=(-w -r -e m3aHostDriven true -e class "$test_name")
  while test "$#" -gt 0; do
    test "$#" -ge 2 || die "instrumentation arguments must be key/value pairs"
    command+=(-e "$1" "$2")
    shift 2
  done
  command+=("$M3A_RUNNER")

  local status=0
  run_with_host_timeout \
    "$M3A_HOST_TIMEOUT_SECONDS" "$output_file" "${command[@]}" || status=$?
  cat "$output_file"
  local normalized
  normalized="$(tr -d '\r' < "$output_file")"
  if test "$status" -ne 0; then
    printf 'M3A_ERROR instrumentation exited %s: %s\n' "$status" "$test_name" >&2
    return "$status"
  fi
  printf '%s\n' "$normalized" | grep -Eq '^OK \([1-9][0-9]* tests?\)$' \
    || die "instrumentation did not report at least one passing test: $test_name"
  if printf '%s\n' "$normalized" | grep -Eiq 'skipped|assumption|FAILURES|INSTRUMENTATION_FAILED'; then
    die "instrumentation skipped or failed: $test_name"
  fi
}

instrument() {
  run_instrumentation restart "$@"
}

instrument_no_restart() {
  run_instrumentation no-restart "$@"
}

copy_private_file() {
  local private_path="$1"
  local output_path="$2"
  adb_serial exec-out run-as "$M3A_PACKAGE" cat "$private_path" > "$output_path"
  test -s "$output_path" || die "private file was empty: $private_path"
}

copy_protocol_result() {
  local marker="$1"
  copy_private_file "$M3A_PROTOCOL_DIR/$marker.result" "$RESULT_DIR/$marker.result"
}

protocol_value() {
  local file="$1"
  local key="$2"
  awk -F= -v expected="$key" '$1 == expected { print substr($0, index($0, "=") + 1); exit }' "$file"
}

decoded_media_ids() {
  local decoded_file="$1"
  awk '$1 == "media_ids:" { gsub(/"/, "", $2); ids = ids (ids ? "," : "") $2 } END { print ids }' \
    "$decoded_file"
}

decoded_field_or_default() {
  local decoded_file="$1"
  local field="$2"
  local default_value="$3"
  awk -v expected="$field:" -v fallback="$default_value" \
    '$1 == expected { print $2; found=1; exit } END { if (!found) print fallback }' \
    "$decoded_file"
}

decode_snapshot() {
  local input="$1"
  local output="$2"
  "$PROTOC" \
    --proto_path="$M3A_REPO_ROOT/core/data/src/main/proto" \
    --decode=app.yinyuehe.core.data.playback.proto.PlaybackSnapshotProto \
    "$M3A_REPO_ROOT/core/data/src/main/proto/playback_snapshot.proto" \
    < "$input" > "$output"
  test -s "$output" || die "decoded snapshot was empty"
}

sha256_file() {
  shasum -a 256 "$1" | awk '{ print $1 }'
}

wait_for_private_file() {
  local private_path="$1"
  local timeout_seconds="$2"
  local deadline=$((SECONDS + timeout_seconds))
  while test "$SECONDS" -lt "$deadline"; do
    if adb_serial shell run-as "$M3A_PACKAGE" test -f "$private_path"; then
      return 0
    fi
    sleep 0.1
  done
  die "timed out waiting for private marker: $private_path"
}

wait_for_private_files() {
  local timeout_seconds="$1"
  shift
  local deadline=$((SECONDS + timeout_seconds))
  while test "$SECONDS" -lt "$deadline"; do
    local all_present=true
    local private_path
    for private_path in "$@"; do
      if ! adb_serial shell run-as "$M3A_PACKAGE" test -f "$private_path"; then
        all_present=false
        break
      fi
    done
    test "$all_present" = true && return 0
    sleep 0.1
  done
  die "timed out waiting for private markers: $*"
}

wait_for_private_result_values() {
  local private_path="$1"
  local timeout_seconds="$2"
  shift 2
  test "$#" -gt 0 && test $(( $# % 2 )) -eq 0 \
    || die "result value expectations must be key/value pairs"
  local temporary
  temporary="$(mktemp "${TMPDIR:-/tmp}/m3a-result.XXXXXX")"
  local deadline=$((SECONDS + timeout_seconds))
  while test "$SECONDS" -lt "$deadline"; do
    if adb_serial exec-out run-as "$M3A_PACKAGE" cat "$private_path" > "$temporary" 2>/dev/null; then
      local matches=true
      local -a expectations=("$@")
      local index
      for ((index = 0; index < ${#expectations[@]}; index += 2)); do
        if test "$(protocol_value "$temporary" "${expectations[index]}")" != \
            "${expectations[index + 1]}"; then
          matches=false
          break
        fi
      done
      if test "$matches" = true; then
        rm -f "$temporary"
        return 0
      fi
    fi
    sleep 0.1
  done
  rm -f "$temporary"
  die "timed out waiting for private result values: $private_path"
}

single_process_pid() {
  local process_name="$1"
  local output
  output="$(adb_serial shell pidof "$process_name" 2>/dev/null | tr -d '\r')" || output=""
  local -a pids
  read -r -a pids <<< "$output"
  test "${#pids[@]}" -eq 1 || die "expected one PID for $process_name, got: ${output:-none}"
  [[ "${pids[0]}" =~ ^[0-9]+$ ]] || die "non-numeric PID for $process_name: ${pids[0]}"
  printf '%s\n' "${pids[0]}"
}

wait_for_snapshot_ids() {
  local expected_ids="$1"
  local output_proto="$2"
  local output_decoded="$3"
  local timeout_seconds="$4"
  local temporary_proto temporary_decoded
  temporary_proto="$(mktemp "${TMPDIR:-/tmp}/m3a-snapshot.XXXXXX")"
  temporary_decoded="$(mktemp "${TMPDIR:-/tmp}/m3a-snapshot-text.XXXXXX")"
  local deadline=$((SECONDS + timeout_seconds))
  while test "$SECONDS" -lt "$deadline"; do
    if adb_serial exec-out run-as "$M3A_PACKAGE" cat "$M3A_SNAPSHOT_PATH" \
        > "$temporary_proto" 2>/dev/null && test -s "$temporary_proto" && \
        "$PROTOC" \
          --proto_path="$M3A_REPO_ROOT/core/data/src/main/proto" \
          --decode=app.yinyuehe.core.data.playback.proto.PlaybackSnapshotProto \
          "$M3A_REPO_ROOT/core/data/src/main/proto/playback_snapshot.proto" \
          < "$temporary_proto" > "$temporary_decoded" 2>/dev/null && \
        test "$(decoded_media_ids "$temporary_decoded")" = "$expected_ids"; then
      cp "$temporary_proto" "$output_proto"
      cp "$temporary_decoded" "$output_decoded"
      rm -f "$temporary_proto" "$temporary_decoded"
      return 0
    fi
    sleep 0.1
  done
  rm -f "$temporary_proto" "$temporary_decoded"
  die "timed out waiting for snapshot ids: $expected_ids"
}

write_private_marker() {
  local private_path="$1"
  test "$private_path" = "$M3A_PROTOCOL_DIR/$(basename "$private_path")" \
    || die "private marker must stay below $M3A_PROTOCOL_DIR"
  adb_serial shell run-as "$M3A_PACKAGE" mkdir -p "$M3A_PROTOCOL_DIR"
  adb_serial shell run-as "$M3A_PACKAGE" touch "$private_path"
}

remove_private_protocol_files() {
  local removal_status=0
  adb_serial shell rm -f "$M3A_REMOTE_STAGING_PATH" \
    >/dev/null 2>&1 || removal_status=1
  adb_serial shell run-as "$M3A_PACKAGE" rm -f "$M3A_PRIVATE_TEMPORARY_PATH" \
    >/dev/null 2>&1 || removal_status=1
  adb_serial shell run-as "$M3A_PACKAGE" rm -rf "$M3A_PROTOCOL_DIR" \
    >/dev/null 2>&1 || removal_status=1
  adb_serial shell test ! -e "$M3A_REMOTE_STAGING_PATH" \
    >/dev/null 2>&1 || removal_status=1
  adb_serial shell run-as "$M3A_PACKAGE" test ! -e "$M3A_PRIVATE_TEMPORARY_PATH" \
    >/dev/null 2>&1 || removal_status=1
  adb_serial shell run-as "$M3A_PACKAGE" test ! -e "$M3A_PROTOCOL_DIR" \
    >/dev/null 2>&1 || removal_status=1
  return "$removal_status"
}

install_private_file_atomically() {
  local host_file="$1"
  local private_path="$2"
  test -f "$host_file" || die "host injection file missing: $host_file"
  test "$private_path" = "$M3A_SNAPSHOT_PATH" \
    || die "M3-A private injection is restricted to the playback snapshot"
  adb_serial shell rm -f "$M3A_REMOTE_STAGING_PATH"
  adb_serial shell run-as "$M3A_PACKAGE" rm -f "$M3A_PRIVATE_TEMPORARY_PATH"
  adb_serial push "$host_file" "$M3A_REMOTE_STAGING_PATH" >/dev/null
  adb_serial shell run-as "$M3A_PACKAGE" mkdir -p files/datastore
  adb_serial shell run-as "$M3A_PACKAGE" cp \
    "$M3A_REMOTE_STAGING_PATH" \
    "$M3A_PRIVATE_TEMPORARY_PATH"
  adb_serial shell run-as "$M3A_PACKAGE" mv "$M3A_PRIVATE_TEMPORARY_PATH" "$private_path"
  adb_serial shell rm -f "$M3A_REMOTE_STAGING_PATH"
  adb_serial shell test ! -e "$M3A_REMOTE_STAGING_PATH"
  adb_serial shell run-as "$M3A_PACKAGE" test ! -e "$M3A_PRIVATE_TEMPORARY_PATH"
}

prepare_restore_barrier() {
  local key="$1"
  case "$key" in
    before-read|before-apply) ;;
    *) die "unknown restore barrier: $key" ;;
  esac
  adb_serial shell run-as "$M3A_PACKAGE" mkdir -p "$M3A_PROTOCOL_DIR"
  adb_serial shell run-as "$M3A_PACKAGE" rm -f \
    "$M3A_PROTOCOL_DIR/hold-$key" \
    "$M3A_PROTOCOL_DIR/$key-blocked" \
    "$M3A_PROTOCOL_DIR/$key-cancelled" \
    "$M3A_PROTOCOL_DIR/release-$key"
  adb_serial shell run-as "$M3A_PACKAGE" touch "$M3A_PROTOCOL_DIR/hold-$key"
}

release_restore_barrier() {
  local key="$1"
  adb_serial shell run-as "$M3A_PACKAGE" touch "$M3A_PROTOCOL_DIR/release-$key"
}
