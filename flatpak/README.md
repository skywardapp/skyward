# Flatpak packaging (§15.5)

Flatpak is the **primary** desktop distribution channel. The manifest here
packages a *prebuilt* tree rather than building from source, because
Flathub's build sandbox has no network and a Gradle build needs one — the
"repackage `createReleaseDistributable`" pattern §15.5 calls for.

```sh
./build.sh                 # gradle → jlinked tree → flatpak-builder
```

or by hand:

```sh
./gradlew :desktopApp:createReleaseDistributable
flatpak-builder --user --install --force-clean build/flatpak \
  flatpak/dev.fritze.Skyward.yml
```

## Files

| File | What it is |
|---|---|
| `dev.fritze.Skyward.yml` | The manifest. Its `dir` source points at `desktopApp/build/compose/binaries/main-release/app/skyward`. |
| `build.sh` | Runs both steps in order and fails loudly if the tree isn't where the manifest expects. |
| `skyward.sh` | The `/app/bin/skyward` launcher — `exec`s the jlinked binary. |
| `dev.fritze.Skyward.desktop` | Desktop entry (also the `launchable` the AppStream metadata points at). |
| `dev.fritze.Skyward.metainfo.xml` | AppStream metadata, required by Flathub. Its `<release>` version is a placeholder — see "Version metadata" below. |
| `icon.svg` | App icon; the same crescent-and-star the tray draws. |
| `flathub.json` | Restricts Flathub builds to `x86_64`. The manifest packages a prebuilt tree that only exists for that architecture, so an aarch64 build would fail rather than produce anything. Copy it into the Flathub submission repo, where it lives at the root. |

## Version metadata

The checked-in metainfo's `<release version="…" date="…">` is a **placeholder**
and stamping it is `build.sh`'s job, not a reviewer's: it asks the tree being
packaged what version it is (`bin/skyward --version`) and rewrites the entry
via `tools/packaging/stamp-metainfo.sh` into `build/packaging/`, which is where
the manifest's `sources` picks it up. See
[ADR 0020](../docs/adr/0020-package-metadata-version-from-the-packaged-binary.md)
— a tag is cut on every push to `main`, and a hand-maintained version here was
41 patch releases stale before anyone noticed.

Building by hand therefore means stamping first:

```sh
./gradlew :desktopApp:createReleaseDistributable
tools/packaging/stamp-metainfo.sh \
  "$(desktopApp/build/compose/binaries/main-release/app/skyward/bin/skyward --version | awk '{print $NF}')" \
  build/packaging/dev.fritze.Skyward.metainfo.xml
flatpak-builder --user --install --force-clean build/flatpak \
  flatpak/dev.fritze.Skyward.yml
```

or just run `./build.sh`, which does all three.

## Sandbox permissions

Every `finish-arg` in the manifest is there for a specific §10.3 behaviour,
and the list is deliberately short:

- `--share=network` — unrestricted outbound network. Flatpak has no per-host
  filter, so P1's "NOAA/NASA/JPL only" is enforced by the app's source list,
  not by the sandbox.
- `--socket=wayland`, `--socket=fallback-x11` — the window.
- `--share=ipc` — the pairing flatpak's own docs call for alongside X11:
  without it the X11 shared-memory extension is unavailable, which is "very
  bad for X11 performance". No effect under Wayland.
- `--device=dri` — the GPU node. Compose Desktop renders through Skiko, which
  uses OpenGL on Linux; without this Mesa falls back to llvmpipe and the app
  still starts, which is what makes the omission easy to miss and unpleasant
  to use.
- `--talk-name=org.freedesktop.Notifications` — reminders.
- `--talk-name=org.kde.StatusNotifierWatcher` — the tray icon.
- `--filesystem=xdg-download` — §12 export/import. Swing's `JFileChooser` is
  not portal-aware: it browses the sandbox's own filesystem view, so without
  a grant there is nowhere to write an export to. `~/Downloads` is the
  narrowest place that is still a sensible default; this is deliberately not
  `--filesystem=home`. Switching the pickers to the FileChooser portal would
  remove the grant entirely — a good M7 change.

The Background portal (*optional* autostart, requested only when the user
enables it) and OpenURI (the JPL/EONET links on the event detail pane) need
no entries: flatpak's default policy already permits talking to
`org.freedesktop.portal.Desktop`.

## Before submitting to Flathub

- **Screenshots are missing, and they are a hard requirement**: "All graphical
  applications must have one or more screenshots in the MetaInfo." They need a
  stable public URL for the images, which this repository does not host, so the
  `<screenshots>` block is absent rather than pointing at URLs that would not
  resolve. This blocks submission (RELEASE.md item 5).
- **Check the runtime is still current.** Flathub requires the latest available
  runtime at submission time; the manifest tracks `25.08`, and a freedesktop
  branch goes EOL two years after its August release.
- **Run the linter**, which checks both of the above and more:
  `flatpak run --command=flatpak-builder-lint org.flatpak.Builder manifest flatpak/dev.fritze.Skyward.yml`.
  Nothing in CI runs it — or `flatpak-builder` at all — so this is a local step.

## Known limitation

Release minification is currently off — see
[ADR 0007](../docs/adr/0007-desktop-release-minification-off.md). The tree
this manifest packages is therefore unminified; it is still self-contained
and still starts, which minified builds did not.
