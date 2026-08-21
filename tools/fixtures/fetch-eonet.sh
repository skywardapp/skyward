#!/usr/bin/env bash
# §17.3 fixture fetcher: captures NASA EONET's open-events response into
# core/src/commonTest/resources/fixtures/ for the golden parser test.
#
# The query mirrors EonetSource.eventsUrl()'s default (status=open, days=30,
# the DEFAULT_CATEGORIES list) minus the optional bbox -- the unfiltered
# response is the wider one, and the bbox narrowing is unit-tested separately
# against EonetBbox rather than against a captured response.
#
# Usage: tools/fixtures/fetch-eonet.sh
set -euo pipefail

cd "$(dirname "$0")/../.."
FIXTURES="core/src/commonTest/resources/fixtures"
USER_AGENT="Skyward/1.0 (+https://github.com/skywardapp/skyward; fixture capture)"

URL="https://eonet.gsfc.nasa.gov/api/v3/events?status=open&days=30&category=volcanoes%2CsevereStorms%2Cwildfires"

echo "[fetch-eonet] GET $URL"
curl --fail --silent --show-error --location --max-time 120 \
    --user-agent "$USER_AGENT" "$URL" --output "$FIXTURES/eonet_events_open.json"
echo "[fetch-eonet] wrote eonet_events_open.json ($(wc -c <"$FIXTURES/eonet_events_open.json") bytes)"
