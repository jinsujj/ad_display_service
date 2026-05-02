package com.owldev.adsignage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * AC 13 — Android APK auto-starts on BOOT_COMPLETED.
 *
 * Auto-launches the signage player once the device finishes booting so a
 * fridge-mounted unit resumes playback on its own after every power cycle
 * (planned reboot, store-hours power switch, breaker trip, etc.) without
 * any operator intervention.
 *
 * Boot intents accepted (declared in AndroidManifest.xml):
 *   - android.intent.action.BOOT_COMPLETED            (stock Android)
 *   - android.intent.action.QUICKBOOT_POWERON         (Xiaomi / generic)
 *   - com.htc.intent.action.QUICKBOOT_POWERON         (HTC)
 *   - android.intent.action.REBOOT                    (some custom ROMs)
 *
 * The receiver also pre-warms the persistent device_id (AC 12) so the UUID
 * is ready before MainActivity's WebView starts loading the player page —
 * avoids an extra disk read on the first frame.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (!isBootAction(action)) {
            Log.w(TAG, "Ignoring non-boot intent: $action")
            return
        }

        Log.i(TAG, "Boot intent received: $action — launching player")

        // Make sure device_id is persisted as early as possible after boot.
        // Safe to call from a BroadcastReceiver: getOrCreateDeviceId is
        // synchronized and uses commit() (synchronous) under the hood.
        DeviceIdManager.getOrCreateDeviceId(context.applicationContext)

        launchPlayer(context.applicationContext)
    }

    private fun launchPlayer(appContext: Context) {
        // FLAG_ACTIVITY_NEW_TASK is mandatory when starting an Activity
        // from a non-Activity context (BroadcastReceiver). CLEAR_TOP +
        // SINGLE_TOP make sure we don't stack duplicate MainActivity
        // instances if the user later launches via the home screen.
        val launch = Intent(appContext, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }

        try {
            appContext.startActivity(launch)
        } catch (t: Throwable) {
            // On Android 10+ background activity starts are restricted.
            // For signage devices this is normally fine because:
            //   (a) BOOT_COMPLETED is a documented exception on most OEMs,
            //   (b) the app is also registered as HOME (see manifest),
            //       so the system will resolve our MainActivity as the
            //       launcher right after boot finishes.
            // We still log so a failure is visible in `adb logcat` during
            // QA / demo setup.
            Log.e(TAG, "Failed to auto-launch MainActivity from boot receiver", t)
        }
    }

    private fun isBootAction(action: String): Boolean = action in BOOT_ACTIONS

    companion object {
        private const val TAG = "AdSignageBoot"

        /**
         * Boot intents this receiver responds to. Kept in sync with the
         * <intent-filter> entries in AndroidManifest.xml. Exposed for tests.
         */
        val BOOT_ACTIONS: Set<String> = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.REBOOT"
        )
    }
}
