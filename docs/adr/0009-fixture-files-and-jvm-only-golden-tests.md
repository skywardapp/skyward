# ADR 0009: Captured-response fixtures are files, and the tests that read them are JVM-only

**Status:** Accepted (implements §17.3's fixture files; records one narrow
deviation from CLAUDE.md's "domain tests live in `commonTest`")

## Context

§17.3 asks for "checked-in HTTP fixture files (real captured responses, in
`commonTest/resources/fixtures/`)" and §15.1 lists "fixture fetchers" under
`tools/`. Neither existed. Every SWPC, OVATION, EONET, JPL SBDB and Horizons
payload the parser tests ran against was a string literal inside the test
file, and `tools/` held only `ci/` and `naturalearth/`.

That left three problems, none of them about test *coverage* — the parsers
were well exercised:

- **No provenance.** A literal in a test file cannot be traced to a request.
  Some of those literals were real captures (`KeplerTest`'s Horizons vectors
  said so in a comment); others were hand-written to look like one. Nothing
  distinguished them.
- **No regeneration path.** CLAUDE.md instructs "regenerate fixtures with the
  `tools/` fetchers, never by hand" — an instruction it was impossible to
  follow. When SWPC or JPL changes a response shape (§19 R3), the only way to
  find out was for a user's fetch to fail.
- **Nothing full-size.** §17.3 asks specifically for a full-size OVATION grid.
  A hand-written six-cell grid can be indexed correctly while the real
  360×181 payload isn't: `(lon*181)+(lat+90)` only goes wrong at the
  extremes, and only a complete grid has them.

The inline choice was argued in a comment in `SwpcParsingTest`: a
resource-loading `expect`/`actual` would add KMP plumbing, and AGP's
resource handling has a duplication trap (the one that forces
`showers.json` to exist twice). Both points are real. But the eclipse canon
tests already read `commonTest/resources/fixtures/*.csv` — from
`desktopTest`, with a plain JVM classloader and no `expect`/`actual` at all.
The plumbing was avoidable, not required.

## Decision

**Captured responses are files under `core/src/commonTest/resources/fixtures/`,
written by scripts in `tools/fixtures/`.** One script per upstream, each
issuing the same request the corresponding source issues, so a fixture always
describes something the app actually asks for.

**The golden tests that read them live in `desktopTest`,** following the
existing `EclipseCanonFixtureTest`, and use a shared `Fixtures` helper rather
than each rolling its own classloader call. This is the deviation from
CLAUDE.md's "domain tests live in `:core` `commonTest` and run on both JVM and
Android": these specific tests run on the JVM only.

**Synthetic inputs stay inline in `commonTest`,** and are now named for what
they are (`*_SAMPLE`, not `*_FIXTURE`). A malformed row, a swapped column
order, a truncated payload, a polygon with a hand-checkable centroid: none of
these can be *captured*, because a real response cannot be relied on to
contain them. They are written, so they belong next to the assertion that
explains them.

The two kinds of test check different things, which is why both exist:

| | reads | asserts |
|---|---|---|
| `commonTest` (`*ParsingTest`) | synthetic literals | specific edge-case behaviour, exact values |
| `desktopTest` (`*FixtureTest`) | captured files | shape invariants: counts, ranges, ordering, extent |

A golden test that asserted the *numbers* in a live capture would fail on
every refresh, so they assert what must hold for any capture — that no row
was silently skipped, that no coordinate is out of range, that the OVATION
grid's strongest cells are at high latitude rather than on the equator.

## Consequences

- `tools/` now contains the fetchers §15.1 promised, so CLAUDE.md's
  "regenerate with the `tools/` fetchers, never by hand" is followed rather
  than aspirational. A format drift upstream now surfaces as a failing test
  after a refresh, instead of as a source that quietly returns nothing.
- The parser code under test stays in `commonMain` and is still compiled for
  both targets; only the assertions are JVM-only. What Android would have
  contributed by running them again is a second execution of identical
  Kotlin over identical bytes — the platform difference these tests could
  expose is nil, which is why the deviation is acceptable.
- The repository carries about 1.3 MB of captured JSON, most of it the
  full-size OVATION grid. Test resources are not packaged into the APK or the
  desktop distributable, so this costs clone size and nothing else.
- Refreshing a fixture changes every number in it, so a refresh is reviewed
  as a diff, not rubber-stamped. The fetchers say so; a *shape* change in
  that diff is a finding.
- The GSFC eclipse CSVs have no fetcher and are not getting one: they are
  transcribed from NASA's published canon tables (HTML), and a scraper over
  someone's table layout is a worse provenance story than a recorded URL and
  a transcription date. `fixtures/README.md` records where each row came
  from.

## Alternatives considered

**An `expect`/`actual` resource reader in `commonTest`, keeping the golden
tests common.** This is the shape §17.3 implies, and it would keep every
domain test on both targets. Rejected for now: it needs an `androidUnitTest`
actual whose resource visibility is exactly the AGP behaviour that already
forced `showers.json` to be duplicated, and the payoff is running the same
pure Kotlin over the same bytes a second time. If a parser ever grows a
genuine platform-dependent path, this becomes worth doing.

**Keep the fixtures inline and write an ADR accepting that.** The other
branch the issue offered. Rejected: it would have meant amending §17.3 and
§15.1 to match the code, and the reasons those sections give — provenance and
a regeneration path for when an upstream format drifts — are correct. The
plumbing objection that motivated inlining turned out not to apply once the
tests read the files from `desktopTest`.

**Trim the captures to keep the repository small.** Rejected for OVATION
specifically: §17.3 asks for a full-size grid, and a trimmed grid cannot
exercise the layout at the boundaries where it fails. For the others the full
response is what the app receives, and trimming it would reintroduce the
hand-editing this ADR exists to remove.
