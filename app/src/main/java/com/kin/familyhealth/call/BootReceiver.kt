package com.kin.familyhealth.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "CallBootReceiver"

/**
 * BOOT_COMPLETED receiver (manifest-declared by FOUNDATION; do not edit the manifest).
 *
 * Kept intentionally minimal and safe:
 *  - No call state to restore across reboot — a call in progress does not survive a
 *    reboot, and [CallForegroundService] is only ever started by an explicit FCM push or
 *    a user tapping "Reach in", neither of which needs re-arming here.
 *  - [CallAccessibilityService] is a system-managed accessibility service — the OS
 *    restarts it on its own if the user has enabled it in Settings; nothing to do here.
 *  - The only thing worth doing on boot is a best-effort nudge to refresh the FCM token,
 *    since a fresh boot occasionally invalidates a stale token faster than the natural
 *    refresh cycle. That refresh itself is AGENT-SYNC's (KinMessagingService.onNewToken)
 *    responsibility — we only log here so this stays a true no-op from AGENT-CALL's side
 *    and never reaches into another package's classes.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Re-arm the serverless reach-in listener after a reboot so the phone can be
        // reached without anyone opening the app. (Android 14 permits starting a
        // specialUse foreground service from BOOT_COMPLETED.)
        Log.i(TAG, "Boot completed; starting StandbyService.")
        StandbyService.start(context)
    }
}
