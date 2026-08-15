#!/usr/bin/env bash
# §15.4 / §17.5b: builds the fossRelease APK twice from a clean workspace and
# asserts the two are reproducible. This is what makes dual F-Droid/Play
# distribution coherent (§15.4) — F-Droid rebuilds fossRelease itself and
# only ever publishes the developer-signed APK if its rebuild matches ours.
#
# Two tiers, strongest first: (1) the two APKs are byte-for-byte identical —
# passes outright; (2) failing that, identical *content* once META-INF's
# signing entries are stripped — passes, but logs a warning, since this tier
# can't see zip-level nondeterminism (entry order, metadata) that tier 1
# would have caught. Tier 2 existing at all is what makes this check work
# whether or not a real production key is configured
# (androidApp/build.gradle.kts, keystore.properties): an unsigned release APK
# has no META-INF signing entries, so in that case tier 1 and tier 2 are
# checking the same thing and a tier-2-only pass is itself informative (real
# zip nondeterminism, not signing). A real key only matters at actual publish
# time, not for this check to run.
#
# Run from the repo root. Exits non-zero (and prints the differing files) on
# any divergence that survives both tiers.
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

# Strongest check first: exact bytes. An APK is a zip, and zip entry order or
# metadata (timestamps, compression parameters) can differ between two builds
# without any *content* differing — a content-only diff (below) would miss
# that kind of nondeterminism entirely, so it matters to try this first.
if cmp -s "$WORKDIR/build1.apk" "$WORKDIR/build2.apk"; then
  log "reproducible — two clean fossRelease builds are byte-for-byte identical."
  exit 0
fi

log "builds are not byte-for-byte identical — checking whether the difference is confined to the signing block..."

# "Excluding the signature block" (§15.4) means stripping META-INF's signing
# entries (MANIFEST.MF, the certificate/signature files a real signing config
# adds) before comparing what's left. This is the fallback, not the primary
# check, precisely because it only compares extracted *content* and can't see
# zip-level nondeterminism the byte comparison above already ruled out.
extract_stripped() {
  local apk="$1" dest="$2"
  mkdir -p "$dest"
  unzip -q "$apk" -d "$dest"
  rm -rf "$dest/META-INF"
}

extract_stripped "$WORKDIR/build1.apk" "$WORKDIR/build1"
extract_stripped "$WORKDIR/build2.apk" "$WORKDIR/build2"

if diff -rq "$WORKDIR/build1" "$WORKDIR/build2" >"$WORKDIR/diff.txt"; then
  # The trap below deletes $WORKDIR on exit — copy the two APKs out first so
  # the diffoscope pointer below actually resolves to something.
  preserved_apk_dir="androidApp/build/reproducibility-check"
  mkdir -p "$preserved_apk_dir"
  cp "$WORKDIR/build1.apk" "$WORKDIR/build2.apk" "$preserved_apk_dir/"
  log "content is identical once META-INF/ is stripped, but the raw APK bytes differ — likely zip entry" \
    "ordering/metadata (or, once a real signing key is configured, per-run signature bytes)."
  log "that's a weaker guarantee than full byte-identity: cross-check with diffoscope (§15.4) on" \
    "$preserved_apk_dir/build{1,2}.apk before relying on this for an actual release."
  exit 0
fi

log "NOT reproducible — two clean fossRelease builds differ even with META-INF/ stripped:"
cat "$WORKDIR/diff.txt"
exit 1
