#!/usr/bin/env python3
"""Capture JPL Horizons comet ephemerides as the §17.3b oracle fixture.

§17.3b wants "positions + T-mag, daily over one year" for four comets
spanning the eccentricity regimes, checked in and compared against the
universal-variable propagator (§7.4.2). This writes two files into
`core/src/commonTest/resources/fixtures/`:

  jpl_horizons_comet_elements.csv      one osculating solution per comet
  jpl_horizons_comet_ephemerides.csv   one row per comet per day

Everything the propagator is asked to reproduce is captured **geometrically**,
so the comparison is like for like:

  - `r` and the heliocentric ecliptic position come from a VECTORS call
    centred on the Sun (`500@10`, ECLIPTIC/J2000) — the same frame
    `heliocentricPosition` returns.
  - `delta` and RA/Dec come from a VECTORS call centred on the Earth
    (`500@399`, FRAME/ICRF) with `VEC_CORR='NONE'`. Horizons' *observer*
    RA/Dec (quantity 1) is astrometric — light-time and stellar aberration
    corrected — which the propagator does not model; comparing against it
    would spend a fifth of §17.3b's 0.05° budget on a correction that is not
    the propagator's job. The geometric vector is what the app computes.
  - T-mag comes from an OBSERVER call, because it is the one quantity
    VECTORS does not carry. Horizons computes it as
    `M1 + 5*log10(delta) + k1*log10(r)`, the same formula as
    `apparentMagnitude` (ADR 0004), so the comparison is a geometry check —
    exactly as §17.3b says.

**Elements and times.** The osculating elements are taken from Horizons at
each comet's perihelion, and the ephemeris window is that date ±182 days, so
two-body propagation is anchored in the middle of the span it is checked
over. Both the ephemeris timestamps and `Tp` are Julian dates in **TDB**, and
both are converted to `Instant` by the app's own rule (JD 2440587.5 = Unix
epoch, 86400 s/day, §7.4.1). Converting them the same way means the ~69 s
TDB-UTC offset cancels out of `t - tp`, which is the only thing the
propagator uses them for. The OBSERVER rows are timestamped in UT rather than
TDB and are joined on calendar date; T-mag moves by ~0.01 mag/day for these
comets, so the 69 s is worth ~1e-5 mag.

Ephemerides courtesy of the Jet Propulsion Laboratory, California Institute
of Technology (US Government work, public domain — §16).

Per AGENTS.md, fixtures are regenerated with this script rather than edited
by hand:

    python3 tools/fetch-jpl-horizons-comet-ephemerides.py
"""

import csv
import io
import math
import re
import sys
import urllib.parse
import urllib.request

API = "https://ssd.jpl.nasa.gov/api/horizons.api"
USER_AGENT = "skyward-fixture-fetcher (https://github.com/skywardapp/skyward)"

ELEMENTS_OUTPUT = "core/src/commonTest/resources/fixtures/jpl_horizons_comet_elements.csv"
EPHEMERIDES_OUTPUT = "core/src/commonTest/resources/fixtures/jpl_horizons_comet_ephemerides.csv"

# §17.3b: "four comets spanning the eccentricity regimes — one short-period
# elliptical, one near-parabolic (e < 1 but > 0.99), one genuinely hyperbolic
# (e > 1.02, i.e. an interstellar object), one with perihelion inside 0.5 au".
# `CAP` picks the apparition nearest the ephemeris start, which is what makes
# a designation like `2P` unambiguous.
COMETS = [
    # (fixture key, Horizons COMMAND, display name, perihelion date, why it is here)
    ("encke", "DES=2P;CAP", "2P/Encke", "2023-10-22", "short-period elliptical, e=0.85; also q<0.5 au"),
    ("neowise", "DES=C/2020 F3;CAP", "C/2020 F3 (NEOWISE)", "2020-07-03", "near-parabolic, e=0.999"),
    ("borisov", "DES=2I;CAP", "2I/Borisov (C/2019 Q4)", "2019-12-08", "genuinely hyperbolic, e=3.36"),
    ("machholz", "DES=96P;CAP", "96P/Machholz 1", "2017-10-27", "deep perihelion, q=0.12 au"),
]

WINDOW_DAYS = 182
SOE = re.compile(r"\$\$SOE(.*?)\$\$EOE", re.S)
MAG_PARAMS = re.compile(r"M1=\s*([-\d.]+).*?k1=\s*([-\d.]+)", re.S)


def horizons(**params) -> str:
    query = urllib.parse.urlencode({"format": "text", **params})
    request = urllib.request.Request(f"{API}?{query}", headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=120) as response:
        text = response.read().decode("utf-8", errors="replace")
    if "$$SOE" not in text and params.get("MAKE_EPHEM") != "NO":
        raise RuntimeError(f"Horizons returned no ephemeris for {params.get('COMMAND')}:\n{text[:800]}")
    return text


def body(text: str):
    match = SOE.search(text)
    if not match:
        raise RuntimeError("no $$SOE block")
    return [line for line in match.group(1).splitlines() if line.strip()]


def shift_days(iso_date: str, days: int) -> str:
    """Calendar arithmetic without importing datetime just for this."""
    import datetime

    d = datetime.date.fromisoformat(iso_date) + datetime.timedelta(days=days)
    return d.isoformat()


def jd_to_iso(jd: float) -> str:
    """The app's own rule (§7.4.1): JD 2440587.5 is the Unix epoch, 86400 s/day."""
    import datetime

    seconds = (jd - 2440587.5) * 86400.0
    stamp = datetime.datetime(1970, 1, 1, tzinfo=datetime.timezone.utc) + datetime.timedelta(seconds=seconds)
    return stamp.strftime("%Y-%m-%dT%H:%M:%S.") + f"{stamp.microsecond // 1000:03d}Z"


def fetch_elements(command: str, perihelion: str) -> dict:
    text = horizons(
        COMMAND=f"'{command}'", OBJ_DATA="NO", MAKE_EPHEM="YES", EPHEM_TYPE="ELEMENTS",
        CENTER="'500@10'", REF_PLANE="'ECLIPTIC'", REF_SYSTEM="'ICRF'", OUT_UNITS="'AU-D'",
        START_TIME=f"'{perihelion}'", STOP_TIME=f"'{shift_days(perihelion, 1)}'", STEP_SIZE="'1d'",
    )
    lines = body(text)
    # Two rows come back (start and stop); the first is the osculating
    # solution at the requested epoch, which is the one to propagate from.
    block = " ".join(lines[:5])
    def value(key):
        match = re.search(rf"\b{key}\s*=\s*([-\d.E+]+)", block)
        if not match:
            raise RuntimeError(f"no {key} in:\n{block}")
        return float(match.group(1))

    return {
        "eccentricity": value("EC"),
        "perihelion_distance_au": value("QR"),
        "inclination_deg": value("IN"),
        "ascending_node_deg": value("OM"),
        "arg_perihelion_deg": value("W"),
        "tp_jd_tdb": value("Tp"),
    }


def fetch_heliocentric(command: str, start: str, stop: str) -> dict:
    text = horizons(
        COMMAND=f"'{command}'", OBJ_DATA="NO", MAKE_EPHEM="YES", EPHEM_TYPE="VECTORS",
        CENTER="'500@10'", REF_PLANE="'ECLIPTIC'", REF_SYSTEM="'ICRF'", VEC_CORR="'NONE'",
        OUT_UNITS="'AU-D'", VEC_TABLE="'1'", CSV_FORMAT="'YES'",
        START_TIME=f"'{start}'", STOP_TIME=f"'{stop}'", STEP_SIZE="'1d'",
    )
    rows = {}
    for line in body(text):
        parts = [p.strip() for p in line.split(",")]
        jd = float(parts[0])
        x, y, z = float(parts[2]), float(parts[3]), float(parts[4])
        rows[round(jd, 6)] = (x, y, z)
    return rows


def fetch_geocentric(command: str, start: str, stop: str) -> dict:
    text = horizons(
        COMMAND=f"'{command}'", OBJ_DATA="NO", MAKE_EPHEM="YES", EPHEM_TYPE="VECTORS",
        CENTER="'500@399'", REF_PLANE="'FRAME'", REF_SYSTEM="'ICRF'", VEC_CORR="'NONE'",
        OUT_UNITS="'AU-D'", VEC_TABLE="'1'", CSV_FORMAT="'YES'",
        START_TIME=f"'{start}'", STOP_TIME=f"'{stop}'", STEP_SIZE="'1d'",
    )
    rows = {}
    for line in body(text):
        parts = [p.strip() for p in line.split(",")]
        jd = float(parts[0])
        x, y, z = float(parts[2]), float(parts[3]), float(parts[4])
        delta = math.sqrt(x * x + y * y + z * z)
        ra = math.degrees(math.atan2(y, x)) % 360.0
        dec = math.degrees(math.asin(z / delta))
        rows[round(jd, 6)] = (delta, ra, dec)
    return rows


def fetch_tmag(command: str, start: str, stop: str):
    text = horizons(
        COMMAND=f"'{command}'", OBJ_DATA="YES", MAKE_EPHEM="YES", EPHEM_TYPE="OBSERVER",
        CENTER="'500@399'", QUANTITIES="'9'", ANG_FORMAT="'DEG'", CSV_FORMAT="'YES'",
        START_TIME=f"'{start}'", STOP_TIME=f"'{stop}'", STEP_SIZE="'1d'",
    )
    match = MAG_PARAMS.search(text)
    if not match:
        raise RuntimeError(f"no M1/k1 in the header for {command}")
    m1, k1 = float(match.group(1)), float(match.group(2))

    by_date = {}
    for line in body(text):
        parts = [p.strip() for p in line.split(",")]
        # "2023-Oct-20 00:00" -> "2023-10-20"
        stamp = parts[0].split()[0]
        year, month, day = stamp.split("-")
        month_number = "JanFebMarAprMayJunJulAugSepOctNovDec".index(month) // 3 + 1
        date = f"{year}-{month_number:02d}-{int(day):02d}"
        tmag = parts[3]
        if tmag and tmag != "n.a.":
            by_date[date] = float(tmag)
    return m1, k1, by_date


def main() -> int:
    element_rows = []
    ephemeris_rows = []

    for key, command, name, perihelion, rationale in COMETS:
        start = shift_days(perihelion, -WINDOW_DAYS)
        stop = shift_days(perihelion, WINDOW_DAYS)
        print(f"fetching {name} ({start}..{stop})", file=sys.stderr)

        elements = fetch_elements(command, perihelion)
        helio = fetch_heliocentric(command, start, stop)
        geo = fetch_geocentric(command, start, stop)
        m1, k1, tmag_by_date = fetch_tmag(command, start, stop)

        element_rows.append({
            "comet": key,
            "name": name,
            "regime": rationale,
            "horizons_command": command,
            "elements_epoch_utc": perihelion,
            "eccentricity": repr(elements["eccentricity"]),
            "perihelion_distance_au": repr(elements["perihelion_distance_au"]),
            "inclination_deg": repr(elements["inclination_deg"]),
            "ascending_node_deg": repr(elements["ascending_node_deg"]),
            "arg_perihelion_deg": repr(elements["arg_perihelion_deg"]),
            "tp_utc": jd_to_iso(elements["tp_jd_tdb"]),
            "m1": repr(m1),
            "k1": repr(k1),
        })

        kept = 0
        for jd in sorted(helio):
            if jd not in geo:
                continue
            x, y, z = helio[jd]
            delta, ra, dec = geo[jd]
            iso = jd_to_iso(jd)
            tmag = tmag_by_date.get(iso[:10])
            if tmag is None:
                continue
            ephemeris_rows.append({
                "comet": key,
                "time_utc": iso,
                "helio_x_au": f"{x:.9f}",
                "helio_y_au": f"{y:.9f}",
                "helio_z_au": f"{z:.9f}",
                "r_au": f"{math.sqrt(x * x + y * y + z * z):.9f}",
                "delta_au": f"{delta:.9f}",
                "ra_deg": f"{ra:.6f}",
                "dec_deg": f"{dec:.6f}",
                "tmag": f"{tmag:.3f}",
            })
            kept += 1
        print(f"  {kept} daily samples", file=sys.stderr)

    write(ELEMENTS_OUTPUT, element_rows, (
        "# Osculating elements at perihelion for the §17.3b comets, from JPL Horizons.\n"
        "# Ephemerides courtesy of the Jet Propulsion Laboratory, California Institute of\n"
        "# Technology (US Government work, public domain).\n"
        "# `tp_utc` is Horizons' Tp (a Julian date in TDB) converted by the app's own rule\n"
        "# (JD 2440587.5 = Unix epoch), the same conversion time_utc below uses, so the\n"
        "# TDB-UTC offset cancels out of the (t - tp) the propagator actually uses.\n"
        "# Regenerate with tools/fetch-jpl-horizons-comet-ephemerides.py — do not edit by hand.\n"
    ))
    write(EPHEMERIDES_OUTPUT, ephemeris_rows, (
        "# Daily JPL Horizons ephemerides, perihelion ±182 days, for the §17.3b comets.\n"
        "# Ephemerides courtesy of the Jet Propulsion Laboratory, California Institute of\n"
        "# Technology (US Government work, public domain).\n"
        "# Positions, delta and RA/Dec are geometric (VEC_CORR='NONE'): no light-time or\n"
        "# aberration correction, matching what the propagator computes. helio_* is\n"
        "# ecliptic J2000; ra/dec come from the geocentric ICRF/J2000 equatorial vector.\n"
        "# tmag is Horizons' own total magnitude, M1 + 5*log10(delta) + k1*log10(r).\n"
        "# Regenerate with tools/fetch-jpl-horizons-comet-ephemerides.py — do not edit by hand.\n"
    ))
    return 0


def write(path: str, rows: list, header: str) -> None:
    buffer = io.StringIO()
    buffer.write(header)
    writer = csv.DictWriter(buffer, fieldnames=list(rows[0].keys()), lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(buffer.getvalue())
    print(f"wrote {len(rows)} rows to {path}", file=sys.stderr)


if __name__ == "__main__":
    sys.exit(main())
