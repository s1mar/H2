package com.kin.familyhealth.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * OWNED BY AGENT-ONBOARD. Route: "onboarding" (see ARCHITECTURE.md NavGraph).
 *
 * Friendly, older-parent-friendly multi-step setup: welcome -> runtime permissions ->
 * Health Connect -> special access nudges -> pairing.
 *
 * [onFinished] is the only parameter required to match the NavGraph contract exactly.
 * [pairing] and [healthConnectPermissions] are optional, defaulted seams: the commander
 * should pass the real `sync.PairingService` (adapted to the local [Pairing] interface)
 * and AGENT-VITALS's `HealthConnectReader.permissions` when wiring the NavGraph, e.g.
 *   EntryScreen(
 *       onFinished = { nav.navigate("dashboard") },
 *       pairing = RealPairingAdapter(pairingService),
 *       healthConnectPermissions = healthConnectReader.permissions,
 *   )
 * Left un-passed, it falls back to [NoopPairing] and [HealthConnectPermissions.DEFAULT]
 * so this screen still compiles and previews standalone.
 */
@Composable
fun EntryScreen(
    onFinished: () -> Unit,
    pairing: Pairing = remember { NoopPairing() },
    healthConnectPermissions: Set<String> = HealthConnectPermissions.DEFAULT,
) {
    val context = LocalContext.current
    val viewModel: OnboardingViewModel = viewModel(
        factory = remember(pairing) { OnboardingViewModel.Factory(context, pairing) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Exit onboarding once paired OR once the user chose to pair later (skip). Also
    // fires immediately on later launches, since the ViewModel restores the flag.
    LaunchedEffect(state.paired, state.onboardingComplete) {
        if (state.paired || state.onboardingComplete) onFinished()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        val stepIndex = OnboardingStep.values().indexOf(state.step)
        LinearProgressIndicator(
            progress = { (stepIndex + 1) / OnboardingStep.values().size.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            when (state.step) {
                OnboardingStep.WELCOME -> WelcomeStep(onNext = viewModel::nextStep)
                OnboardingStep.RUNTIME_PERMISSIONS -> RuntimePermissionsStep(
                    onNext = viewModel::nextStep,
                    onBack = viewModel::previousStep,
                )
                OnboardingStep.HEALTH_CONNECT -> HealthConnectStep(
                    permissions = healthConnectPermissions,
                    onNext = viewModel::nextStep,
                    onBack = viewModel::previousStep,
                )
                OnboardingStep.SPECIAL_ACCESS -> SpecialAccessStep(
                    onNext = viewModel::nextStep,
                    onBack = viewModel::previousStep,
                )
                OnboardingStep.PAIRING -> PairingStep(
                    state = state,
                    onSignIn = viewModel::signInIfNeeded,
                    onPartnerUidChanged = viewModel::onPartnerUidChanged,
                    onSubmit = viewModel::submitPairing,
                    onSkip = viewModel::skipPairing,
                    onBack = viewModel::previousStep,
                )
            }
        }
    }
}

private val TitleStyle @Composable get() = MaterialTheme.typography.headlineMedium
private val BodyStyle @Composable get() = MaterialTheme.typography.bodyLarge

@Composable
private fun StepScaffold(
    title: String,
    body: @Composable () -> Unit,
    footer: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(title, style = TitleStyle, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Column(modifier = Modifier.weight(1f, fill = false)) { body() }
        Spacer(Modifier.height(24.dp))
        footer()
    }
}

@Composable
private fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp),
        contentPadding = PaddingValuesLarge,
    ) {
        Text(text, fontSize = 20.sp)
    }
}

private val PaddingValuesLarge = androidx.compose.foundation.layout.PaddingValues(16.dp)

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    StepScaffold(
        title = "Welcome to Kin",
        body = {
            Text(
                "Kin quietly shares health basics -- heart rate, steps, sleep -- between " +
                    "you and the person you pair with, so you can each see how the other " +
                    "is doing.",
                style = BodyStyle,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "If something feels like an emergency, either of you can start a video " +
                    "call that opens automatically on the other's phone -- no need for " +
                    "them to pick up first.",
                style = BodyStyle,
            )
            Spacer(Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Your privacy", fontWeight = FontWeight.Bold, style = BodyStyle)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "A green camera dot always shows on screen whenever video is live " +
                            "-- that's Android's own privacy indicator, and it can't be " +
                            "hidden. Only the one person you pair with can ever reach in.",
                        style = BodyStyle,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Next we'll ask for a few permissions. Each one only does what we just " +
                    "described -- we'll explain as we go.",
                style = BodyStyle,
            )
        },
        footer = { BigButton("Get started", onNext) },
    )
}

@Composable
private fun RuntimePermissionsStep(onNext: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var cameraGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.CAMERA)) }
    var micGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.RECORD_AUDIO)) }
    var notifGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= 33) hasPermission(context, Manifest.permission.POST_NOTIFICATIONS) else true,
        )
    }
    var askedOnce by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        askedOnce = true
        cameraGranted = result[Manifest.permission.CAMERA] ?: cameraGranted
        micGranted = result[Manifest.permission.RECORD_AUDIO] ?: micGranted
        if (Build.VERSION.SDK_INT >= 33) {
            notifGranted = result[Manifest.permission.POST_NOTIFICATIONS] ?: notifGranted
        }
    }

    fun requestNeeded() {
        val toRequest = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        launcher.launch(toRequest.toTypedArray())
    }

    val allGranted = cameraGranted && micGranted && notifGranted

    StepScaffold(
        title = "A few phone permissions",
        body = {
            Text(
                "Kin needs these to make the emergency video call and to alert you when " +
                    "your partner needs you:",
                style = BodyStyle,
            )
            Spacer(Modifier.height(16.dp))
            PermissionRow("Camera", "So your partner can see you during a reach-in call.", cameraGranted)
            PermissionRow("Microphone", "So your partner can hear you during a call.", micGranted)
            PermissionRow("Notifications", "So you're alerted the moment your partner reaches in.", notifGranted)
            if (askedOnce && !allGranted) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Some permissions were denied. You can grant them here, or later from " +
                        "your phone's Settings > Apps > Kin > Permissions.",
                    style = BodyStyle,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { openAppSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open app settings")
                }
            }
        },
        footer = {
            Column {
                BigButton(if (allGranted) "Continue" else "Allow permissions", onClick = {
                    if (allGranted) onNext() else requestNeeded()
                })
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onBack) { Text("Back") }
                    if (!allGranted) {
                        OutlinedButton(onClick = onNext) { Text("Skip for now") }
                    }
                }
            }
        },
    )
}

@Composable
private fun PermissionRow(name: String, why: String, granted: Boolean) {
    Row(modifier = Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        if (granted) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        } else {
            Spacer(Modifier.size(24.dp))
        }
        Spacer(Modifier.height(0.dp))
        Column(Modifier.padding(start = 8.dp)) {
            Text(name, fontWeight = FontWeight.Bold, style = BodyStyle)
            Text(why, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun HealthConnectStep(permissions: Set<String>, onNext: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var sdkStatus by remember { mutableStateOf(HealthConnectClient.getSdkStatus(context)) }
    var granted by remember { mutableStateOf(false) }

    val requestPermissions = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { grantedSet ->
        granted = grantedSet.containsAll(permissions)
    }

    StepScaffold(
        title = "Connect your watch data",
        body = {
            Text(
                "Kin reads heart rate, steps, sleep and oxygen from Health Connect -- the " +
                    "same place your Pixel Watch or Galaxy Watch already stores this data.",
                style = BodyStyle,
            )
            Spacer(Modifier.height(16.dp))
            when (sdkStatus) {
                HealthConnectClient.SDK_UNAVAILABLE -> {
                    Text(
                        "This phone can't run Health Connect. Vitals sharing won't work, " +
                            "but the rest of Kin -- including emergency calling -- still will.",
                        style = BodyStyle,
                    )
                }
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                    Text(
                        "Health Connect needs to be installed or updated first.",
                        style = BodyStyle,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { openHealthConnectInstall(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Install Health Connect") }
                }
                else -> {
                    if (granted) {
                        Text("Health Connect access granted. Thank you!", style = BodyStyle)
                    } else {
                        Text(
                            "You'll be asked to approve each type of data individually -- " +
                                "that's normal.",
                            style = BodyStyle,
                        )
                    }
                }
            }
        },
        footer = {
            Column {
                if (sdkStatus == HealthConnectClient.SDK_AVAILABLE && !granted) {
                    BigButton("Grant Health Connect access", onClick = {
                        requestPermissions.launch(permissions)
                    })
                    Spacer(Modifier.height(8.dp))
                } else if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
                    BigButton("Continue", onClick = onNext)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { sdkStatus = HealthConnectClient.getSdkStatus(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("I installed it, check again") }
                } else {
                    BigButton("Continue", onClick = onNext)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
        },
    )
}

@Composable
private fun SpecialAccessStep(onNext: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current

    StepScaffold(
        title = "Two more settings for emergencies",
        body = {
            Text(
                "These make sure an emergency call always gets through, even if your " +
                    "phone is locked or trying to save battery. They're optional but " +
                    "strongly recommended.",
                style = BodyStyle,
            )
            Spacer(Modifier.height(20.dp))
            Text("Display over other apps", fontWeight = FontWeight.Bold, style = BodyStyle)
            Text(
                "Lets the incoming-call screen appear immediately, even over your lock " +
                    "screen, instead of waiting for you to unlock first.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { openOverlaySettings(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open overlay settings") }
            Spacer(Modifier.height(20.dp))
            Divider()
            Spacer(Modifier.height(20.dp))
            Text("Skip battery optimization", fontWeight = FontWeight.Bold, style = BodyStyle)
            Text(
                "Stops the system from delaying or blocking Kin's alert when your " +
                    "partner reaches in, especially overnight.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { openBatteryOptimizationSettings(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open battery settings") }
        },
        footer = {
            Column {
                BigButton("Continue", onNext)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
        },
    )
}

@Composable
private fun PairingStep(
    state: OnboardingUiState,
    onSignIn: () -> Unit,
    onPartnerUidChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { onSignIn() }
    val clipboard = LocalClipboardManager.current

    StepScaffold(
        title = "Pair with your person",
        body = {
            Text(
                "Both of you need Kin installed. Share your codes with each other once " +
                    "-- after that, Kin remembers the pairing. Setting up one phone first? " +
                    "Copy your code, tap \"Skip for now\", and enter their code later in Settings.",
                style = BodyStyle,
            )
            Spacer(Modifier.height(20.dp))
            Text("Your code", fontWeight = FontWeight.Bold, style = BodyStyle)
            Spacer(Modifier.height(8.dp))
            when {
                state.isSigningIn || state.myUid == null -> CircularProgressIndicator()
                else -> {
                    val myUid = state.myUid
                    Card {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                myUid,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(onClick = {
                                clipboard.setText(AnnotatedString(myUid))
                            }) {
                                Text("Copy")
                            }
                        }
                    }
                    Text(
                        "Read this code out loud, text it, or show this screen to your " +
                            "partner. (A scannable QR code can be added later -- for now, " +
                            "the code above is all that's needed.)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("Their code", fontWeight = FontWeight.Bold, style = BodyStyle)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.partnerUidInput,
                onValueChange = onPartnerUidChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp),
            )
            if (state.pairError != null) {
                Spacer(Modifier.height(8.dp))
                Text(state.pairError, color = MaterialTheme.colorScheme.error, style = BodyStyle)
            }
        },
        footer = {
            Column {
                BigButton(
                    if (state.isPairing) "Pairing..." else "Pair",
                    onClick = onSubmit,
                    modifier = Modifier,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                    Text("Skip for now -- pair later in Settings")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
        },
    )
}

// -- small platform helpers --------------------------------------------------

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

private fun openHealthConnectInstall(context: Context) {
    val marketIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("market://details?id=com.google.android.apps.healthdata"),
    )
    runCatching { context.startActivity(marketIntent) }.onFailure {
        val webIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"),
        )
        context.startActivity(webIntent)
    }
}
