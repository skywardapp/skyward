# Store listing metadata

F-Droid reads `metadata/android/en-US/` directly from this repo (§15.4); the
same copy is reused for the Play listing. Screenshots
(`metadata/android/en-US/images/phoneScreenshots/`) are added once there's a
real UI to screenshot (M3+) — an app with no functional screens yet has
nothing honest to show.

`changelogs/<versionCode>.txt` must exist for every release; add one per
bump to `androidApp`'s `versionCode` (`androidApp/build.gradle.kts`).
