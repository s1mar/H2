package com.kin.familyhealth.call

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kin.familyhealth.MainActivity
import com.kin.familyhealth.core.CallLauncher
import com.kin.familyhealth.di.ServiceLocator
import com.kin.familyhealth.core.Constants

private const val TAG = "CallForegroundService"
private const val NOTIFICATION_ID = 4201
private const val WAKE_LOCK_TAG = "${Constants.APP_PACKAGE}:call"

/**
 * Foreground service hosting the live WebRTC session (ARCHITECTURE.md: background camera
 * access is blocked by the OS, so the call MUST run inside a foreground service with a
 * visible notification posted BEFORE the camera opens).
 *
 * Started by:
 *  - AGENT-SYNC's KinMessagingService via [CallLauncher.startIncomingCall] on a
 *    TYPE_EMERGENCY_CALL push (self-answer primary path).
 *  - The dashboard's "Reach in" button via [com.kin.familyhealth.call.OutgoingCall.reachIn]
 *    (which calls [CallLauncher.startOutgoingCall]).
 *
 * Flow on start:
 *  1. Read EXTRA_CALLER_ID / EXTRA_ROOM / EXTRA_IS_INCOMING.
 *  2. Immediately startForeground() with a CHANNEL_CALL notification (required before
 *     opening the camera).
 *  3. Acquire a short partial wake lock so the device doesn't sleep before the user/UI
 *     takes over (CallActivity keeps the screen on itself once launched).
 *  4. Create the [com.kin.familyhealth.call.webrtc.WebRtcSession] via [CallSessionHolder]
 *     and initialize it (opens camera/mic).
 *  5. Fire a full-screen-intent notification that launches [CallActivity] directly —
 *     this is the self-answer primary path; the user lands straight in the call UI,
 *     which auto-accepts on the incoming side.
 */
class CallForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callerId = intent?.getStringExtra(CallLauncher.EXTRA_CALLER_ID)
        val room = intent?.getStringExtra(CallLauncher.EXTRA_ROOM)
        val isIncoming = intent?.getBooleanExtra(CallLauncher.EXTRA_IS_INCOMING, false) ?: false

        if (callerId == null || room == null) {
            Log.w(TAG, "Missing EXTRA_CALLER_ID/EXTRA_ROOM; stopping.")
            stopSelf()
            return START_NOT_STICKY
        }

        // Step 2: startForeground() BEFORE any camera/mic access.
        // Android 14+ throws SecurityException if we start with the camera/microphone
        // foreground-service type without holding that runtime permission -- which would
        // crash the process and silently kill the emergency call. Start with exactly the
        // types we are allowed to use; with neither, never crash: tell the user what is
        // missing and bail out so the caller's 30s watchdog reports no-answer.
        val fgsType = grantedForegroundTypes()
        if (fgsType == 0) {
            Log.e(TAG, "Camera and microphone permissions both missing; cannot run a call.")
            postPermissionMissingNotification()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildCallNotification(callerId, room, isIncoming), fgsType
            )
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
            postPermissionMissingNotification()
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()

        // Self-heal: on a cold start triggered by the wake-push, the signaling client may
        // not have been installed yet. Resolve it now rather than failing into a dead
        // "Connecting..." screen with no video and no recovery.
        if (CallSessionHolder.signalingClient == null) {
            CallSessionHolder.signalingClient = ServiceLocator.signalingClient(applicationContext)
        }

        // Step 4: stand up the WebRTC session (this is what opens the camera).
        val session = runCatching {
            CallSessionHolder.ensureSession(applicationContext, room, callerId, isIncoming)
        }.onFailure { Log.e(TAG, "Failed to create WebRtcSession", it) }.getOrNull()

        if (session == null) {
            // Never launch the call screen without a session: it would sit on
            // "Connecting..." forever. Tear down cleanly instead.
            Log.e(TAG, "No WebRTC session; aborting call.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        session.initialize()
        if (isIncoming) session.startAsCallee() else session.startAsCaller()

        // Step 5: full-screen intent launches CallActivity directly (self-answer path).
        launchCallActivity(callerId, room, isIncoming)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    /** Bitmask of the declared FGS types whose runtime permission is currently granted. */
    private fun grantedForegroundTypes(): Int {
        var type = 0
        if (hasPermission(Manifest.permission.CAMERA)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return type
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Loud, tappable notice: the emergency call could not run because a permission is missing. */
    private fun postPermissionMissingNotification() {
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, Constants.CHANNEL_CALL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Emergency call could not start")
            .setContentText("Camera or microphone permission is missing. Open Kin to fix it.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID + 1, notification) }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            // Long enough to survive a slow ICE/TURN negotiation on mobile data; the
            // call screen's own keep-screen-on flag is the primary mechanism once visible.
            acquire(10 * 60 * 1000L /* 10 min safety timeout */)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun callActivityIntent(callerId: String, room: String, isIncoming: Boolean): Intent =
        Intent(this, CallActivity::class.java).apply {
            putExtra(CallLauncher.EXTRA_CALLER_ID, callerId)
            putExtra(CallLauncher.EXTRA_ROOM, room)
            putExtra(CallLauncher.EXTRA_IS_INCOMING, isIncoming)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

    private fun launchCallActivity(callerId: String, room: String, isIncoming: Boolean) {
        // On Android 10+ starting an activity from the background generally requires going
        // through a full-screen-intent notification (already posted above) OR calling
        // startActivity directly from a foreground service context, which IS allowed. We
        // do both: start directly (works reliably from a just-foregrounded service) and
        // rely on the notification's fullScreenIntent as a fallback if the direct start is
        // ever suppressed by OEM battery managers.
        runCatching { startActivity(callActivityIntent(callerId, room, isIncoming)) }
            .onFailure { Log.w(TAG, "Direct CallActivity launch failed, relying on full-screen notification", it) }
    }

    private fun buildCallNotification(callerId: String, room: String, isIncoming: Boolean): Notification {
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            0,
            callActivityIntent(callerId, room, isIncoming),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isIncoming) "Incoming emergency call" else "Calling $callerId…"
        val text = if (isIncoming) "$callerId is reaching in" else "Connecting…"

        val builder = NotificationCompat.Builder(this, Constants.CHANNEL_CALL)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Best-effort: CallStyle requires a Person + PendingIntents for answer/hangup
            // actions; kept as a plain high-priority notification here to stay dependency-
            // light. TODO(commander/UX polish): upgrade to NotificationCompat.CallStyle
            // once caller Person objects are available from the sync/contacts layer.
        }

        return builder.build()
    }

    companion object {
        /** Stops the service and tears down the active call, if any. */
        fun stop(context: Context) {
            CallSessionHolder.endCall()
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }
}
