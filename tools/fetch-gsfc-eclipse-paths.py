#!/usr/bin/env python3
"""Capture GSFC umbral-path tables as the §17.2 centreline fixture.

Fetches Espenak's per-eclipse path tables from NASA/GSFC and writes the
central-line rows — time, latitude, longitude, path width, central duration —
as CSV into `core/src/commonTest/resources/fixtures/`. That table is the
published centreline §17.2's proximity assertion is measured against, and its
central durations are the published totality figures §17.1 spot-checks.

Eclipse predictions courtesy of Fred Espenak and Jean Meeus, NASA/GSFC
(§16: free with acknowledgment; the acknowledgment is carried in the CSV
header and in the test source).

Per AGENTS.md, fixtures are regenerated with this script rather than edited
by hand:

    python3 tools/fetch-gsfc-eclipse-paths.py

It rewrites the file in place; commit the diff after checking it is the
change you expected.
"""

import csv
import io
import re
import sys
import urllib.request

# §17.1/§17.2's three named eclipses. The keys are the eclipse dates the app
# uses in its occurrence ids (`se:YYYYMMDD`).
ECLIPSES = {
    "2026-08-12": "SE2026Aug12Tpath.html",
    "2027-08-02": "SE2027Aug02Tpath.html",
    "2028-07-22": "SE2028Jul22Tpath.html",
}

BASE_URL = "https://eclipse.gsfc.nasa.gov/SEpath/SEpath2001/"
OUTPUT = "core/src/commonTest/resources/fixtures/gsfc_central_paths_named_eclipses.csv"
USER_AGENT = "skyward-fixture-fetcher (https://github.com/skywardapp/skyward)"

TAG = re.compile(r"<[^>]*>")
ROW = re.compile(r"^\s*(\d{2}):(\d{2})\s")
DURATION = re.compile(r"^(\d+)m([\d.]+)s$")


def degrees(whole: str, minutes_and_hemisphere: str) -> float:
    """`"043"`, `"22.3N"` -> 43.3717. GSFC writes degrees and decimal minutes."""
    hemisphere = minutes_and_hemisphere[-1]
    minutes = float(minutes_and_hemisphere[:-1])
    value = float(whole) + minutes / 60.0
    return -value if hemisphere in ("S", "W") else value


def duration_seconds(text: str) -> float:
    match = DURATION.match(text)
    if not match:
        raise ValueError(f"unparsable duration {text!r}")
    return int(match.group(1)) * 60 + float(match.group(2))


def parse(html: str, date: str):
    """Yields one row per central-line sample.

    Read from the right-hand end: the northern and southern limit columns are
    `-` wherever the limit misses the Earth, so their token count varies while
    the central line, ratio, altitude, azimuth, width and duration are always
    present. The `Limits` rows (the extreme ends of the track, with no
    associated time) are skipped — there is nothing in the app's own sampling
    to compare them against.
    """
    for line in TAG.sub("", html).splitlines():
        match = ROW.match(line)
        if not match:
            continue
        tokens = line.split()
        yield {
            "eclipse_date": date,
            "time_utc": f"{date}T{match.group(1)}:{match.group(2)}:00Z",
            "centre_lat_deg": f"{degrees(tokens[-9], tokens[-8]):.4f}",
            "centre_lon_deg": f"{degrees(tokens[-7], tokens[-6]):.4f}",
            "path_width_km": tokens[-2],
            "central_duration_sec": f"{duration_seconds(tokens[-1]):.1f}",
        }


def main() -> int:
    rows = []
    for date, page in ECLIPSES.items():
        url = BASE_URL + page
        print(f"fetching {url}", file=sys.stderr)
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(request, timeout=60) as response:
            html = response.read().decode("utf-8", errors="replace")
        eclipse_rows = list(parse(html, date))
        if not eclipse_rows:
            print(f"no central-line rows parsed from {url}", file=sys.stderr)
            return 1
        print(f"  {len(eclipse_rows)} central-line samples", file=sys.stderr)
        rows += eclipse_rows

    buffer = io.StringIO()
    buffer.write(
        "# NASA GSFC umbral-path tables for the §17.1/§17.2 named eclipses,\n"
        "# central line only, at the published 120-second interval.\n"
        "# Eclipse predictions courtesy of Fred Espenak and Jean Meeus, NASA/GSFC.\n"
        f"# Regenerate with tools/{__file__.split('/')[-1]} — do not edit by hand.\n"
    )
    writer = csv.DictWriter(buffer, fieldnames=list(rows[0].keys()), lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)

    with open(OUTPUT, "w", encoding="utf-8") as handle:
        handle.write(buffer.getvalue())
    print(f"wrote {len(rows)} rows to {OUTPUT}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
