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

### The supply-chain gap this repo doesn't otherwise have

Every third-party binary this repo's build/release pipeline trusts is
pinned and verifiable: the Gradle wrapper JAR is checked against Gradle's
published hashes (`validate-wrappers: true`) and the distribution itself is
checksum-pinned (`distributionSha256Sum`); every GitHub Action is a full
commit SHA. `appimagetool` has no equivalent to pin against:

- The old, fixed-tag release (`AppImage/AppImageKit`, tag `13`, 2020) is
  flagged obsolete by its own publisher ("should not be used anymore") and
  is stale enough that using it deliberately would trade one problem for a
  worse one.
- The maintained tool (`AppImage/appimagetool`) only publishes a rolling
  `continuous` release — its asset is overwritten on every upstream commit,
  so the download URL points at different bytes over time by design. There
  is no numbered release and no published checksum to pin against; "pin a
  hash today" would just mean the build starts failing the next time
  upstream ships a commit, for no security benefit (a bad update is not
  caught, since the pin would immediately go stale).

This is a real gap against §15.4's reproducibility posture, not a shortcut
taken for convenience: `checkDependencyLicenses` and the Gradle/Actions
pinning exist because this repo can *usually* get a pinnable artifact, and
here it cannot.

## Decision

Ship it anyway, using `AppImage/appimagetool`'s `continuous` build, and
accept the unpinned download as a documented, scoped exception rather than
pretend it doesn't exist:

- `appimage/build.sh` downloads `appimagetool` (cached under
  `build/appimage/`, override with `APPIMAGETOOL=/path/to/tool` to supply a
  locally-vetted copy instead) and repackages the same
  `createReleaseDistributable` tree Flatpak uses into an AppDir, reusing
  `flatpak/dev.fritze.Skyward.desktop`, `flatpak/dev.fritze.Skyward.metainfo.xml`
  and `flatpak/icon.svg` rather than forking copies that could drift.
- It runs with `APPIMAGE_EXTRACT_AND_RUN=1` (both to invoke `appimagetool`
  itself, and inside `appimagetool` when it embeds the AppImage runtime),
  since GitHub-hosted runners are not guaranteed to have FUSE wired up for
  unprivileged mounts — the documented workaround for exactly this
  environment.
- `.github/workflows/release-on-tag.yml` runs it after the flat pack step
  (which already builds and smoke-tests the same tree), stages the result
  the same way, and extracts+runs the AppImage's own `debug-matches` smoke
  test before publishing — so a broken `appimagetool` download or a broken
  AppDir fails the release job rather than shipping a dead download.
- The download is scoped to that one packaging step: nothing about it
  touches the release signing key, and the step does not receive the
  `SKYWARD_RELEASE_*` secrets in its environment.

## Consequences

- The AppImage release asset depends on an upstream artifact this repo
  cannot pin or verify the way it does everything else in the release
  pipeline. If `appimagetool`'s `continuous` build ever regresses or is
  compromised, that risk is not caught by any check here — only by the
  smoke test failing outright (which would fail loudly, not silently ship a
  broken or malicious asset with a passing build).
- If `AppImage/appimagetool` ever cuts a numbered, checksummed release, or a
  trustworthy checksum source becomes available another way, switch
  `appimage/build.sh` to pin it and delete this paragraph.
- §15.5 is left as written — the Compose-plugin statement it makes is still
  correct — with this ADR referenced from `appimage/build.sh` at the point
  of the download.

## Alternatives considered

- **Use the old `AppImageKit` release 13 instead of `continuous`:** trades
  an unpinnable-but-current binary for a pinnable-but-obsolete one; the
  publisher's own "should not be used anymore" notice makes this the worse
  trade, not a safer one.
- **Vendor a locally-built `appimagetool`:** `AppImage/appimagetool` itself
  depends on a squashfs/runtime toolchain that is its own unpinned-supply-
  chain problem one layer down, for no net improvement.
- **Skip AppImage, keep only the flat-pack tarball:** the safest option, and
  the one to fall back to if the `continuous` download becomes unreliable in
  practice — rejected for now because a single-file, `chmod +x`-and-run
  artifact is a strictly better fit for the "no installer, no package
  manager" audience than an archive to unpack, and the user explicitly asked
  for it.
