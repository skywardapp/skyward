# ADR 0019: Add AppImage desktop packaging, built outside the Compose plugin

**Status:** Accepted for M7 (§18's release-engineering milestone)

## Context

§15.5 says, of desktop packaging:

> `compose.desktop { application { … } }` with `TargetFormat.Deb`,
> `TargetFormat.Rpm` (jpackage; JDK 17+; no AppImage support in the Compose
> plugin — do not attempt).

That line is true and stays true: the Compose Multiplatform Gradle plugin's
`nativeDistributions.targetFormats` only understands the jpackage-backed
formats (Dmg/Msi/Exe/Deb/Rpm/Pkg), and trying to coax an `AppImage` value out
of `TargetFormat` is not a thing worth attempting.

It does not, however, rule out packaging an AppImage the way this repo
already packages Flatpak (also absent from `TargetFormat`): build the
self-contained jlinked tree with `:desktopApp:createReleaseDistributable`
once, then repackage that prebuilt tree with a separate tool
(`flatpak-builder` for Flatpak, `appimagetool` here). Neither goes through
the Compose plugin's packaging path at all, so neither conflicts with the
line above — it is about `TargetFormat`, not about every possible Linux
packaging format.

A user asked for a downloadable AppImage alongside the existing GitHub
Release assets (signed APK, flat-pack tarball). Unlike Flatpak — which needs
`flatpak-builder` and, for the primary channel, Flathub — an AppImage is a
single file a user can `chmod +x` and run, which is exactly the "no
installer, no package manager" niche the flat-pack tarball already serves,
just as one executable instead of an archive to unpack.

### `appimagetool` *is* pinnable — check before assuming otherwise

An earlier draft of this ADR assumed `AppImage/appimagetool` only publishes
a rolling `continuous` release (its GitHub "Releases" page shows that one as
latest, and its asset is genuinely overwritten on every upstream commit) and
treated the download as an unavoidable, unpinned exception to how this repo
otherwise treats every third-party binary it trusts (the Gradle wrapper JAR
checked against Gradle's published hashes plus a checksum-pinned
distribution; every GitHub Action by full commit SHA). `git ls-remote --tags`
against the upstream repo says otherwise: tag `1.9.1` exists and points at
the same commit as `continuous` today. There was no supply-chain gap here,
only a research gap: not checking past the "latest release" page for a
numbered one underneath it.

A numbered tag is a much stronger signal than `continuous` — the project has
no reason to move it — but a git tag ref is not cryptographically immutable
the way a Gradle distribution's published hash is: whoever holds push access
upstream could in principle repoint it or replace the release asset under it.
The actual protection isn't "the tag can't move," it's the SHA-256 check
below, which fails the build if the bytes at that URL ever stop matching
what was verified — including on a *cached* copy from a previous run, not
just a fresh download (`appimage/build.sh` re-verifies the cache every time,
so a stale or tampered cached binary doesn't get a free pass).

## Decision

Pin `appimagetool` the same way everything else here is pinned:

- `appimage/build.sh` downloads `appimagetool` from its numbered `1.9.1`
  release tag (not `continuous`) and verifies it against a hardcoded SHA-256
  before `chmod +x`-ing it, refusing to proceed on a mismatch
  (`sha256sum -c`). Cached under `build/appimage/`, but the cache is
  re-verified against the same checksum on every run, not trusted just
  because the path exists — a stale or tampered cached file re-downloads and
  re-verifies rather than getting used as-is. Override with
  `APPIMAGETOOL=/path/to/tool` to supply a locally-vetted copy instead and
  skip the download/verify step entirely.
- It repackages the same `createReleaseDistributable` tree Flatpak uses into
  an AppDir, reusing `flatpak/dev.fritze.Skyward.desktop`,
  `flatpak/dev.fritze.Skyward.metainfo.xml` and `flatpak/icon.svg` rather
  than forking copies that could drift.
- It runs with `APPIMAGE_EXTRACT_AND_RUN=1` (both to invoke `appimagetool`
  itself, and inside `appimagetool` when it embeds the AppImage runtime),
  since GitHub-hosted runners are not guaranteed to have FUSE wired up for
  unprivileged mounts — the documented workaround for exactly this
  environment.
- `.github/workflows/release-on-tag.yml` runs it after the flat pack step
  (which already built and smoke-tested the same tree), stages the result
  the same way, and extracts+runs the AppImage's own `debug-matches` smoke
  test before publishing — so a broken `appimagetool` download or a broken
  AppDir fails the release job rather than shipping a dead download.
- **The step that downloads and runs `appimagetool` never has the release
  signing material available to it, in its environment or on disk.**
  `appimage/build.sh` accepts `SKIP_GRADLE_BUILD=1` to reuse the tree Gradle
  already built in an earlier workflow step instead of invoking Gradle
  itself — deliberately, not just to save a few seconds: any Gradle
  invocation configures `androidApp/build.gradle.kts` too, which requires
  all four `SKYWARD_RELEASE_*` signing values together or none, and Gradle
  is the only reason this step would otherwise need any of them. On top of
  that, a dedicated "Remove the decoded release signing key" step deletes
  the JKS file and blanks `SKYWARD_RELEASE_STORE_FILE` in `GITHUB_ENV`
  immediately after the last step that needs it ("Build desktop
  distributable") — GitHub Actions runs every step of a job in the same
  VM with no filesystem isolation between them, so avoiding Gradle alone
  would still have left the keystore bytes on disk at a path named in the
  workflow's own source for any later step to read. Together these mean the
  one step in the job that downloads and executes a third-party binary has
  no signing password, alias, key password, or keystore file to find even
  if that binary were compromised. (An earlier version of this PR got the
  first half right and missed the second: it stopped passing the three
  secrets into this step, but left the JKS on disk and `STORE_FILE` pointing
  at it. Caught in review before merging — see the PR discussion.)

## Consequences

- The AppImage release asset now depends on a pinned, checksum-verified
  upstream artifact, same posture as everything else in the release
  pipeline. A build with a mismatched checksum fails loudly rather than
  silently shipping different bytes than what was verified.
- Bumping `appimagetool` is a two-line change in `appimage/build.sh`
  (`appimagetool_version`, `appimagetool_sha256`), the same shape as bumping
  the Gradle wrapper or a pinned Action.
- §15.5 is left as written — the Compose-plugin statement it makes is still
  correct — with this ADR referenced from `appimage/build.sh` and
  `desktopApp/build.gradle.kts` at the relevant points.

## Alternatives considered

- **Use the old `AppImageKit` release 13 instead of `AppImage/appimagetool`:**
  a fixed tag, but flagged obsolete by its own publisher ("should not be
  used anymore") and stale enough (2020) that using it deliberately would
  trade one problem for a worse one even with a valid pin.
- **The rolling `continuous` tag, unpinned, as an accepted exception:** the
  original shape of this ADR, superseded once `git ls-remote --tags` showed
  a numbered release existed to pin against instead. Left out of the final
  decision entirely rather than kept as a fallback, since there's no reason
  to prefer it now.
- **Vendor a locally-built `appimagetool`:** `AppImage/appimagetool` itself
  depends on a squashfs/runtime toolchain that is its own supply-chain
  surface one layer down, for no net improvement over pinning the published
  binary.
- **Skip AppImage, keep only the flat-pack tarball:** rejected because a
  single-file, `chmod +x`-and-run artifact is a strictly better fit for the
  "no installer, no package manager" audience than an archive to unpack, and
  the user explicitly asked for it — and once the checksum-pinning gap
  turned out not to exist, there was no remaining reason not to.
