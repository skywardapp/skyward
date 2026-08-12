# Flatpak packaging (§15.5)

Placeholder only at this milestone (M0) — `desktopApp` is a hello-world
window, not something worth packaging yet. The real manifest lands in M6/M7,
following the "repackage the prebuilt jlinked tree" pattern described in
§15.5 (network-isolated Flathub builds can't run Gradle, so
`createReleaseDistributable`'s output tree gets packaged by flatpak-builder,
not built from source inside the sandbox).

Files here are skeletons to fix the shape of the final layout early, per
§15.1's "cheap now, tedious to retrofit" reasoning for the repo layout —
none of them are exercised by CI yet.
