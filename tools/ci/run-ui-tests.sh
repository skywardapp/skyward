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

# adb brings no timeouts of its own: `wait-for-device` blocks forever by
# design, and a `shell` call against a half-up device can hang just as long. A
# readiness gate that can itself hang is worse than none — it would burn the
# job's whole 60-minute budget instead of failing in three minutes — so every
# call below is bounded. `timeout` is coreutils and always present on the CI
# runner; a dev box without it (macOS) degrades to unbounded rather than
# refusing to run.
TIMEOUT_BIN=$(command -v timeout || command -v gtimeout || echo "")

adb_bounded() {
  local budget=$1
  shift
  # Deadline already spent: report a timeout rather than granting a fresh
  # second. 124 is what `timeout` itself exits with.
  [ "$budget" -lt 1 ] && return 124
  if [ -n "$TIMEOUT_BIN" ]; then
    "$TIMEOUT_BIN" "$budget" adb "$@"
  else
    adb "$@"
  fi
}

# `sys.boot_completed` is not on its own proof that the device can be driven:
# the emulator-runner action has already seen it flip to 1 and then failed on
# the very next adb call because the system services were not registered yet.
# Wait for the package manager to actually answer before installing anything —
# otherwise a half-ready device surfaces as an inscrutable Gradle install
# failure partway through the first flavour, rather than as what it is.
await_device_ready() {
  local start=$SECONDS
  local deadline=$((start + READY_TIMEOUT_SECONDS))
  local booted=""

  if ! adb_bounded "$((deadline - SECONDS))" wait-for-device; then
    log "no device visible to adb within ${READY_TIMEOUT_SECONDS}s"
    return 1
  fi

  # Each call recomputes what is left of the deadline rather than sharing one
  # per-iteration budget: a slow-but-successful getprop would otherwise hand
  # the full remainder to the pm probe a second time, and the gate would
  # overrun the timeout it exists to enforce.
  while [ "$SECONDS" -lt "$deadline" ]; do
    booted=$(adb_bounded "$((deadline - SECONDS))" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')
    if [ "$booted" = "1" ] &&
      adb_bounded "$((deadline - SECONDS))" shell pm path android >/dev/null 2>&1; then
      log "device ready after $((SECONDS - start))s"
      return 0
    fi
    sleep 2
  done

  # Report the value already observed above; asking again would spend time we
  # have by definition run out of.
  log "device never became ready within ${READY_TIMEOUT_SECONDS}s" \
    "(last sys.boot_completed='${booted}'); not running any flavour against it"
  return 1
}

await_device_ready || exit 1

# BOOT_COMPLETED and MY_PACKAGE_REPLACED are <protected-broadcast>s: ActivityManager
# refuses them from any app-id outside the system set, checked on the action before
# the component is even resolved. So neither the app's own sendBroadcast() nor
# `am broadcast` from the ordinary shell (uid 2000) can deliver one -- and §17.5's
# "boot-receiver re-registration" needs a real dispatch to the real receiver, not a
# test that re-implements BootReceiver's body (#55).
#
# UiAutomation.executeShellCommand runs in the shell that hosts `am instrument`,
# which inherits adbd's uid, so restarting adbd as root is what lets
# SystemBroadcastReceiverTest send them. See ADR 0018.
#
# Best effort by design: these are userdebug emulator images, so this normally
# succeeds, but a device that refuses must not fail the run -- those two tests
# report themselves skipped (assumeTrue) and everything else is unaffected. adbd
# restarting drops the connection, hence the second readiness gate; done once
# here rather than inside the flavour loop so it costs one reconnect, not one
# per flavour.
if adb root >/dev/null 2>&1; then
  # await_device_ready already does its own bounded wait-for-device, so a bare
  # `adb wait-for-device` here would only add an unbounded one: if adbd never
  # came back it would sit until the job's 60-minute timeout instead of failing
  # in READY_TIMEOUT_SECONDS with a diagnosis.
  await_device_ready || exit 1
else
  log "adb root unavailable; the protected-broadcast tests will report themselves skipped"
fi

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
