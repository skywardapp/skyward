# ADR 0020: Stamp package metadata with the version the packaged binary reports

**Status:** Accepted for M7 (§18's release-engineering milestone)

## Context

§15.4 makes the git tag the only place a version is recorded: the root
`build.gradle.kts` derives `versionCode`, `versionName` and the desktop
`packageVersion` from `git describe`, and `auto-tag-main.yml` cuts a
`vMAJOR.MINOR.PATCH` tag on every push to `main`. "There is no version field
to bump in a commit, so nothing can silently disagree with the tag it shipped
under" is the property that arrangement buys.

Three places disagreed with it anyway, because they were never wired to it:

1. `desktopApp`'s `Main.kt` carried
   `const val APP_VERSION = "0.1.0"`, with a comment asking whoever touched it
   to keep it in step with `packageVersion`. Nobody could: the version moves
   on a push to `main` without any commit touching that line. By `v0.1.41` the
   About section still said 0.1.0, and so did the `appVersion` header written
   into every §12 export file — the one piece of provenance an exported file
   carries.
2. `flatpak/dev.fritze.Skyward.metainfo.xml`'s `<release version="0.1.0"
   date="2026-08-14">` shipped, verbatim, inside both the Flatpak and the
   AppImage. Software centres read that entry to decide which version they are
   offering and whether an update exists.
3. The AppImage carried no version at all. The release asset's *filename* was
   built from `$TAG` by the workflow, so it looked right on the release page
   and told you nothing once downloaded and renamed; `appimagetool` writes an
   `X-AppImage-Version` into the packaged desktop entry only when `VERSION` is
   set in its environment, and it wasn't.

Only the first is a bug in the app. All three are the same failure: a version
recorded by hand, next to a mechanism that derives it.

## Decision

Generate the constant, and have packaging ask the binary.

- `:desktopApp:generateAppVersion` writes `AppVersion.kt` from
  `rootProject.extra["skywardVersionName"]` into the main source set. Same
  string Android's `versionName` gets, same `git describe` shape. There is no
  longer a version literal in `desktopApp`'s sources to drift.
- `Main.kt` gains a `--version` flag, handled ahead of any windowing setup for
  the same reason `debug-matches` is, printing `skyward <APP_VERSION>`.
- `appimage/build.sh` and `flatpak/build.sh` read the version out of the tree
  they are about to package (`bin/skyward --version`) and use it for
  everything downstream: `tools/packaging/stamp-metainfo.sh` rewrites the
  AppStream `<release>` entry with it, and `appimagetool` gets it as `VERSION`.

Asking the binary rather than re-deriving the tag in each script is the point.
A second `git describe` in shell would be a second implementation of §15.4's
rules — one that could disagree with the first, and that would be wrong in
precisely the case that matters least visibly (a build from a tarball, a
different `--match`, a dirty checkout). Reading it back out of the artefact
means the metadata describing a build cannot disagree with the build. It also
works under `SKIP_GRADLE_BUILD=1`, which ADR 0019 requires of the AppImage
step: no Gradle invocation, and therefore no signing secrets, in the step that
downloads and runs `appimagetool`.

The checked-in `<release>` entry stays a placeholder, marked as one in the
file. Stamping is unconditional in both build scripts, and
`stamp-metainfo.sh` fails if it finds nothing to rewrite, so a later
reformatting of that element cannot quietly produce an unstamped copy.

`release-on-tag.yml` then asserts, before publishing, that the packaged binary
reports `${TAG#v}` and that the AppImage it is about to upload carries that
same version in both its desktop entry and its AppStream metadata. The
filename proves nothing — the workflow writes it — so the check unpacks the
file it is publishing.

## Consequences

- The About section, `--version`, §12 exports, `.deb`/`.rpm`, the Flatpak's
  AppStream metadata and the AppImage's embedded version all come from one
  tag, and a release that misreports itself fails the job instead of shipping.
- `--version` is now part of the packaging contract, not only a convenience:
  renaming the flag or changing its output shape breaks the stamping. Both
  ends say so, and `AppVersionTest` pins the output format and the character
  set the version is allowed to use — it is substituted into XML attributes
  and a desktop entry.
- The AppStream `<releases>` list holds one entry, describing the version on
  offer, rather than a history. A per-push patch tag makes a hand-written
  changelog per release impossible; AppStream only requires that the version
  being offered is described.
- A dev build past a tag reports `0.1.41-3-gabc1234` and stamps that into the
  metadata, while `.deb`/`.rpm` keep the bare `0.1.41` that jpackage insists
  on. They are meant to differ: one says "the release", the other says "three
  commits past it".

## Alternatives considered

- **Keep the constant, add a check that it matches the tag.** Fails every
  build between a push to `main` and the commit that catches the constant up —
  which is every build.
- **Have Gradle write a version file into the distributable tree for the
  scripts to read.** Works, but adds a file to the packaged output whose only
  purpose is packaging, and the binary can already answer the question.
- **Re-derive the tag with `git describe` in each build script.** A second
  implementation of §15.4's tag rules, in a language with no tests around it,
  free to disagree with the first.
- **Hand-write a `<release>` entry per release.** The natural fit for
  AppStream, and incompatible with a tag per push to `main`. Reconsider if
  §18's post-M7 release cadence ever becomes deliberate rather than automatic.
