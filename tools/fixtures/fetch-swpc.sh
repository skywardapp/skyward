#!/usr/bin/env bash
# §17.3 fixture fetcher: captures NOAA SWPC's aurora-related responses into
# core/src/commonTest/resources/fixtures/ for the golden parser tests.
#
# Byte-for-byte captures, not distilled ones: the whole point of these
# fixtures is that a format drift upstream (§19 R3) shows up as a failing
# parser test rather than as an aurora forecast that silently goes blank, and
# a fixture that has been reshaped on the way in can't do that.
#
# The URLs are copied from AuroraSource.kt and KpNowcast.kt; if either
# changes, change it here too, or the fixtures stop describing what the app
# actually asks for.
#
# Usage: tools/fixtures/fetch-swpc.sh
set -euo pipefail

cd "$(dirname "$0")/../.."
FIXTURES="core/src/commonTest/resources/fixtures"
USER_AGENT="Skyward/1.0 (+https://github.com/skywardapp/skyward; fixture capture)"

log() { echo "[fetch-swpc] $*"; }

capture() {
    local url=$1 target=$2
    log "GET $url"
    curl --fail --silent --show-error --location --max-time 120 \
        --user-agent "$USER_AGENT" "$url" --output "$FIXTURES/$target"
    log "wrote $target ($(wc -c <"$FIXTURES/$target") bytes)"
}

capture "https://services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json" \
    "swpc_planetary_k_index_forecast.json"

# §17.3 asks for a full-size OVATION grid specifically: the 360x181 = 65160
# triples are what exercise the (lon*181)+(lat+90) layout at every boundary,
# and a hand-trimmed grid can be laid out correctly while the real one isn't.
capture "https://services.swpc.noaa.gov/json/ovation_aurora_latest.json" \
    "swpc_ovation_aurora_latest.json"

capture "https://services.swpc.noaa.gov/json/planetary_k_index_1m.json" \
    "swpc_planetary_k_index_1m.json"

log "done. Review the diff before committing -- these are live snapshots, so"
log "every refresh changes the numbers, and a *shape* change in the diff is a"
log "finding, not noise."
