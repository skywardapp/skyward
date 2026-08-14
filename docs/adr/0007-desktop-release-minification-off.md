# ADR 0007: Desktop release minification is off — ProGuard miscompiles this dependency set

**Status:** Accepted for M6; revisit at M7 (§18's release-engineering milestone)

## Context

§15.5 specifies, for desktop packaging:

> ProGuard release minification: enabled, keep rules for kotlinx-serialization
> + reflection-free config.

M6's own acceptance criterion is that the app "runs under Flatpak locally",
and Flatpak packages the `createReleaseDistributable` tree — i.e. the
minified one. So the two requirements meet in the same artifact, and this is
the milestone where they were first exercised together.

They do not currently coexist. Running the packaged binary (not merely
building it) surfaced three independent ProGuard miscompilations, on both
7.7.0 and 7.4.2:

1. **The optimizer breaks `EclipseSource.refresh`.** §7.1.3's path sampler is
   a large suspend function with many simultaneously-live `double` locals.
   ProGuard's local-variable reallocation rewrites it into bytecode whose
   stack map no longer matches:
   `VerifyError: Instruction type does not match stack map ... Type top
   (current frame, locals[98]) is not assignable to double`.
   The targeted `-optimizations !code/allocation/variable` is *not* enough;
   only `-dontoptimize` avoids it.

2. **The shrinker breaks `kotlinx.coroutines.JobSupport`.** It deletes
   `Job` from `JobSupport`'s *direct* interface list as redundant — `ChildJob`
   already extends `Job` — but `JobSupport.cancel()` is compiled as an
   `invokespecial` to `Job`'s JVM-default method, and the JVM requires that
   target to be a *direct* superinterface:
   `VerifyError: Bad invokespecial instruction: interface method reference is
   in an indirect superinterface`, thrown the moment the app constructs its
   application scope. Verified by decompiling the shrunk jar: the interface
   really is gone. `-keep class kotlinx.coroutines.** { *; }` does not
   prevent it — the redundant-interface removal is unconditional — and only
   `-dontshrink` does.

3. **Service-loaded providers vanish.** Ktor finds its engine and its
   kotlinx-serialization extension through `META-INF/services`. Those files
   survive shrinking, but nothing references the classes they name
   statically, so the classes are removed and the app dies constructing its
   first source. This one *is* a configuration problem, and keep rules fix
   it.

(3) is fixed in `desktopApp/proguard-rules.pro`. (1) and (2) can only be
avoided together by `-dontoptimize` plus `-dontshrink`, which leaves ProGuard
doing nothing but renaming — no size benefit, and renaming is itself a risk
around the reflective paths the app depends on (kotlinx-serialization,
JDBC/Xerial, the tray's JNI bridges).

## Decision

Ship M6 with `buildTypes.release.proguard.isEnabled = false`, and keep
`proguard-rules.pro` written and wired via `configurationFiles` so
re-enabling is a one-line change rather than a rediscovery exercise. The
reasoning is recorded in `desktopApp/build.gradle.kts` at the point of the
flag, so nobody has to find this ADR to understand the `false`.

A packaged app that does not start fails M6's acceptance criterion outright.
Minification is a size optimisation; correctness is not negotiable against
it.

## Consequences

- The desktop distributable is larger than §15.5 intends (~157 MB of jlinked
  tree, of which the JRE and skiko dominate — minification would not have
  changed that order of magnitude anyway).
- M7 should retry: (2) is upstream behaviour that a kotlinx-coroutines or
  ProGuard release may change, and it is the only blocker that cannot be
  configured around. The retest is cheap and specific — build
  `createReleaseDistributable` and *run* it; each of the three failures above
  reproduces within seconds of startup, and the packaged binary's
  `debug-matches` command exercises (1) on its own.
- Nothing about Android's minification is affected; this is the desktop
  ProGuard configuration only.

## Alternatives considered

- **Pin an older ProGuard** (`version.set("7.4.2")`): tried, identical
  failures.
- **`-dontshrink` alone**: fixes (2), leaves (1). Both flags together leave
  no benefit.
- **Drop kotlinx-coroutines' JVM-default interfaces**: not ours to change.
- **Exclude single jars from processing**: the Compose plugin generates the
  ProGuard `-injars` list itself and exposes no per-jar opt-out.
