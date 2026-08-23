# AppImage packaging (§15.5, ADR 0019)

A single-file, `chmod +x`-and-run download — the same "no installer, no
package manager" niche as the flat-pack tarball
(`skyward-<tag>-linux-x64.tar.gz`), just as one executable instead of an
archive to unpack. Flatpak stays the primary, sandboxed desktop channel;
this is a secondary option for the GitHub Release page.

```sh
./build.sh                 # gradle → jlinked tree → AppDir → appimagetool
```

or by hand:

```sh
./gradlew :desktopApp:createReleaseDistributable
# then assemble an AppDir per build.sh and run appimagetool over it
```

## Files

| File | What it is |
|---|---|
| `AppRun` | The AppDir entry point — `exec`s the jlinked binary, resolving its own location via `$APPDIR` (set by the AppImage runtime) rather than assuming a fixed path. |
| `build.sh` | Runs the Gradle build, assembles the AppDir (reusing `flatpak/dev.fritze.Skyward.desktop`, `.metainfo.xml` and `icon.svg` rather than forking copies), and invokes `appimagetool`. |

There's no local `.desktop`/icon/metainfo copy here — `build.sh` installs
straight from `flatpak/`, so those stay a single source of truth instead of
two files that can quietly drift apart.

## The `appimagetool` download

Pinned the same way everything else this repo trusts is pinned (the Gradle
wrapper's `distributionSha256Sum`, every GitHub Action by commit SHA):
`build.sh` downloads `AppImage/appimagetool`'s numbered `1.9.1` release tag
(not the rolling `continuous` one) and verifies it against a hardcoded
SHA-256 before using it, cached under `build/appimage/`. Set
`APPIMAGETOOL=/path/to/a/vetted/appimagetool` to skip the download and
verification entirely and use a copy you trust instead. Bumping the version
means updating both `appimagetool_version` and `appimagetool_sha256` in
`build.sh` — see [ADR 0019](../docs/adr/0019-appimage-desktop-packaging.md).

`appimagetool` itself also fetches the AppImage runtime it embeds in the
output over the network at packaging time (from `AppImage/type2-runtime`)
unless `--runtime-file` is passed. That fetch isn't pinned here; see the ADR.

`build.sh` never has the release signing secrets in its environment when it
runs `appimagetool` — see `SKIP_GRADLE_BUILD` in the ADR's Decision section
for why that matters and how it's kept that way.

## Running the output

```sh
APPIMAGE_EXTRACT_AND_RUN=1 build/appimage/Skyward-x86_64.AppImage
```

The extract-and-run env var/flag isn't optional in most CI environments —
GitHub-hosted runners aren't guaranteed to have FUSE wired up for
unprivileged mounts. `build.sh` uses it for the same reason when invoking
`appimagetool` itself (also distributed as an AppImage).

## Known limitation

Same as Flatpak: release minification is off (see
[ADR 0007](../docs/adr/0007-desktop-release-minification-off.md)), so the
jlinked tree this packages is unminified — self-contained and working, just
larger than §15.5 intends.
