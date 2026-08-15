#!/usr/bin/env bash
# §15.5's two-step Flatpak build: Gradle produces the jlinked tree with the
# network available, then flatpak-builder packages that tree inside the
# (network-isolated) sandbox. Running Gradle inside the sandbox is what
# Flathub cannot do, which is why this split exists at all.
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

echo "==> Packaging with flatpak-builder"
# --disable-download: everything the manifest needs is already on disk, and
# saying so makes an accidental network dependency fail here rather than on
# a Flathub builder.
flatpak-builder --force-clean --disable-download \
  "${FLATPAK_BUILD_DIR:-build/flatpak}" flatpak/dev.fritze.Skyward.yml "$@"

echo
echo "Install locally with:"
echo "  flatpak-builder --user --install --force-clean build/flatpak flatpak/dev.fritze.Skyward.yml"
