#!/usr/bin/env bash
# §15.5's AppImage packaging (ADR 0019). Same "build once, repackage per
# format" split as flatpak/build.sh: Gradle produces the self-contained
# jlinked tree, then a separate packager — appimagetool, here — wraps it.
# No Compose-plugin involvement, so this doesn't reopen §15.5's "no
# AppImage support in the Compose plugin" note; that note is about
# `TargetFormat`, not about every possible Linux packaging tool.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

tree="desktopApp/build/compose/binaries/main-release/app/skyward"

# release-on-tag.yml already built this tree in an earlier step (and needed
# the release signing secrets in its environment to do so, per
# androidApp/build.gradle.kts's "all four or none" check). Re-running Gradle
# here would pull those secrets into this step's environment too, right
# before it downloads and executes appimagetool — set SKIP_GRADLE_BUILD=1 to
# reuse that tree instead and keep this step secret-free.
if [ "${SKIP_GRADLE_BUILD:-}" = "1" ]; then
  echo "==> Reusing the existing desktop distributable (SKIP_GRADLE_BUILD=1)"
else
  echo "==> Building the self-contained desktop distributable"
  ./gradlew :desktopApp:createReleaseDistributable
fi

if [ ! -d "$tree" ]; then
  echo "expected the jlinked tree at $tree — did createReleaseDistributable change its output layout?" >&2
  exit 1
fi

build_dir="${APPIMAGE_BUILD_DIR:-build/appimage}"
appdir="$build_dir/Skyward.AppDir"

# ADR 0020: ask the binary being packaged what version it is rather than
# re-deriving the tag here. Two consumers below — the AppStream <release>
# entry, and appimagetool's VERSION — and neither can drift from the app's own
# About screen when both are read out of it. Works under SKIP_GRADLE_BUILD=1
# too, which the release workflow needs (this step must stay Gradle-free, and
# therefore signing-secret-free — see the ADR 0019 note above).
version="$("$tree/bin/skyward" --version | awk '{print $NF}')"
if [ -z "$version" ]; then
  echo "'$tree/bin/skyward --version' printed nothing — cannot stamp the AppImage metadata" >&2
  exit 1
fi
echo "==> Packaging version $version"

metainfo="$build_dir/dev.fritze.Skyward.metainfo.xml"
tools/packaging/stamp-metainfo.sh "$version" "$metainfo" >/dev/null

echo "==> Assembling the AppDir"
rm -rf "$appdir"
mkdir -p \
  "$appdir/usr/share/applications" \
  "$appdir/usr/share/icons/hicolor/scalable/apps" \
  "$appdir/usr/share/metainfo"

cp -r "$tree" "$appdir/usr/skyward"
install -Dm755 appimage/AppRun "$appdir/AppRun"
install -Dm644 flatpak/dev.fritze.Skyward.desktop "$appdir/dev.fritze.Skyward.desktop"
install -Dm644 flatpak/dev.fritze.Skyward.desktop "$appdir/usr/share/applications/dev.fritze.Skyward.desktop"
install -Dm644 "$metainfo" "$appdir/usr/share/metainfo/dev.fritze.Skyward.metainfo.xml"
install -Dm644 flatpak/icon.svg "$appdir/usr/share/icons/hicolor/scalable/apps/dev.fritze.Skyward.svg"
# appimagetool looks for <Icon-key-from-desktop-file>.{png,svg,xpm} at the
# AppDir root (falling back through that order) and writes .DirIcon itself —
# nothing else to do here. Reusing flatpak's SVG keeps one icon asset instead
# of a second, divergeable copy.
install -Dm644 flatpak/icon.svg "$appdir/dev.fritze.Skyward.svg"

# Pinned to the numbered `1.9.1` tag (ADR 0019), not the rolling `continuous`
# one — same commit today, but a numbered tag's asset doesn't get replaced
# out from under this checksum the way `continuous`'s does.
appimagetool_version="1.9.1"
appimagetool_sha256="ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0"

tool="${APPIMAGETOOL:-}"
if [ -z "$tool" ]; then
  tool="$build_dir/appimagetool-$appimagetool_version.AppImage"
  # Re-verify a cached copy too, not just a freshly downloaded one — a stale
  # or tampered file sitting in build/appimage/ from a previous run must not
  # get a free pass just because the path already exists.
  if [ ! -x "$tool" ] || ! echo "$appimagetool_sha256  $tool" | sha256sum -c - >/dev/null 2>&1; then
    echo "==> Fetching appimagetool $appimagetool_version"
    mkdir -p "$build_dir"
    tool_partial="$tool.partial"
    curl --fail --silent --show-error --location --max-time 120 \
      -o "$tool_partial" \
      "https://github.com/AppImage/appimagetool/releases/download/$appimagetool_version/appimagetool-x86_64.AppImage"
    echo "$appimagetool_sha256  $tool_partial" | sha256sum -c -
    mv "$tool_partial" "$tool"
    chmod +x "$tool"
  fi
fi

# The AppImage docs' packaging guide asks for a desktop file that passes
# desktop-file-validate; appimagetool runs it too, but only if it happens to be
# installed, and a release that skips the check because a tool was missing is
# how an invalid entry ships. Fatal when the tool is present, skipped loudly
# when it isn't.
if command -v desktop-file-validate >/dev/null 2>&1; then
  echo "==> Validating the desktop entry"
  desktop-file-validate "$appdir/dev.fritze.Skyward.desktop"
else
  echo "==> Skipping desktop-file-validate (not installed)"
fi

# Advisory, not fatal, and deliberately so: the metainfo is knowingly missing
# the <screenshots> block Flathub requires (see the comment in the file), so a
# fatal appstreamcli would fail every build over a gap that is already tracked
# and cannot be closed from here. It is still worth printing — it catches the
# malformed-XML and wrong-id kind of mistake this stamping step could
# introduce.
if command -v appstreamcli >/dev/null 2>&1; then
  echo "==> Validating AppStream metadata (advisory)"
  appstreamcli validate --no-net "$appdir/usr/share/metainfo/dev.fritze.Skyward.metainfo.xml" || true
fi

# $APPNAME-$VERSION-$ARCH.AppImage, the naming AppImage's own tooling and
# AppImageHub expect. Lower-cased to match what release-on-tag.yml publishes
# (skyward-vX.Y.Z-x86_64.AppImage) — and, more than cosmetically, to match the
# filename glob in the update information that workflow embeds.
out="${APPIMAGE_OUTPUT:-$build_dir/skyward-$version-x86_64.AppImage}"
mkdir -p "$(dirname "$out")"
# Including the copy appimagetool drops in the working directory (see the move
# below), so a stale one from an earlier run can't be mistaken for this run's.
rm -f "$out" "$out.zsync" "$(basename "$out").zsync"

echo "==> Packaging with appimagetool"
# VERSION is what appimagetool writes into the packaged desktop file as
# X-AppImage-Version (and what it would name the output after, were the name
# not given explicitly). Without it the AppImage carries no version of its own
# at all: the release asset's filename said v0.1.41 while nothing inside the
# file did, so a downloaded-and-renamed copy became unidentifiable.
#
# APPIMAGE_UPDATE_INFO, when set, embeds AppImageUpdate update information and
# makes appimagetool emit a matching .zsync file next to the output, which is
# how an AppImage delivers its own delta updates. Left unset for local builds:
# it names the release channel an AppImage will update itself from, which only
# the release workflow can answer.
appimagetool_args=(--appimage-extract-and-run --no-appstream)
if [ -n "${APPIMAGE_UPDATE_INFO:-}" ]; then
  # appimagetool shells out to zsyncmake and only *warns* if it is missing, so
  # check first rather than discover a silently absent .zsync after publishing.
  if ! command -v zsyncmake >/dev/null 2>&1; then
    echo "APPIMAGE_UPDATE_INFO is set but zsyncmake is not installed — install" >&2
    echo "the 'zsync' package, or unset it to build without update information." >&2
    exit 1
  fi
  appimagetool_args+=(--updateinformation "$APPIMAGE_UPDATE_INFO")
fi

# --appimage-extract-and-run: appimagetool is itself distributed as an
# AppImage, and the runtime it embeds in the output needs the same — GitHub-
# hosted runners aren't guaranteed to have FUSE wired up for unprivileged
# mounts (ADR 0019).
ARCH=x86_64 VERSION="$version" APPIMAGE_EXTRACT_AND_RUN=1 \
  "$tool" "${appimagetool_args[@]}" "$appdir" "$out"

if [ -n "${APPIMAGE_UPDATE_INFO:-}" ]; then
  # appimagetool writes the .zsync into the *current directory* under the
  # output's basename, not beside the output itself — so with an --output path
  # anywhere else, the file lands somewhere nobody looks and the release ships
  # update information pointing at an asset that was never uploaded. Move it
  # next to the AppImage, which is where every consumer expects it and where
  # release-on-tag.yml collects it from.
  stray="$(basename "$out").zsync"
  if [ -s "$stray" ] && [ "$stray" != "$out.zsync" ]; then
    mv "$stray" "$out.zsync"
  fi
  if [ ! -s "$out.zsync" ]; then
    echo "appimagetool produced no $out.zsync despite update information being set —" >&2
    echo "the AppImage would advertise updates it has no zsync to serve." >&2
    exit 1
  fi
  echo "zsync written to $out.zsync"
fi

echo
echo "AppImage written to $out"
echo "Run it with: APPIMAGE_EXTRACT_AND_RUN=1 $out"
