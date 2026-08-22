package dev.fritze.skyward.alarm

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * §17.5's "`canScheduleExact() == false` ... this is the default state of the
 * `play` flavour and must not be an afterthought", driven against the real OS
 * rather than a fake.
 *
 * The shell holds `MANAGE_APP_OPS_MODES`, so it can move
 * `OP_SCHEDULE_EXACT_ALARM` -- which is what
 * `AlarmManager.canScheduleExactAlarms()` reads, and therefore what
 * [AndroidAlarmScheduler] branches on. Two consequences worth knowing before
 * reading the tests:
 *
 * - `AlarmManagerService` watches this op and, on a move *to* allowed, sends
 *   `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` itself. Granting
 *   here is therefore a real OS dispatch to the real, exported
 *   [ExactAlarmPermissionReceiver] -- no simulated broadcast required. A
 *   *revocation* sends nothing, which is exactly the asymmetry
 *   [AndroidAlarmScheduler]'s standing-WorkManager-backup comment exists for.
 * - `USE_EXACT_ALARM` outranks the op: `canScheduleExactAlarms()` returns true
 *   for a package holding it without ever consulting app-ops. The `foss`
 *   flavour holds it from API 33 (§10.2/D13), so on foss at API 33+ denial is
 *   *not producible at all* -- see [exactAlarmDenialIsProducible].
 */
internal fun Context.allowExactAlarms() {
    // The op does not exist before API 31; `cmd appops` then errors out rather
    // than no-opping, so callers below S must not reach here.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    shell("cmd appops set $packageName SCHEDULE_EXACT_ALARM allow")
}

/**
 * There is deliberately no `denyExactAlarms()` and no restore-to-default.
 *
 * `AlarmManagerService` watches this op, and on any move *away* from allowed it
 * both drops the package's exact alarms and kills its uid. The instrumentation
 * runs in that uid, so a test that revokes takes the whole run down with it --
 * twice observed on play/API 34: once from a test that granted then revoked in
 * its body, and once from an `@After` that merely restored the default after
 * granting (#55).
 *
 * The op is therefore one-way here: a suite may **grant** it, and must then
 * leave it granted for the rest of the process. Anything needing the denied
 * state reads it from the flavour's own install default instead of arranging
 * it -- which is what §17.5 actually asks about, and is free on `play`.
 * Tests that would be misled by a grant left behind guard with
 * [exactAlarmOpIsAtInstallDefault]. See ADR 0018.
 */
internal fun Context.exactAlarmOpIsAtInstallDefault(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    // `cmd appops get` prints e.g. "SCHEDULE_EXACT_ALARM: allow" once set, and
    // reports no operations while the op is still permission-derived.
    val mode = shell("cmd appops get $packageName SCHEDULE_EXACT_ALARM")
    return !mode.contains("allow") && !mode.contains("ignore")
}

/**
 * Whether this flavour, on this API level, can actually be put into the denied
 * state. False means the test that needs it must skip with a stated reason
 * (`assumeTrue`), not silently pass:
 *
 * - below API 31 the concept does not exist and [AndroidAlarmScheduler]
 *   hardwires `canScheduleExact()` true;
 * - from API 33 the `foss` flavour holds `USE_EXACT_ALARM`, which outranks the
 *   app-op -- the permission working as designed, not a gap.
 *
 * Detects the flavour by reading the *merged manifest* back rather than by a
 * `BuildConfig` field or flavour-specific source: D13 and §15.1 allow the two
 * flavours to differ only in that manifest, and `checkFlavourManifestParity`
 * fails the build on anything else.
 */
internal fun exactAlarmDenialIsProducible(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && holdsUseExactAlarm(context))

/** True on the `foss` flavour, which declares `USE_EXACT_ALARM`; false on `play`, which deliberately does not. */
internal fun holdsUseExactAlarm(context: Context): Boolean {
    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
    return info.requestedPermissions?.contains(USE_EXACT_ALARM) == true
}

/** `Manifest.permission.USE_EXACT_ALARM` as a literal: the constant is API 33+, and this must compile against minSdk 26. */
private const val USE_EXACT_ALARM = "android.permission.USE_EXACT_ALARM"
