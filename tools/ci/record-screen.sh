#!/usr/bin/env bash
# Screen-records the connected Android emulator while instrumented tests run.
#
# Usage:
#   tools/ci/record-screen.sh start <label>
#   tools/ci/record-screen.sh stop  <label>
#
# `screenrecord` caps a single recording at 3 minutes, so `start` spawns a
# background loop that records back-to-back segments; `stop` interrupts the
# in-flight segment, pulls everything off the device and (if ffmpeg is present)
# concatenates the segments into one <label>.mp4.
#
# Recording is best-effort by design: this script never fails the test run.
# A missing video is a worse debugging experience, not a red build.
set -uo pipefail

OUT_DIR=${OUT_DIR:-build/ui-test-video}
STATE_DIR=${STATE_DIR:-build/ui-test-video/.state}
SEGMENT_SECONDS=${SEGMENT_SECONDS:-170}   # screenrecord's own cap is 180 s
BIT_RATE=${BIT_RATE:-4000000}
# Optional WxH passed through to `screenrecord --size`. Left empty by default:
# an unsupported size makes the encoder refuse to start on some system images,
# and the emulator's native resolution is fine.
RECORD_SIZE=${RECORD_SIZE:-}

log() { echo "[record-screen] $*"; }

device_dir_for() { echo "/sdcard/skyward-ui-video-$1"; }
pid_file_for() { echo "$STATE_DIR/$1.pid"; }

# The background loop. Re-invokes this script so `start` can return immediately.
run_loop() {
  local label=$1
  local device_dir
  device_dir=$(device_dir_for "$label")
  local size_args=()
  [ -n "$RECORD_SIZE" ] && size_args=(--size "$RECORD_SIZE")

  local index=0
  local consecutive_failures=0
  while :; do
    local segment
    segment=$(printf '%s/segment-%03d.mp4' "$device_dir" "$index")
    # ${a[@]+…}: expanding an empty array under `set -u` is an error on the
    # bash 3.2 a macOS dev box still ships.
    if adb shell screenrecord --bit-rate "$BIT_RATE" ${size_args[@]+"${size_args[@]}"} \
      --time-limit "$SEGMENT_SECONDS" "$segment"; then
      consecutive_failures=0
    else
      # A recorder that dies instantly (no encoder, device gone, storage full)
      # would otherwise spin this loop until the job hits its timeout.
      consecutive_failures=$((consecutive_failures + 1))
      if [ "$consecutive_failures" -ge 3 ]; then
        log "screenrecord failed 3 times in a row; giving up on '$label'"
        return 0
      fi
    fi
    index=$((index + 1))
    sleep 1
  done
}

start() {
  local label=$1
  local device_dir
  device_dir=$(device_dir_for "$label")

  mkdir -p "$STATE_DIR" "$OUT_DIR"
  adb wait-for-device || { log "no device; not recording"; return 0; }

  if ! adb shell 'command -v screenrecord || which screenrecord' >/dev/null 2>&1; then
    log "screenrecord is not available on this system image; not recording"
    return 0
  fi

  adb shell "rm -rf $device_dir; mkdir -p $device_dir" >/dev/null 2>&1
  nohup "$0" __loop "$label" >"$STATE_DIR/$label.log" 2>&1 &
  echo $! >"$(pid_file_for "$label")"
  log "recording '$label' (pid $!, ${SEGMENT_SECONDS}s segments)"
}

stop() {
  local label=$1
  local device_dir pid_file
  device_dir=$(device_dir_for "$label")
  pid_file=$(pid_file_for "$label")

  # 1. Stop the loop first, so it cannot start a fresh segment while we are
  #    finalising the current one.
  if [ -f "$pid_file" ]; then
    kill "$(cat "$pid_file")" 2>/dev/null
    rm -f "$pid_file"
  fi

  # 2. SIGINT (not SIGKILL) the on-device recorder: screenrecord writes the MP4
  #    moov atom on interrupt, and a killed segment is an unplayable file.
  adb shell 'pkill -INT screenrecord || pkill -2 screenrecord || killall -INT screenrecord' \
    >/dev/null 2>&1
  sleep 4  # let the muxer flush

  # 3. Any local `adb shell screenrecord` client left holding the connection.
  pkill -f "adb shell screenrecord" >/dev/null 2>&1

  local segments_dir="$OUT_DIR/$label-segments"
  rm -rf "$segments_dir"
  mkdir -p "$segments_dir"
  adb pull "$device_dir/." "$segments_dir" >/dev/null 2>&1
  adb shell "rm -rf $device_dir" >/dev/null 2>&1

  # Drop empty segments — the interrupted one can land at 0 bytes.
  find "$segments_dir" -name '*.mp4' -size -1k -delete 2>/dev/null

  local segments=()
  while IFS= read -r file; do segments+=("$file"); done < <(find "$segments_dir" -name '*.mp4' | sort)

  if [ ${#segments[@]} -eq 0 ]; then
    log "no video captured for '$label'"
    rmdir "$segments_dir" 2>/dev/null
    return 0
  fi

  local output="$OUT_DIR/$label.mp4"
  if [ ${#segments[@]} -eq 1 ]; then
    mv "${segments[0]}" "$output"
    rmdir "$segments_dir" 2>/dev/null
  elif command -v ffmpeg >/dev/null 2>&1; then
    local list="$STATE_DIR/$label-concat.txt"
    : >"$list"
    local segment
    for segment in "${segments[@]}"; do
      printf "file '%s'\n" "$(cd "$(dirname "$segment")" && pwd)/$(basename "$segment")" >>"$list"
    done
    if ffmpeg -y -loglevel error -f concat -safe 0 -i "$list" -c copy "$output"; then
      rm -rf "$segments_dir"
    else
      log "ffmpeg concat failed; keeping ${#segments[@]} raw segments"
    fi
  else
    log "ffmpeg not found; keeping ${#segments[@]} raw segments"
  fi

  [ -f "$output" ] && log "wrote $output ($(du -h "$output" | cut -f1))"
  return 0
}

case "${1:-}" in
  start) start "${2:?usage: record-screen.sh start <label>}" ;;
  stop) stop "${2:?usage: record-screen.sh stop <label>}" ;;
  __loop) run_loop "${2:?}" ;;   # internal
  *)
    echo "usage: $0 {start|stop} <label>" >&2
    exit 2
    ;;
esac
