#!/usr/bin/env python3
"""§17.3b fixture fetcher: JPL Horizons state vectors for the Kepler propagator test.

Unlike its sibling fetchers this one does not capture bytes verbatim, because
Horizons does not serve a machine format for these: its `VECTORS` output is a
human-formatted table wrapped in a banner. Reproducing that table's parser
inside the test would be testing the wrong thing, so the capture is distilled
here, once, into the small JSON the test reads --
`core/src/commonTest/resources/fixtures/jpl_horizons_comet_vectors.json`.

Two calls per comet, both public JPL APIs:
  * https://ssd-api.jpl.nasa.gov/sbdb.api      -> osculating elements (the
    propagator's input: e, q, i, om, w, tp)
  * https://ssd.jpl.nasa.gov/api/horizons.api  -> heliocentric ecliptic J2000
    position vectors (the expected output), CENTER=500@10, REF_PLANE=ECLIPTIC,
    REF_SYSTEM=J2000, OUT_UNITS=AU-D

The four comets span the eccentricity and perihelion regimes §17.3b names:
short-period elliptical, near-parabolic, genuinely hyperbolic, and a deep
perihelion. Each is sampled monthly across its perihelion passage, where a
two-body propagator is under the most strain.

A refresh is a review, not a rubber stamp. Horizons refits orbit solutions,
so the numbers move between captures; what must not move is the agreement
with the propagator, which the test asserts. Read the diff.

Usage: tools/fixtures/fetch-horizons.py [--out PATH]
"""

from __future__ import annotations

import argparse
import datetime
import json
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path

USER_AGENT = "Skyward/1.0 (+https://github.com/skywardapp/skyward; fixture capture)"
SBDB_API = "https://ssd-api.jpl.nasa.gov/sbdb.api"
HORIZONS_API = "https://ssd.jpl.nasa.gov/api/horizons.api"

# (label, designation, first sample, last sample). The sample windows straddle
# each comet's perihelion, where a two-body propagator is under the most
# strain; STEP_SIZE is 30d.
#
# Both APIs are addressed by designation rather than by Horizons record
# number: the record number changes when a solution is refit (2P/Encke has
# moved more than once), and a stale one silently returns a different body.
COMETS = [
    ("2P/Encke", "2P", "2023-08-23", "2024-01-20"),
    ("C/2020 F3 (NEOWISE)", "C/2020 F3", "2020-06-03", "2020-10-01"),
    ("2I/Borisov (C/2019 Q4)", "2I", "2019-10-01", "2020-01-29"),
    ("96P/Machholz 1", "96P", "2017-09-27", "2018-01-25"),
]

# §7.4.1: "JD 2440587.5 = Unix epoch" -- the same conversion the parser uses.
UNIX_EPOCH_JD = 2440587.5
SECONDS_PER_DAY = 86400.0

VECTOR_LINE = re.compile(
    r"^\s*X\s*=\s*(?P<x>[-+0-9.Ee]+)\s+Y\s*=\s*(?P<y>[-+0-9.Ee]+)\s+Z\s*=\s*(?P<z>[-+0-9.Ee]+)"
)
EPOCH_LINE = re.compile(r"^\s*\d+\.\d+\s*=\s*A\.D\.\s*(?P<stamp>[0-9]{4}-[A-Za-z]{3}-[0-9]{2} [0-9:.]+)")

MONTHS = {m: i + 1 for i, m in enumerate(
    ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
)}


def get(url: str, params: dict[str, str]) -> str:
    request = urllib.request.Request(
        f"{url}?{urllib.parse.urlencode(params)}", headers={"User-Agent": USER_AGENT}
    )
    with urllib.request.urlopen(request, timeout=180) as response:
        return response.read().decode("utf-8")


def elements(designation: str) -> dict[str, float | str]:
    """The osculating elements the propagator takes as input."""
    payload = json.loads(get(SBDB_API, {"sstr": designation, "full-prec": "true"}))
    by_name = {e["name"]: e for e in payload["orbit"]["elements"]}
    return {
        "eccentricity": float(by_name["e"]["value"]),
        "perihelionDistanceAu": float(by_name["q"]["value"]),
        "inclinationDeg": float(by_name["i"]["value"]),
        "ascendingNodeDeg": float(by_name["om"]["value"]),
        "argPerihelionDeg": float(by_name["w"]["value"]),
        # Stored as an instant, not a Julian date: the test feeds it straight
        # to CometElements, and a JD in the fixture would put a conversion
        # between the capture and the thing under test.
        "tp": julian_date_to_iso(float(by_name["tp"]["value"])),
    }


def julian_date_to_iso(jd: float) -> str:
    seconds = round((jd - UNIX_EPOCH_JD) * SECONDS_PER_DAY, 3)
    stamp = datetime.datetime.fromtimestamp(seconds, tz=datetime.timezone.utc)
    return stamp.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def vectors(designation: str, start: str, stop: str) -> list[dict[str, object]]:
    """Heliocentric ecliptic J2000 positions, one per 30 days, from the $$SOE block."""
    text = get(HORIZONS_API, {
        "format": "text",
        # ";CAP" picks the apparition closest to the requested window, which
        # is what makes a designation usable as a Horizons COMMAND for a
        # multi-apparition periodic comet.
        "COMMAND": f"'DES={designation};CAP'",
        "OBJ_DATA": "NO",
        "MAKE_EPHEM": "YES",
        "EPHEM_TYPE": "VECTORS",
        "CENTER": "500@10",
        "REF_PLANE": "ECLIPTIC",
        "REF_SYSTEM": "J2000",
        "OUT_UNITS": "AU-D",
        "VEC_TABLE": "1",
        "START_TIME": start,
        "STOP_TIME": stop,
        "STEP_SIZE": "30d",
    })
    if "$$SOE" not in text:
        raise SystemExit(f"Horizons returned no ephemeris block for {designation}:\n{text[:600]}")

    body = text.split("$$SOE", 1)[1].split("$$EOE", 1)[0]
    points: list[dict[str, object]] = []
    pending: str | None = None
    for line in body.splitlines():
        epoch = EPOCH_LINE.match(line)
        if epoch:
            pending = iso(epoch.group("stamp"))
            continue
        vector = VECTOR_LINE.match(line)
        if vector and pending:
            points.append({
                "time": pending,
                "x": float(vector.group("x")),
                "y": float(vector.group("y")),
                "z": float(vector.group("z")),
            })
            pending = None
    if not points:
        raise SystemExit(f"parsed no vectors for {designation} -- has the table format changed?")
    return points


def iso(stamp: str) -> str:
    """'2023-Aug-23 00:00:00.0000' -> '2023-08-23T00:00:00Z' (Horizons emits TDB; the ~70 s
    offset from UTC is far below this test's tolerance and is ignored, as the test's own
    comment records)."""
    date, time = stamp.split(" ")
    year, month, day = date.split("-")
    return f"{year}-{MONTHS[month]:02d}-{day}T{time.split('.')[0]}Z"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--out",
        type=Path,
        default=Path("core/src/commonTest/resources/fixtures/jpl_horizons_comet_vectors.json"),
    )
    args = parser.parse_args()

    captured = []
    for label, designation, start, stop in COMETS:
        print(f"[fetch-horizons] {label}: elements from SBDB, vectors from Horizons", file=sys.stderr)
        entry: dict[str, object] = {"name": label, "designation": designation}
        entry.update(elements(designation))
        entry["points"] = vectors(designation, start, stop)
        captured.append(entry)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps({"comets": captured}, indent=2) + "\n", encoding="utf-8")
    print(f"[fetch-horizons] wrote {args.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
