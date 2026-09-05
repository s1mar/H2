package com.kin.familyhealth.call

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kin.familyhealth.core.Constants
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * The three special-access grants an emergency reach-in depends on to open WITHOUT a
 * tap. Any of them missing means the callee may only get a normal notification:
 *  - Display over other apps (SYSTEM_ALERT_WINDOW): LOAD-BEARING, do not remove. Full-
 *    screen intents only auto-launch when the phone is locked/screen-off. When it is
 *    unlocked with the screen on, the service must call startActivity() from the
 *    background, which Android 10+ blocks -- a foreground service alone does NOT exempt
 *    it, but holding SYSTEM_ALERT_WINDOW does. Without it the callee only gets a
 *    tappable banner, which defeats the no-tap guarantee.
 *  - Full-screen notifications (Android 14+ user-revocable): auto-launch over lock screen.
 *  - Battery optimization exemption: stops Doze/OEM managers from killing the wake path.
 */
object EmergencyReadiness {

    fun overlayGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun fullScreenIntentAllowed(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManagerCompat.from(context).canUseFullScreenIntent()
        } else {
            true
        }

    fun batteryExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Camera + microphone runtime permissions. Without them Android 14+ refuses to even
     * start the call's foreground service, so they are a hard requirement, not a nicety.
     */
    fun cameraMicGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Notifications. On Android 13+ this is a runtime permission that onboarding can
     * skip; without it the incoming-call notification, health alerts, and the
     * "call could not start" notice are all silently dropped by the OS.
     */
    fun notificationsEnabled(context: Context): Boolean {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return false
        // Channel-level: a user can silence just the call channel (long-press -> off),
        // which drops the incoming-call and failure notices as surely as the app toggle.
        val channel = nm.getNotificationChannelCompat(Constants.CHANNEL_CALL) ?: return true
        return channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    /** Opens the call channel's own settings page (also reachable when app-level is off). */
    fun openNotificationSettings(context: Context) = open(
        context,
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, Constants.CHANNEL_CALL),
    )

    /**
     * For runtime permissions the system will no longer prompt for ("don't ask again"):
     * the only remaining path is the app's own settings page.
     */
    fun openAppSettings(context: Context) = open(
        context,
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ),
    )

    fun allReady(context: Context): Boolean =
        cameraMicGranted(context) && notificationsEnabled(context) && overlayGranted(context) &&
            fullScreenIntentAllowed(context) && batteryExempt(context)

    fun openOverlaySettings(context: Context) = open(
        context,
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
    )

    fun openFullScreenIntentSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= 34) {
            open(
                context,
                Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }
    }

    fun openBatterySettings(context: Context) = open(
        context,
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        ),
    )

    private fun open(context: Context, intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/**
 * Persistent warning shown on the dashboard whenever any emergency-readiness grant is
 * missing, with a button to fix each one. Re-checks every time the screen resumes, so it
 * disappears on its own once the user returns from Settings having fixed it.
 */
@Composable
fun EmergencyReadinessBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var overlay by remember { mutableStateOf(EmergencyReadiness.overlayGranted(context)) }
    var fullScreen by remember { mutableStateOf(EmergencyReadiness.fullScreenIntentAllowed(context)) }
    var battery by remember { mutableStateOf(EmergencyReadiness.batteryExempt(context)) }
    var camMic by remember { mutableStateOf(EmergencyReadiness.cameraMicGranted(context)) }
    var notifs by remember { mutableStateOf(EmergencyReadiness.notificationsEnabled(context)) }
    // Once the system dialog has been shown and still denied, Android stops prompting
    // ("don't ask again"); the button must then route to app settings, not go inert.
    var askedOnce by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        askedOnce = true
        camMic = EmergencyReadiness.cameraMicGranted(context)
        notifs = EmergencyReadiness.notificationsEnabled(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlay = EmergencyReadiness.overlayGranted(context)
                fullScreen = EmergencyReadiness.fullScreenIntentAllowed(context)
                battery = EmergencyReadiness.batteryExempt(context)
                camMic = EmergencyReadiness.cameraMicGranted(context)
                notifs = EmergencyReadiness.notificationsEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (overlay && fullScreen && battery && camMic && notifs) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Emergency calls may not open on their own",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Kin needs these so a reach-in can appear without anyone tapping. Fix each one:",
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(10.dp))
            if (!camMic) {
                Button(
                    onClick = {
                        if (askedOnce) {
                            EmergencyReadiness.openAppSettings(context)
                        } else {
                            permissionLauncher.launch(
                                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (askedOnce) "Open app settings to allow camera & mic"
                        else "Grant camera & microphone"
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            if (!notifs) {
                Button(
                    onClick = {
                        val appLevelOff =
                            !NotificationManagerCompat.from(context).areNotificationsEnabled()
                        if (Build.VERSION.SDK_INT >= 33 && appLevelOff && !askedOnce) {
                            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        } else {
                            // Channel silenced, pre-13, or already refused: go to settings.
                            EmergencyReadiness.openNotificationSettings(context)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Allow notifications") }
                Spacer(Modifier.height(6.dp))
            }
            if (!overlay) {
                Button(
                    onClick = { EmergencyReadiness.openOverlaySettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Allow display over other apps") }
            }
            if (!fullScreen) {
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { EmergencyReadiness.openFullScreenIntentSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Allow full-screen notifications") }
            }
            if (!battery) {
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { EmergencyReadiness.openBatterySettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stop battery optimization") }
            }
        }
    }
}
