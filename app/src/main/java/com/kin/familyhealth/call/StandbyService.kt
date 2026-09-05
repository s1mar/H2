package com.kin.familyhealth.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.kin.familyhealth.MainActivity
import com.kin.familyhealth.core.CallLauncher
import com.kin.familyhealth.core.Constants

private const val TAG = "StandbyService"
private const val NOTIFICATION_ID = 4301

/** Invites older than this are stale (e.g. seen on a restart) and are dropped. */
private const val INVITE_MAX_AGE_MS = 60_000L

/**
 * Always-on, serverless wake path for emergency reach-ins.
 *
 * The callee's phone keeps a single Firestore listener on `incoming_calls/{myUid}`.
 * When the paired partner taps "Reach in", they write that doc; this service sees it
 * within a second and starts [CallForegroundService] exactly as the FCM path would.
 * This needs no Cloud Function and no billing account. It survives Doze via the
 * battery-optimization exemption the readiness banner insists on, and restarts at
 * boot and every app open (START_STICKY).
 *
 * Runs as a `specialUse` foreground service (Android 14 allows that type to start
 * from BOOT_COMPLETED; long-running listeners are exactly its purpose).
 */
class StandbyService : Service() {

    private var registration: ListenerRegistration? = null
    private var authListener: FirebaseAuth.AuthStateListener? = null
    @Volatile private var lastHandledRoom: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed; standby unavailable", t)
            stopSelf()
            return
        }
        attachWhenSignedIn()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        registration?.remove()
        registration = null
        authListener?.let { runCatching { FirebaseAuth.getInstance().removeAuthStateListener(it) } }
        authListener = null
        super.onDestroy()
    }

    private fun attachWhenSignedIn() {
        val auth = runCatching { FirebaseAuth.getInstance() }.getOrNull() ?: return
        auth.currentUser?.uid?.let { listen(it); return }
        // Not signed in yet (fresh install before onboarding): attach once we are.
        val l = FirebaseAuth.AuthStateListener { a ->
            a.currentUser?.uid?.let { uid ->
                listen(uid)
                authListener?.let { auth.removeAuthStateListener(it) }
                authListener = null
            }
        }
        authListener = l
        auth.addAuthStateListener(l)
    }

    private fun listen(myUid: String) {
        registration?.remove()
        registration = try {
            Firebase.firestore.collection(Constants.COLLECTION_INCOMING_CALLS).document(myUid)
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        Log.w(TAG, "incoming_calls listener error", err)
                        return@addSnapshotListener
                    }
                    if (snap == null || !snap.exists()) return@addSnapshotListener
                    val room = snap.getString("room") ?: return@addSnapshotListener
                    val callerId = snap.getString("callerId") ?: return@addSnapshotListener
                    val at = snap.getTimestamp("at")?.toDate()?.time
                    // `at` is null only for the writer's own local echo; the callee always
                    // sees the server value. Drop stale invites (e.g. found on restart).
                    if (at != null && System.currentTimeMillis() - at > INVITE_MAX_AGE_MS) {
                        snap.reference.delete()
                        return@addSnapshotListener
                    }
                    if (room == lastHandledRoom) return@addSnapshotListener
                    lastHandledRoom = room
                    snap.reference.delete() // consume so it can't re-trigger
                    Log.i(TAG, "Incoming reach-in from $callerId (room $room)")
                    CallLauncher.startIncomingCall(applicationContext, callerId, room)
                }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to attach incoming_calls listener", t)
            null
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(Constants.CHANNEL_SYNC) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    Constants.CHANNEL_SYNC, "Standing by", NotificationManager.IMPORTANCE_MIN
                ).apply { description = "Kin is ready to receive an emergency reach-in" }
            )
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, Constants.CHANNEL_SYNC)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Kin is standing by")
            .setContentText("Ready to receive an emergency reach-in. Don't force-stop Kin.")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        /** Idempotent: safe to call on every app open and at boot. */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context, Intent(context, StandbyService::class.java)
                )
            }.onFailure { Log.w(TAG, "Could not start StandbyService", it) }
        }
    }
}
