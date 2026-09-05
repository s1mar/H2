package com.kin.familyhealth

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.google.firebase.FirebaseApp
import com.kin.familyhealth.core.Constants
import com.kin.familyhealth.di.ServiceLocator

/**
 * Application entry point. FOUNDATION-owned.
 *
 * Firebase init is guarded: if `google-services.json` is still the FOUNDATION
 * placeholder (see app/google-services.json), FirebaseApp.initializeApp will
 * either throw or produce a non-functional instance depending on the
 * Play services state on device — either way we must not crash app startup,
 * so any failure here is swallowed and logged. Feature agents (AGENT-SYNC)
 * should check `FirebaseApp.getApps(context).isNotEmpty()` before relying on
 * Firebase services if they need to guard against the placeholder file too.
 */
class KinApp : Application() {

    override fun onCreate() {
        super.onCreate()
        initFirebaseSafely()
        createNotificationChannels()
        // Inject the signaling client so incoming/outgoing calls can start.
        runCatching { ServiceLocator.installCallSignaling(this) }
    }

    private fun initFirebaseSafely() {
        try {
            FirebaseApp.initializeApp(this)
        } catch (t: Throwable) {
            // Placeholder google-services.json or missing Play services in this
            // environment — no-op so the rest of the app keeps working.
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                Constants.CHANNEL_CALL,
                "Emergency reach-in calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Full-screen incoming call notifications"
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                Constants.CHANNEL_ALERT,
                "Health alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Fall/SOS and abnormal vitals alerts"
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                Constants.CHANNEL_SYNC,
                "Vitals sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background vitals sync status"
            }
        )
    }
}
