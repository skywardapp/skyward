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

## Version metadata

`build.sh` asks the tree it is packaging what version it is
(`bin/skyward --version`, ADR 0020) and uses the answer twice: it stamps the
AppStream `<release>` entry with it, and passes it to `appimagetool` as
`VERSION`, which is what puts `X-AppImage-Version` into the packaged desktop
entry. Without the latter the AppImage carries no version of its own at all —
the release asset's filename is written by the workflow, so a downloaded and
renamed copy would be unidentifiable.

The output is named `skyward-<version>-x86_64.AppImage`, the
`$APPNAME-$VERSION-$ARCH` shape AppImage's own tooling expects.
`release-on-tag.yml` overrides it with `APPIMAGE_OUTPUT` to carry the tag, and
then unpacks the file it is about to publish to check that both versions
inside it agree with that tag.

## Updates

Set `APPIMAGE_UPDATE_INFO` to embed
[AppImageUpdate](https://github.com/AppImage/AppImageUpdate) update
information, which also makes `appimagetool` emit a `.zsync` file beside the
output for delta updates. `release-on-tag.yml` sets it to
`gh-releases-zsync|skywardapp|skyward|latest|skyward-*-x86_64.AppImage.zsync`
and publishes the `.zsync` alongside the AppImage, so an installed copy can
find and fetch the next release itself. Nothing else updates an AppImage:
there is no package manager behind it.

It is left unset for local builds — it names a release channel only the
release workflow can answer for — and `build.sh` refuses to proceed if it is
set without `zsyncmake` (the `zsync` package) installed, rather than
advertising updates it publishes no `.zsync` for.

## Validation

`build.sh` runs `desktop-file-validate` over the AppDir's desktop entry when
it is installed (fatal), and `appstreamcli validate` over the metainfo
(advisory — the metadata is knowingly missing the screenshots Flathub
requires, see `flatpak/README.md`, and failing every build over a gap that is
already tracked would help nobody). `appimagetool` is invoked with
`--no-appstream` for the same reason.

## Running the output

```sh
APPIMAGE_EXTRACT_AND_RUN=1 build/appimage/skyward-<version>-x86_64.AppImage
```

The extract-and-run env var/flag isn't optional in most CI environments —
GitHub-hosted runners aren't guaranteed to have FUSE wired up for
unprivileged mounts. `build.sh` uses it for the same reason when invoking
`appimagetool` itself (also distributed as an AppImage).

## Known limitations

Release minification is off, same as Flatpak (see
[ADR 0007](../docs/adr/0007-desktop-release-minification-off.md)), so the
jlinked tree this packages is unminified — self-contained and working, just
larger than §15.5 intends.

**glibc baseline.** AppImage's own
[best practices](https://docs.appimage.org/reference/best-practices.html) ask
that an AppImage be built on the oldest base system it is meant to run on,
because glibc breaks forward compatibility routinely: a binary built against a
newer glibc will not start against an older one. `release-on-tag.yml` builds on
`ubuntu-latest`, so the published AppImage — and the flat-pack tarball, which
comes off the same tree — requires at least that runner image's glibc, and will
not start on, say, Debian stable a release behind. The bundled JRE does not
help: it is itself dynamically linked against the build host's glibc.

Pinning the job to an older runner label is not the fix — `ubuntu-22.04` begins
[deprecation in September 2026](https://github.com/actions/runner-images/issues/14254),
which only moves the same problem. Building the desktop tree inside a container
(`debian:bookworm` or similar) decouples the baseline from whatever
`ubuntu-latest` currently means, and is the change to make if this starts
costing users. Until then the constraint is at least written down rather than
discovered on a failed launch.
