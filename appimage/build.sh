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

echo "==> Building the self-contained desktop distributable"
./gradlew :desktopApp:createReleaseDistributable

tree="desktopApp/build/compose/binaries/main-release/app/skyward"
if [ ! -d "$tree" ]; then
  echo "expected the jlinked tree at $tree — did createReleaseDistributable change its output layout?" >&2
  exit 1
fi

build_dir="${APPIMAGE_BUILD_DIR:-build/appimage}"
appdir="$build_dir/Skyward.AppDir"

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
install -Dm644 flatpak/dev.fritze.Skyward.metainfo.xml "$appdir/usr/share/metainfo/dev.fritze.Skyward.metainfo.xml"
install -Dm644 flatpak/icon.svg "$appdir/usr/share/icons/hicolor/scalable/apps/dev.fritze.Skyward.svg"
# appimagetool looks for <Icon-key-from-desktop-file>.{png,svg,xpm} at the
# AppDir root (falling back through that order) and writes .DirIcon itself —
# nothing else to do here. Reusing flatpak's SVG keeps one icon asset instead
# of a second, divergeable copy.
install -Dm644 flatpak/icon.svg "$appdir/dev.fritze.Skyward.svg"

tool="${APPIMAGETOOL:-}"
if [ -z "$tool" ]; then
  tool="$build_dir/appimagetool.AppImage"
  if [ ! -x "$tool" ]; then
    # ADR 0019: no pinned, checksummed release exists upstream for this tool
    # — the `continuous` tag is intentionally rolling. Set APPIMAGETOOL to a
    # locally-vetted copy to avoid this download.
    echo "==> Fetching appimagetool (see ADR 0019 for why this isn't checksum-pinned)"
    mkdir -p "$build_dir"
    curl --fail --silent --show-error --location --max-time 120 \
      -o "$tool" \
      https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
    chmod +x "$tool"
  fi
fi

out="${APPIMAGE_OUTPUT:-$build_dir/Skyward-x86_64.AppImage}"
mkdir -p "$(dirname "$out")"
rm -f "$out"

echo "==> Packaging with appimagetool"
# --appimage-extract-and-run: appimagetool is itself distributed as an
# AppImage, and the runtime it embeds in the output needs the same — GitHub-
# hosted runners aren't guaranteed to have FUSE wired up for unprivileged
# mounts (ADR 0019).
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$tool" --appimage-extract-and-run \
  --no-appstream "$appdir" "$out"

echo
echo "AppImage written to $out"
echo "Run it with: APPIMAGE_EXTRACT_AND_RUN=1 $out"
