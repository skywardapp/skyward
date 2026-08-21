#!/usr/bin/env bash
# §17.3 fixture fetcher: captures the JPL Small-Body Database query that
# CometSource.discoveryUrl() issues, into
# core/src/commonTest/resources/fixtures/ for the golden parser test.
#
# The constraint (q < 4.5 au, M1 < 14) and the field list are copied from
# CometSource.kt. full-prec=1 matters: without it SBDB rounds the orbital
# elements, and the propagator tests downstream care about the digits.
#
# D12: comet elements come from JPL, not COBS -- JPL's data is public domain,
# COBS' licence forbids commercial use (§16).
#
# Usage: tools/fixtures/fetch-jpl-sbdb.sh
set -euo pipefail

cd "$(dirname "$0")/../.."
FIXTURES="core/src/commonTest/resources/fixtures"
USER_AGENT="Skyward/1.0 (+https://github.com/skywardapp/skyward; fixture capture)"

CDATA='{"AND":["q|LT|4.5","M1|LT|14"]}'

echo "[fetch-jpl-sbdb] GET https://ssd-api.jpl.nasa.gov/sbdb_query.api (sb-kind=c, $CDATA)"
curl --fail --silent --show-error --location --max-time 180 \
    --user-agent "$USER_AGENT" \
    --get "https://ssd-api.jpl.nasa.gov/sbdb_query.api" \
    --data "sb-kind=c" \
    --data "fields=full_name,pdes,name,epoch,e,q,i,om,w,tp,M1,K1,M2,K2" \
    --data-urlencode "sb-cdata=$CDATA" \
    --data "full-prec=1" \
    --output "$FIXTURES/jpl_sbdb_comets.json"
echo "[fetch-jpl-sbdb] wrote jpl_sbdb_comets.json ($(wc -c <"$FIXTURES/jpl_sbdb_comets.json") bytes)"
