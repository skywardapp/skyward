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

# ADR 0020: the AppStream <release> entry is stamped from the version the
# binary itself reports, not from a number kept up to date by hand — which it
# never was. Asking the tree that is about to be packaged means the metadata
# cannot disagree with the app it describes.
version="$("$tree/bin/skyward" --version | awk '{print $NF}')"
if [ -z "$version" ]; then
    echo "'$tree/bin/skyward --version' printed nothing — cannot stamp the AppStream metadata" >&2
    exit 1
fi
echo "==> Stamping AppStream metadata for $version"
tools/packaging/stamp-metainfo.sh "$version" build/packaging/dev.fritze.Skyward.metainfo.xml >/dev/null

echo "==> Packaging with flatpak-builder"
# --disable-download: everything the manifest needs is already on disk, and
# saying so makes an accidental network dependency fail here rather than on
# a Flathub builder.
flatpak-builder --force-clean --disable-download \
  "${FLATPAK_BUILD_DIR:-build/flatpak}" flatpak/dev.fritze.Skyward.yml "$@"

echo
echo "Install locally with:"
echo "  flatpak-builder --user --install --force-clean build/flatpak flatpak/dev.fritze.Skyward.yml"
