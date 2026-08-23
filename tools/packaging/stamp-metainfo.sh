#!/usr/bin/env bash
# Writes a copy of flatpak/dev.fritze.Skyward.metainfo.xml whose <release>
# entry carries the version actually being packaged (ADR 0020).
#
# The checked-in metainfo cannot carry a real version: auto-tag-main.yml cuts
# a tag on every push to main, so by the time anyone read the file its
# <release version="…"> was stale — v0.1.41 shipped a Flatpak and an AppImage
# whose AppStream metadata still said 0.1.0. Software centres read that entry
# to decide what version they are offering and whether an update exists, so a
# frozen one is not cosmetic.
#
#   stamp-metainfo.sh <version> <output-path> [<date>]
#
# <date> defaults to the commit date of HEAD (deterministic per commit, which
# keeps two builds of one commit identical — §15.4) and falls back to the date
# already in the file where git history is unavailable, e.g. a source tarball.
set -euo pipefail

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
    echo "usage: ${0##*/} <version> <output-path> [<date>]" >&2
    exit 2
fi

version="$1"
output="$2"
date="${3:-}"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source_file="$repo_root/flatpak/dev.fritze.Skyward.metainfo.xml"

if [ -z "$version" ]; then
    echo "stamp-metainfo: refusing to stamp an empty version" >&2
    exit 1
fi

if [ -z "$date" ]; then
    date="$(TZ=UTC git -C "$repo_root" log -1 --format=%cd --date=format-local:%Y-%m-%d 2>/dev/null || true)"
fi
if [ -z "$date" ]; then
    date="$(sed -n 's/.*<release version="[^"]*" date="\([^"]*\)".*/\1/p' "$source_file" | head -1)"
fi

mkdir -p "$(dirname "$output")"

# One <release> entry, rewritten rather than prepended: a patch tag per push to
# main would otherwise pile up hundreds of entries with nothing to say about
# any of them. AppStream only requires that the version on offer is described.
sed -E "s|<release version=\"[^\"]*\" date=\"[^\"]*\">|<release version=\"$version\" date=\"$date\">|" \
    "$source_file" > "$output"

# A metainfo refactor that renames or reformats that element must not silently
# produce an unstamped copy — that is exactly the drift this script exists to
# stop, and it would fail no build.
if ! grep -qF "<release version=\"$version\" date=\"$date\">" "$output"; then
    echo "stamp-metainfo: no <release version=… date=…> element in $source_file —" >&2
    echo "nothing was stamped. Fix the pattern in ${0##*/} to match the file." >&2
    exit 1
fi

echo "$output"
