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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * The three special-access grants an emergency reach-in depends on to open WITHOUT a
 * tap. Any of them missing means the callee may only get a normal notification:
 *  - Display over other apps: lets the service open the call screen when the phone is
 *    unlocked with the screen on (full-screen intents only auto-launch when locked).
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

    fun allReady(context: Context): Boolean =
        cameraMicGranted(context) && overlayGranted(context) &&
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
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { camMic = EmergencyReadiness.cameraMicGranted(context) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlay = EmergencyReadiness.overlayGranted(context)
                fullScreen = EmergencyReadiness.fullScreenIntentAllowed(context)
                battery = EmergencyReadiness.batteryExempt(context)
                camMic = EmergencyReadiness.cameraMicGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (overlay && fullScreen && battery && camMic) return

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
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Grant camera & microphone") }
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
