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
| `dev.fritze.Skyward.metainfo.xml` | AppStream metadata, required by Flathub. |
| `icon.svg` | App icon; the same crescent-and-star the tray draws. |

## Sandbox permissions

Every `finish-arg` in the manifest is there for a specific §10.3 behaviour,
and the list is deliberately short:

- `--share=network` — HTTPS to NOAA/NASA/JPL, and nothing else (P1).
- `--socket=wayland`, `--socket=fallback-x11` — the window.
- `--talk-name=org.freedesktop.Notifications` — reminders.
- `--talk-name=org.kde.StatusNotifierWatcher` — the tray icon.
- `--talk-name=org.freedesktop.portal.Background` — *optional* autostart,
  requested only when the user enables it. Not blanket background access.
- `--talk-name=org.freedesktop.portal.FileChooser` — §12 export/import.
  Note there is no `--filesystem=` grant: the portal hands back only the
  file the user picked.
- `--talk-name=org.freedesktop.portal.OpenURI` — the JPL/EONET links on the
  event detail pane.

## Known limitation

Release minification is currently off — see
[ADR 0007](../docs/adr/0007-desktop-release-minification-off.md). The tree
this manifest packages is therefore unminified; it is still self-contained
and still starts, which minified builds did not.
