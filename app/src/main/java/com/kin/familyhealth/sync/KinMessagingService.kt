package com.kin.familyhealth.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.kin.familyhealth.core.CallLauncher
import com.kin.familyhealth.core.Constants

private const val TAG = "KinMessagingService"

/**
 * AGENT-SYNC implementation of the FCM entry point (declared by FOUNDATION in the
 * manifest already — do not touch AndroidManifest.xml).
 *
 * Handles:
 *  - [Constants.TYPE_EMERGENCY_CALL]: starts the incoming call via [CallLauncher].
 *  - [Constants.TYPE_ALERT]: posts a notification on [Constants.CHANNEL_ALERT].
 *  - New FCM token: persisted to `users/{uid}.fcmToken` so the Cloud Function
 *    relay (see README_signaling.md) knows where to deliver pushes.
 */
class KinMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        try {
            val data = message.data
            when (data[Constants.KEY_TYPE]) {
                Constants.TYPE_EMERGENCY_CALL -> {
                    val callerId = data[Constants.KEY_CALLER_ID]
                    val room = data[Constants.KEY_ROOM]
                    if (callerId != null && room != null) {
                        CallLauncher.startIncomingCall(this, callerId, room)
                    } else {
                        Log.w(TAG, "TYPE_EMERGENCY_CALL missing callerId/room")
                    }
                }
                Constants.TYPE_ALERT -> postAlertNotification(message)
                Constants.TYPE_SIGNAL -> {
                    // Signaling payloads (offer/answer/ice) are delivered via the
                    // Firestore listener in FirebaseSignalingClient.incoming(), not
                    // via FCM data content. Nothing to do here beyond waking the app.
                }
                else -> Log.w(TAG, "Unknown message type: ${data[Constants.KEY_TYPE]}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "onMessageReceived() failed", t)
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        try {
            val uid = Firebase.auth.currentUser?.uid
            if (uid == null) {
                Log.w(TAG, "onNewToken() skipped: no signed-in uid yet")
                return
            }
            Firebase.firestore.collection("users").document(uid)
                .set(mapOf("fcmToken" to token), com.google.firebase.firestore.SetOptions.merge())
        } catch (t: Throwable) {
            Log.w(TAG, "onNewToken() failed to persist token", t)
        }
    }

    private fun postAlertNotification(message: RemoteMessage) {
        val title = message.data["title"] ?: message.notification?.title ?: "Kin alert"
        val body = message.data["body"] ?: message.notification?.body ?: "Check on your partner"

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = nm.getNotificationChannel(Constants.CHANNEL_ALERT)
            if (existing == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        Constants.CHANNEL_ALERT,
                        "Kin Alerts",
                        NotificationManager.IMPORTANCE_HIGH,
                    )
                )
            }
        }

        val notification = NotificationCompat.Builder(this, Constants.CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        nm.notify(System.currentTimeMillis().toInt(), notification)
    }
}
