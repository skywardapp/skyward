# Store listing metadata

F-Droid reads `metadata/android/en-US/` directly from this repo (§15.4); the
same copy is reused for the Play listing. Screenshots
(`metadata/android/en-US/images/phoneScreenshots/`) are added once there's a
real UI to screenshot (M3+) — an app with no functional screens yet has
nothing honest to show.

`changelogs/<versionCode>.txt` must exist for every release; add one per
release tag. The version code is derived from that tag rather than stored in a
build file (see the version block in the root `build.gradle.kts`):

    vMAJOR.MINOR.PATCH -> MAJOR * 1000000 + MINOR * 1000 + PATCH

so `v0.1.0` is `1000.txt`, `v0.2.0` is `2000.txt`, `v1.2.3` is `1002003.txt`.
`./gradlew :androidApp:properties | grep versionCode` prints the current one if
you'd rather not do the arithmetic.
