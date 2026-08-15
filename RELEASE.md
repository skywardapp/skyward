# Release engineering (M7)

Runbook for design-doc §15.4/§15.5/§18's M7 milestone (issue #8). Split into
two halves on purpose: the top half is what Gradle/CI now automates; the
bottom half is what only the owner can do, because it needs a government ID,
a credit card, or a volunteer reviewer on the other end — none of which a
CI job or an agent can supply. Start the bottom half now regardless of where
the top half stands: §18's calendar dependency is real, and every day it
doesn't start is a day added to the critical path.

## What's automated already

- **`checkFlavourManifestParity`** (`androidApp/build.gradle.kts`) — foss/play
  merged manifests may only diverge on the exact-alarm permission entries.
  Runs as part of `./gradlew check`.
- **`checkFlavourDependencyParity`** (`androidApp/build.gradle.kts`) —
  foss/play release variants must resolve to identical dependency sets
  (§17.5b(c)/D13). Runs as part of `./gradlew check`.
- **`checkDependencyLicenses`** (root `build.gradle.kts`) — fails the build on
  any shipped dependency whose licence isn't on the §16 allowlist.
- **`tools/ci/check-reproducible-build.sh`** — builds `fossRelease` twice from
  a clean workspace and asserts the two APKs are reproducible (§15.4/§17.5b):
  byte-for-byte identical first, and only if that fails, identical *content*
  once the v1 jar-signing entries (`META-INF/MANIFEST.MF` and the
  `*.RSA`/`*.DSA`/`*.EC`/`*.SF` certificate/signature files) are stripped —
  weaker, and logged as such, since it can miss zip-level nondeterminism the
  byte comparison would have caught. Runs in its own CI job
  (`.github/workflows/ci.yml`, `reproducible-build`) and locally:
  ```sh
  tools/ci/check-reproducible-build.sh
  ```
  It works with or without a real signing key configured — see below.
- **Versioning from git tags** (root `build.gradle.kts`) — `versionCode`,
  `versionName` and the desktop `packageVersion` are all derived from the
  latest `vMAJOR.MINOR.PATCH` tag reachable from the build's commit, so a
  release is cut by pushing a tag and no build file has a version to bump.
  `versionCode` is `MAJOR * 1000000 + MINOR * 1000 + PATCH` (`v0.1.0` → 1000),
  which keeps it monotonic and decodable; an untagged build gets `0.0.0-dev`
  and `versionCode` 1, deliberately below any real release so it is rejected as
  a downgrade rather than published over one. `-PskywardVersionName` /
  `-PskywardVersionCode` override the derivation where git history isn't
  available (a source tarball, or a pinned F-Droid recipe).

  **The encoding is positional, so components are bounded: minor and patch
  ≤ 999, major ≤ 2100** (Android's `versionCode` ceiling is 2100000000), and
  `v0.0.0` is invalid since Android requires `versionCode` ≥ 1. Out of range,
  two releases collide on one `versionCode` — `v0.0.1000` and `v0.1.0` both
  encode to 1000 — so the build and both workflows reject such a tag instead.
  A version code is permanent once published (no store accepts a re-used or
  lowered one), which is why this fails loudly rather than warning. Since
  `auto-tag-main` bumps the patch on every push to `main`, the patch bound is
  the one you'll actually meet: cut the next minor tag by hand
  (`git tag v0.2.0 && git push origin --tags`) to reset the patch field.

  Because the version is read from `git describe`, any build that needs the
  real version needs the tags fetched — CI checkouts use `fetch-depth: 0` for
  exactly this reason. It stays reproducible (§15.4/§17.5b) because it is a
  pure function of the commit: working-tree dirtiness is not part of it.
- **Release automation** (`.github/workflows/`) — `auto-tag-main.yml` pushes an
  incrementing patch tag on every push to `main` and calls
  `release-on-tag.yml`, which builds the `fossRelease` APK and a Linux desktop
  "flat pack" (the `createReleaseDistributable` jlinked tree, tarred up
  as-is — no installer, unpack and run `bin/skyward`) for that tag and
  publishes a GitHub Release with generated notes and both attached.
  Pushing a `v*` tag by hand does the same thing on its own. The publish job
  refuses to run without all four `SKYWARD_RELEASE_*` secrets (item 1 below)
  — an unsigned APK can't be installed on Android at all, so until the key
  exists this job fails loudly instead of publishing a broken download (this
  currently blocks the flat pack too, since it's built in the same job).
  It also only ever *creates* a release: once a tag has one, the workflow
  refuses to touch its assets rather than overwrite them, matching item 6
  below (and, once that setting is on, required by it) — a tag whose release
  never got created (missing secrets, a build failure) is the one case
  `workflow_dispatch` can usefully re-run.
- **Release signing wiring** (`androidApp/build.gradle.kts`) — reads a
  keystore from `keystore.properties` (gitignored, see
  `keystore.properties.example`) or `SKYWARD_RELEASE_STORE_FILE` /
  `SKYWARD_RELEASE_STORE_PASSWORD` / `SKYWARD_RELEASE_KEY_ALIAS` /
  `SKYWARD_RELEASE_KEY_PASSWORD` env vars, and applies it to both flavours'
  `release` build type (§15.4 step 3: one key, both stores). Absent both,
  `assembleFossRelease`/`assemblePlayRelease` still produce a valid unsigned
  APK — enough for CI's checks above, not enough to publish.
- **Flatpak, `.deb`/`.rpm`, About screen, NOTICE** — already shipped with M6
  (`flatpak/`, `desktopApp/build.gradle.kts`'s `nativeDistributions`,
  `AboutScreen.kt`/`AboutSection.kt`, `NOTICE`). §18's M7 accept criterion
  "`flatpak-builder` produces an installable bundle" is exercised locally via
  `flatpak/build.sh`; CI builds the tree it packages
  (`createReleaseDistributable`) but doesn't run `flatpak-builder` itself —
  see the comment in `ci.yml` for why.

## What still needs the owner

Every item below needs a human with legal identity, payment method, or
standing in a review community — none of which this repo's automation can
stand in for. Ordered by how early each has to start, not by execution order.

### 0. Decide the app id (R9) — blocks everything else

`dev.fritze.skyward` is a placeholder applicationId. **The applicationId is
immutable after first publication on either store**, and Play additionally
locks the developer identity to whatever id is used first — decide it
before doing anything below, since a rename after the RFP/tester-gate has
started means starting over on both stores.

`Skyward` (the store-listing display name/title) is a separate, much lower-
stakes decision: both stores let you change it after publication without
touching the applicationId. Still worth deciding deliberately up front —
the F-Droid RFP and metadata reference it as filed — but it isn't the R9
gate; the applicationId is.

If the id changes: update `applicationId`/`namespace` in
`androidApp/build.gradle.kts`, `flatpak/dev.fritze.Skyward.*`'s reverse-DNS
name, and `fastlane/metadata/android/en-US/title.txt`.

### 1. Generate the signing key (§15.4 step 1)

Gates Play publication and Android Developer Verification, and gates
F-Droid's *developer-signed* republish (item 3's step 3) — but not starting
the F-Droid RFP itself, which only needs item 0. Do this in parallel with
item 3, not after it.

Locally, once, and keep it somewhere backed up and never committed:

```sh
keytool -genkey -v -keystore skyward-release.jks -alias skyward \
  -keyalg RSA -keysize 4096 -validity 10000
cp keystore.properties.example keystore.properties
# fill in keystore.properties with the real storeFile/passwords/alias
```

Losing this key means losing the ability to ship updates under the same
`applicationId` on either store — there is no recovery path. Back it up
somewhere durable and offline before doing anything else with it.

Then verify reproducibility holds with the real key too:
`tools/ci/check-reproducible-build.sh`.

### 2. Start the Play tester gate and identity verification now (calendar-critical)

This is the item §18 explicitly calls out as wall-clock, not developer-time —
a personal Play account created after 2023-11-13 needs a closed test with
**12 testers opted in continuously for 14 days** before production access
unlocks, and identity verification (government photo ID + proof of address)
runs on its own schedule. Neither speeds up by writing more code.

1. Create the Play Console account ($25 one-time) and start identity
   verification immediately.
2. Decide personal vs. organisation account: personal needs the 12×14-day
   tester gate above; organisation skips it but needs a D-U-N-S number
   (free, up to ~28 days) and publishes more contact detail. See §15.4 for
   the trade-off (personal accounts also publish the owner's legal name and
   country, and under EU trader-status rules likely address and phone).
3. Upload the `playRelease` AAB (`./gradlew :androidApp:bundlePlayRelease`,
   once step 1's key is wired) to the closed test track and start recruiting
   12 testers, opted in continuously for 14 days.
4. Upload a *copy* of the local signing key to Play App Signing via Google's
   **PEPK** tool, rather than letting Play generate its own — this is what
   keeps the Play-signed artifact and the F-Droid-signed artifact sharing one
   identity (§15.4 step 1). PEPK download and instructions:
   Play Console → Setup → App integrity → App signing.
5. Host the privacy policy at a stable, public, non-PDF URL (GitHub Pages is
   fine per §15.4) and link it both in Console and the in-app About screen
   (§13.1 — `AboutScreen.kt`/`AboutSection.kt` already have a slot for it,
   currently unpopulated).
6. Fill in the Data Safety form (declare no collection — accurate today; keep
   it accurate as sources are added, R13) and the IARC content rating
   questionnaire, and declare the app not child-targeted.

### 3. F-Droid RFP + `fdroiddata` merge request

The RFP and volunteer review below need only item 0 (the app id) — F-Droid
initially builds and signs with *its own* key, so this doesn't wait on item
1. What does need item 1 is reproducibility: F-Droid only switches to
publishing the *developer-signed* APK (§15.4's whole point — the thing that
makes cross-store updates work) once it can verify a build against the real
key, so get item 1 done before or during review rather than after.

1. File a "Request For Packaging" (RFP) issue against
   [`fdroiddata`](https://gitlab.com/fdroid/fdroiddata) once the app id (item
   0) is final.
2. Prepare the F-Droid build recipe (a `metadata/<applicationId>.yml` in
   `fdroiddata`, not in this repo) pointing at the `fossRelease` build and
   this repo's tagged releases. `fastlane/metadata/android/en-US/` is already
   in place and is what F-Droid reads for the store listing (§15.4) —
   nothing to prepare there beyond adding a `changelogs/<versionCode>.txt`
   per release, where the version code is derived from the tag
   (`fastlane/README.md` has the arithmetic).
3. Open the merge request. This is volunteer-reviewed — **budget weeks, not
   days** (§15.4). Once merged, publishing typically follows in 24–48h
   (F-Droid's own estimate; actual timing varies with their build queue —
   check [F-Droid Monitor](https://monitor.f-droid.org/) for current status),
   and later version bumps are picked up automatically via `UpdateCheckMode`.
4. Locally verify before submitting: `fdroid build` in the official F-Droid
   builder Docker image (§18's accept criterion) — this needs the
   `fdroidserver` tooling and is intentionally not part of this repo's own
   CI, since it depends on the `fdroiddata` recipe living in the other repo.

### 4. Android Developer Verification (R11) — hard deadline

Registration binds *package name + signing key* — one registration if item 1
puts the same key on both stores. Enforcement starts **2026-09-30** in
Brazil/Indonesia/Singapore/Thailand and expands globally through 2027;
unregistered apps degrade to advanced-flow-only installs on certified
devices in enforcing regions.

This is flagged in the design doc as a **values decision, not just a
technical one** (F-Droid formally opposes the scheme) — get explicit owner
sign-off before registering, not just before the deadline.

### 5. Flatpak submission

`flatpak/` is ready (manifest, `.desktop`, AppStream metainfo, icon — see
`flatpak/README.md`). Submitting to Flathub is a separate PR against
[`flathub/flathub`](https://github.com/flathub/flathub) proposing
`dev.fritze.Skyward.yml` (or the final app id from item 0), reviewed by
Flathub maintainers. Verify locally first:

```sh
flatpak/build.sh
```

### 6. Enable Immutable Releases on the repo

GitHub's [Immutable Releases](https://github.blog/changelog/2025-08-26-releases-now-support-immutability-in-public-preview/)
(public preview) locks a release's tag and assets the moment it publishes —
none of them can be added, replaced or deleted afterwards, including by an
admin. This is a one-time toggle, either in **repo Settings → General →
"Releases"**, or via the REST API (`PUT /repos/{owner}/{repo}/immutable-releases`,
needs admin access; `GET` on the same path checks current status). Either
way, `release-on-tag.yml` (above) is already written to only ever create a
release and never overwrite one, so turning this on changes nothing about
how it runs — it just makes GitHub enforce, at the platform level, what the
workflow already refuses to do itself.

Independent of every other item here — no dependency either way — so do it
whenever, ideally before the first tag ships: the setting only protects
releases published *after* it's turned on, so anything already published
when it's flipped stays exactly as mutable as before.

## Order of operations

Item 0 (app id) blocks 1, 2, 3's RFP, 4, and 5. Item 1 (signing key) blocks
2's step 3 onward and 4; it does *not* block starting item 3 — the RFP and
volunteer review only need the app id, and only F-Droid's later switch to
publishing the developer-signed APK needs the real key, so run 1 and 3 in
parallel rather than sequencing them. Start item 2 as early as possible once
0 is done — its 12×14-day tester gate is the longest pole regardless of
what else is in flight. Item 4 needs 1 and should be scheduled well before
2026-09-30. Item 5 is independent of the Android-side items entirely. So is
item 6 — it has no dependency on anything above, and nothing above depends
on it; flip it whenever, ideally before the first tag ships.
