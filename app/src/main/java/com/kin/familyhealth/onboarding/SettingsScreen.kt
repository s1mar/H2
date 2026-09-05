package com.kin.familyhealth.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.kin.familyhealth.data.settings.ReachInMode
import com.kin.familyhealth.data.settings.SettingsRepository
import kotlinx.coroutines.launch

/**
 * OWNED BY AGENT-ONBOARD. Route: "settings" (see ARCHITECTURE.md NavGraph).
 *
 * Only [onBack] is required, matching the NavGraph contract exactly. Everything else is
 * read straight from the FOUNDATION-owned [SettingsRepository] via [LocalContext], so no
 * extra wiring is needed at integration beyond navigating here.
 *
 * "Unpair" here only clears the locally-stored partner uid (SettingsRepository has no
 * backend concept of unpairing) -- if AGENT-SYNC/commander want the remote pairing torn
 * down too, that call should be added where this screen is wired up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryScreen(onBack: () -> Unit, pairing: Pairing? = null) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()

    val myDisplayName by settingsRepository.myDisplayName.collectAsState(initial = null)
    val partnerUid by settingsRepository.partnerUid.collectAsState(initial = null)
    val reachInMode by settingsRepository.reachInMode.collectAsState(initial = ReachInMode.SELF_ANSWER)
    val accessibilityOptIn by settingsRepository.accessibilityOptIn.collectAsState(initial = false)

    var nameField by remember(myDisplayName) { mutableStateOf(myDisplayName.orEmpty()) }
    // SettingsRepository has no "myUid" field (see FOUNDATION contract) -- this reads the
    // anonymous Firebase Auth session directly, which is the same uid onboarding's Pairing
    // seam produced from signInAnonymously(). firebase-auth-ktx is a FOUNDATION-declared
    // dependency (see build.gradle.kts), not an AGENT-SYNC package, so this isn't a
    // cross-agent-package import.
    var myUid by remember { mutableStateOf(FirebaseAuth.getInstance().currentUser?.uid) }
    var showUnpairConfirm by remember { mutableStateOf(false) }
    // Pair-from-Settings state (lets one phone finish setup before the other is ready).
    var partnerCodeInput by remember { mutableStateOf("") }
    var isPairing by remember { mutableStateOf(false) }
    var pairError by remember { mutableStateOf<String?>(null) }

    var cameraGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.CAMERA)) }
    var micGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.RECORD_AUDIO)) }
    var notifGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= 33) hasPermission(context, Manifest.permission.POST_NOTIFICATIONS) else true,
        )
    }

    fun recheckPermissions() {
        cameraGranted = hasPermission(context, Manifest.permission.CAMERA)
        micGranted = hasPermission(context, Manifest.permission.RECORD_AUDIO)
        notifGranted = if (Build.VERSION.SDK_INT >= 33) hasPermission(context, Manifest.permission.POST_NOTIFICATIONS) else true
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            SectionTitle("Your name")
            Text(
                "This is the name your partner sees on their dashboard.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = nameField,
                onValueChange = { nameField = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { scope.launch { settingsRepository.setMyDisplayName(nameField.ifBlank { null }) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save name") }

            Spacer(Modifier.height(28.dp))
            Divider()
            Spacer(Modifier.height(28.dp))

            SectionTitle("Pairing")
            LabeledValue("Your code", myUid ?: "Not signed in yet")
            if (myUid == null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val p = pairing ?: return@OutlinedButton
                        scope.launch {
                            runCatching { p.signInAnonymously() }
                                .onSuccess { uid ->
                                    myUid = uid
                                    runCatching { p.registerFcmToken(uid) }
                                }
                                .onFailure { pairError = "Could not sign in. Check your connection and try again." }
                        }
                    },
                    enabled = pairing != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Get my code") }
            }
            Spacer(Modifier.height(12.dp))
            LabeledValue("Partner's code", partnerUid ?: "Not paired yet")
            Spacer(Modifier.height(12.dp))
            if (partnerUid == null) {
                Text(
                    "Enter your partner's code to pair. You can do this any time -- " +
                        "for example once their phone is set up too.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = partnerCodeInput,
                    onValueChange = { partnerCodeInput = it; pairError = null },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp),
                )
                pairError?.let { err ->
                    Spacer(Modifier.height(8.dp))
                    Text(err, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val p = pairing ?: return@Button
                        val code = partnerCodeInput.trim()
                        if (code.isEmpty()) {
                            pairError = "Enter your partner's code first."
                            return@Button
                        }
                        isPairing = true
                        pairError = null
                        scope.launch {
                            runCatching {
                                val uid = p.signInAnonymously()
                                myUid = uid
                                p.pairWith(code)
                                settingsRepository.setOnboardingComplete(true)
                            }.onFailure { e ->
                                pairError = OnboardingViewModel.pairErrorMessage(e)
                            }
                            isPairing = false
                        }
                    },
                    enabled = pairing != null && !isPairing,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (isPairing) "Pairing..." else "Pair") }
            } else {
                OutlinedButton(
                    onClick = { showUnpairConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Unpair") }
            }

            Spacer(Modifier.height(28.dp))
            Divider()
            Spacer(Modifier.height(28.dp))

            SectionTitle("How reach-in calls answer")
            Text(
                "Choose how an emergency call opens on this phone.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            ReachInModeOption(
                title = "Self-answer (recommended)",
                description = "Kin opens the call itself with a full-screen alert, even " +
                    "over the lock screen. No extra setup needed.",
                selected = reachInMode == ReachInMode.SELF_ANSWER,
                onSelect = { scope.launch { settingsRepository.setReachInMode(ReachInMode.SELF_ANSWER) } },
            )
            Spacer(Modifier.height(8.dp))
            ReachInModeOption(
                title = "Accessibility auto-answer (fallback)",
                description = "Uses an Accessibility Service to tap \"Answer\" for you. " +
                    "Only turn this on if self-answer isn't working reliably on this phone.",
                selected = reachInMode == ReachInMode.ACCESSIBILITY,
                onSelect = { scope.launch { settingsRepository.setReachInMode(ReachInMode.ACCESSIBILITY) } },
            )

            Spacer(Modifier.height(28.dp))
            Divider()
            Spacer(Modifier.height(28.dp))

            SectionTitle("Accessibility auto-answer access")
            Text(
                "Required only if you selected Accessibility auto-answer above. This lets " +
                    "Kin tap the answer button on your behalf during a reach-in call.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Enable accessibility opt-in", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = accessibilityOptIn,
                    onCheckedChange = { checked ->
                        scope.launch { settingsRepository.setAccessibilityOptIn(checked) }
                        if (checked) openAccessibilitySettings(context)
                    },
                )
            }

            Spacer(Modifier.height(28.dp))
            Divider()
            Spacer(Modifier.height(28.dp))

            SectionTitle("Permissions")
            PermissionStatusRow("Camera", cameraGranted)
            PermissionStatusRow("Microphone", micGranted)
            PermissionStatusRow("Notifications", notifGranted)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { recheckPermissions() }, modifier = Modifier.fillMaxWidth()) {
                Text("Re-check permissions")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { openAppSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open app permission settings")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { openOverlaySettings(context) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open display-over-apps settings")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { openBatteryOptimizationSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open battery settings")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showUnpairConfirm) {
        AlertDialog(
            onDismissRequest = { showUnpairConfirm = false },
            title = { Text("Unpair?") },
            text = {
                Text(
                    "This removes your partner's code from this phone. They will no " +
                        "longer be able to reach in or see your vitals until you pair " +
                        "again.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch { settingsRepository.setPartnerUid(null) }
                    showUnpairConfirm = false
                }) { Text("Unpair") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showUnpairConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ReachInModeOption(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(Modifier.padding(start = 8.dp)) {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                Text(description, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PermissionStatusRow(name: String, granted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge)
        Text(
            if (granted) "Granted" else "Not granted",
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
        )
    }
}

// -- small platform helpers (duplicated intentionally from OnboardingScreen.kt to keep
// each file self-contained and independently reviewable) --------------------

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }
    context.startActivity(intent)
}

private fun openOverlaySettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )
    context.startActivity(intent)
}

private fun openBatteryOptimizationSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    context.startActivity(intent)
}

private fun openAccessibilitySettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    context.startActivity(intent)
}
