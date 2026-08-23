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

# Pinned to the numbered `1.9.1` tag (ADR 0019), not the rolling `continuous`
# one — same commit today, but a numbered tag's asset doesn't get replaced
# out from under this checksum the way `continuous`'s does.
appimagetool_version="1.9.1"
appimagetool_sha256="ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0"

tool="${APPIMAGETOOL:-}"
if [ -z "$tool" ]; then
  tool="$build_dir/appimagetool-$appimagetool_version.AppImage"
  if [ ! -x "$tool" ]; then
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
