#!/usr/bin/env bash
# Runs the Android instrumented tests on the connected emulator, one flavour at
# a time, with a screen recording and a logcat dump per flavour.
#
# Invoked as the `script:` of reactivecircus/android-emulator-runner, i.e. with
# an emulator already booted and `adb` on PATH. Run it from the repo root.
#
# §17.5 requires the instrumented smoke tests to run against BOTH flavours, so a
# failure in one must not skip the other: the exit status is collected across
# flavours and reported at the end.
set -uo pipefail

FLAVOURS=${FLAVOURS:-foss play}
OUT_DIR=${OUT_DIR:-build/ui-test-video}
APPLICATION_ID=${APPLICATION_ID:-dev.fritze.skyward}
READY_TIMEOUT_SECONDS=${READY_TIMEOUT_SECONDS:-180}
export OUT_DIR

log() { echo "[run-ui-tests] $*"; }

mkdir -p "$OUT_DIR"

# `sys.boot_completed` is not on its own proof that the device can be driven:
# the emulator-runner action has already seen it flip to 1 and then failed on
# the very next adb call because the system services were not registered yet.
# Wait for the package manager to actually answer before installing anything —
# otherwise a half-ready device surfaces as an inscrutable Gradle install
# failure partway through the first flavour, rather than as what it is.
await_device_ready() {
  local deadline=$((SECONDS + READY_TIMEOUT_SECONDS))

  adb wait-for-device || { log "adb wait-for-device failed"; return 1; }

  while [ "$SECONDS" -lt "$deadline" ]; do
    if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')" = "1" ] &&
      adb shell pm path android >/dev/null 2>&1; then
      log "device ready after $((SECONDS))s"
      return 0
    fi
    sleep 2
  done

  log "device never became ready within ${READY_TIMEOUT_SECONDS}s" \
    "(sys.boot_completed=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n'));" \
    "not running any flavour against it"
  return 1
}

await_device_ready || exit 1

status=0
for flavour in $FLAVOURS; do
  variant="$(tr '[:lower:]' '[:upper:]' <<<"${flavour:0:1}")${flavour:1}Debug"

  echo "::group::Instrumented tests — $variant"

  # Both flavours share one applicationId (D13), so the previous flavour's APK
  # is still installed here. Removing it keeps a stale variant from being the
  # thing under test if an install ever silently no-ops.
  adb uninstall "$APPLICATION_ID" >/dev/null 2>&1
  adb uninstall "$APPLICATION_ID.test" >/dev/null 2>&1
  adb logcat -c >/dev/null 2>&1

  tools/ci/record-screen.sh start "$flavour"

  ./gradlew ":androidApp:connected${variant}AndroidTest" --stacktrace || status=1

  tools/ci/record-screen.sh stop "$flavour"
  adb logcat -d >"$OUT_DIR/logcat-$flavour.txt" 2>/dev/null

  echo "::endgroup::"
done

if [ "$status" -ne 0 ]; then
  echo "Instrumented UI tests failed — see the uploaded reports and $OUT_DIR/*.mp4"
fi
exit "$status"
