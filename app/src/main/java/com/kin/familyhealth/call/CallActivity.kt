package com.kin.familyhealth.call

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kin.familyhealth.call.webrtc.CallConnectionState
import com.kin.familyhealth.call.webrtc.WebRtcSession
import android.content.Intent
import androidx.compose.runtime.key
import com.kin.familyhealth.core.CallLauncher
import com.kin.familyhealth.ui.theme.KinTheme
import org.webrtc.SurfaceViewRenderer

/**
 * Full-screen call UI. Launched either:
 *  - directly by [CallForegroundService] (self-answer primary path — this is the normal
 *    flow; the OS-level showWhenLocked/turnScreenOn manifest flags plus the calls below
 *    get it on screen over the lock screen), or
 *  - via `call/{callerId}` nav route (see [CallEntryPoint] for the delegate composable).
 *
 * On an incoming call this activity auto-accepts immediately (no ringing UI) — the
 * "ringing" already happened as the full-screen-intent notification itself, and
 * [CallForegroundService] already told the [com.kin.familyhealth.call.webrtc.WebRtcSession]
 * to start as callee (auto-answer) before this activity was even launched.
 */
class CallActivity : ComponentActivity() {

    // Held as Compose state so a SECOND call delivered to this singleTask activity via
    // onNewIntent() (Android does not re-run onCreate) re-renders against the NEW session.
    // Without this the UI kept a stale, disposed WebRtcSession and could crash on
    // released EGL/renderer resources exactly during a second urgent reach-in.
    private val callerIdState = mutableStateOf("partner")
    private val isIncomingState = mutableStateOf(false)
    private val sessionState = mutableStateOf<WebRtcSession?>(null)

    private fun applyIntent(intent: Intent?) {
        callerIdState.value = intent?.getStringExtra(CallLauncher.EXTRA_CALLER_ID)
            ?: CallSessionHolder.callerId
            ?: "partner"
        isIncomingState.value =
            intent?.getBooleanExtra(CallLauncher.EXTRA_IS_INCOMING, CallSessionHolder.isIncoming)
                ?: CallSessionHolder.isIncoming
        sessionState.value = CallSessionHolder.session
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Programmatic equivalents of the manifest's showWhenLocked/turnScreenOn, kept for
        // clarity and for the FLAG_DISMISS_KEYGUARD/FLAG_KEEP_SCREEN_ON behavior the
        // manifest attributes alone don't cover.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        applyIntent(intent)

        setContent {
            KinTheme {
                val session = sessionState.value
                // key() forces a full recomposition (fresh video renderers) whenever the
                // session object changes, e.g. after onNewIntent for a new call.
                key(session) {
                    CallScreen(
                        callerId = callerIdState.value,
                        isIncoming = isIncomingState.value,
                        session = session,
                        onHangUp = {
                            CallForegroundService.stop(this)
                            finish()
                        }
                    )
                }
            }
        }
    }
}

/**
 * The composable call UI, factored out so [CallEntryPoint.EntryScreen] (the `call/{callerId}`
 * nav route) can render the identical screen without duplicating layout.
 *
 * By the time this is composed, [CallForegroundService] has already created the
 * [WebRtcSession] and stored it in [CallSessionHolder] — this composable only attaches
 * renderers and drives simple controls, it does not create or own the session.
 */
@Composable
fun CallScreen(
    callerId: String,
    isIncoming: Boolean,
    session: WebRtcSession?,
    onHangUp: () -> Unit,
) {
    var muted by remember { mutableStateOf(false) }
    val connectionState by (session?.connectionState?.collectAsState()
        ?: remember { mutableStateOf(CallConnectionState.CONNECTING) })
    val remoteHungUp by (session?.remoteHangup?.collectAsState()
        ?: remember { mutableStateOf(false) })

    // If the peer hangs up, follow them off the call screen.
    LaunchedEffect(remoteHungUp) {
        if (remoteHungUp) onHangUp()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize()) {

            // Remote video, full screen.
            if (session != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).also { renderer ->
                            session.attachRemoteRenderer(renderer)
                        }
                    }
                )
            }

            // Local preview, picture-in-picture, top-right.
            if (session != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .size(width = 110.dp, height = 150.dp)
                        .clip(RoundedCornerShape(12.dp))
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            SurfaceViewRenderer(ctx).also { renderer ->
                                session.attachLocalRenderer(renderer)
                            }
                        }
                    )
                }
            }

            // Top banner: emergency label + caller name + status.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.error,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "EMERGENCY REACH-IN",
                        color = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = callerId,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = statusLabel(isIncoming, connectionState),
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // Bottom controls: mute, hang up, flip camera.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                // Plain text glyphs rather than Material icons: this module intentionally
                // avoids adding the `material-icons-extended` dependency (out of scope —
                // AGENT-CALL owns only call/, not build.gradle.kts). Swap for real icons
                // once that dependency is added by whoever owns the build file.
                FloatingActionButton(
                    onClick = { muted = session?.toggleMute() ?: !muted },
                    containerColor = Color.DarkGray,
                    contentColor = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Text(if (muted) "Unmute" else "Mute", style = MaterialTheme.typography.labelSmall)
                }

                FloatingActionButton(
                    onClick = onHangUp,
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Text("End", style = MaterialTheme.typography.labelSmall)
                }

                FloatingActionButton(
                    onClick = { session?.switchCamera() },
                    containerColor = Color.DarkGray,
                    contentColor = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Text("Flip", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun statusLabel(isIncoming: Boolean, state: CallConnectionState): String = when (state) {
    CallConnectionState.CONNECTING -> if (isIncoming) "Connecting…" else "Calling…"
    CallConnectionState.CONNECTED -> "Connected"
    CallConnectionState.DISCONNECTED -> "Disconnected"
    CallConnectionState.FAILED -> "Connection failed"
}
