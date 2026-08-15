#!/usr/bin/env bash
# §15.4 / §17.5b: builds the fossRelease APK twice from a clean workspace and
# asserts the two are byte-identical, excluding the signing block. This is
# what makes dual F-Droid/Play distribution coherent (§15.4) — F-Droid
# rebuilds fossRelease itself and only ever publishes the developer-signed
# APK if its rebuild matches ours.
#
# Works whether or not a real production key is configured
# (androidApp/build.gradle.kts, keystore.properties): an unsigned release APK
# has no META-INF signing entries to begin with, so stripping META-INF/ is a
# no-op in that case and the check still compares real content. A real key
# only matters at actual publish time, not for this check.
#
# Run from the repo root. Exits non-zero (and prints the differing files) on
# any divergence.
set -euo pipefail

cd "$(dirname "$0")/../.."

APK_OUT_DIR="androidApp/build/outputs/apk/foss/release"
WORKDIR=$(mktemp -d)
trap 'rm -rf "$WORKDIR"' EXIT

log() { echo "[check-reproducible-build] $*"; }

build_once() {
  local dest="$1"
  ./gradlew --no-build-cache clean :androidApp:assembleFossRelease --stacktrace

  local apk
  apk=$(find "$APK_OUT_DIR" -name '*.apk' | head -n1)
  if [ -z "$apk" ]; then
    log "no fossRelease APK found under $APK_OUT_DIR after a build — check assembleFossRelease's output"
    exit 1
  fi
  cp "$apk" "$dest"
}

# §15.4 asserts reproducibility between OUR two builds, not against F-Droid's
# rebuild (that happens on F-Droid's own infrastructure) — so both runs here
# use the same source tree and the same local toolchain.
log "building fossRelease (1/2)..."
build_once "$WORKDIR/build1.apk"

log "building fossRelease (2/2)..."
build_once "$WORKDIR/build2.apk"

# An APK is a zip. "Excluding the signature block" (§15.4) means stripping
# META-INF's signing entries before comparing — MANIFEST.MF and the
# signature/certificate files a real signing config would add.
extract_stripped() {
  local apk="$1" dest="$2"
  mkdir -p "$dest"
  unzip -q "$apk" -d "$dest"
  rm -rf "$dest/META-INF"
}

extract_stripped "$WORKDIR/build1.apk" "$WORKDIR/build1"
extract_stripped "$WORKDIR/build2.apk" "$WORKDIR/build2"

if diff -rq "$WORKDIR/build1" "$WORKDIR/build2" >"$WORKDIR/diff.txt"; then
  log "reproducible — two clean fossRelease builds are identical (excluding the signature block)."
  exit 0
fi

log "NOT reproducible — two clean fossRelease builds differ (excluding the signature block):"
cat "$WORKDIR/diff.txt"
log "for a human-readable diff of *why* a given entry differs, rerun with each build's APK kept" \
  "and compare via diffoscope (§15.4) rather than this script's plain unzip+diff."
exit 1
