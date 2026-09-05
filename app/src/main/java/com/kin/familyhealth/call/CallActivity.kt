package com.kin.familyhealth.call

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kin.familyhealth.call.webrtc.CallConnectionState
import com.kin.familyhealth.call.webrtc.WebRtcSession
import com.kin.familyhealth.core.CallLauncher
import com.kin.familyhealth.ui.theme.KinTheme
import org.webrtc.SurfaceViewRenderer

/**
 * Full-screen call UI. Launched either:
 *  - directly by [CallForegroundService] (self-answer primary path — this is the normal
 *    flow; the OS-level showWhenLocked/turnScreenOn manifest flags plus the calls below
 *    get it on screen over the lock screen), or
 *  - by [CallSessionHolder]-backed navigation via `call/{callerId}` (see [CallEntryPoint]).
 *
 * On an incoming call this activity auto-accepts immediately (no ringing UI) — the
 * "ringing" already happened as the full-screen-intent notification itself.
 */
class CallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Programmatic equivalents of the manifest's showWhenLocked/turnScreenOn, for
        // pre-Android-8.1 devices and to also dismiss the keyguard on this device family
        // (minSdk 29 already covers setShowWhenLocked/setTurnScreenOn, but call them
        // unconditionally behind the version check for clarity/back-compat safety).
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

        val callerId = intent.getStringExtra(CallLauncher.EXTRA_CALLER_ID)
            ?: CallSessionHolder.callerId
            ?: "partner"
        val room = intent.getStringExtra(CallLauncher.EXTRA_ROOM) ?: CallSessionHolder.room
        val isIncoming = intent.getBooleanExtra(CallLauncher.EXTRA_IS_INCOMING, CallSessionHolder.isIncoming)

        setContent {
            KinTheme {
                CallScreen(
                    callerId = callerId,
                    isIncoming = isIncoming,
                    session = CallSessionHolder.session,
                    onHangUp = {
                        CallForegroundService.stop(this)
                        finish()
                    }
                )
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

    DisposableEffect(remoteHungUp) {
        onDispose { }
    }
    // If the peer hangs up, follow them off the screen.
    if (remoteHungUp) {
        DisposableEffect(Unit) {
            onDispose { }
        }
        onHangUp()
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

            // Top banner.
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
                        style = MaterialTheme.styleSmall()
                    )
                }
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(6.dp))
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

            // Bottom controls.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                FloatingActionButton(
                    onClick = {
                        muted = session?.toggleMute() ?: !muted
                    },
                    containerColor = Color.DarkGray,
                    contentColor = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Icon(if (muted) Icons.Filled.MicOff else Icons.Filled.Mic, contentDescription = "Mute")
                }

                FloatingActionButton(
                    onClick = onHangUp,
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Icon(Icons.Filled.CallEnd, contentDescription = "Hang up")
                }

                FloatingActionButton(
                    onClick = { session?.switchCamera() },
                    containerColor = Color.DarkGray,
                    contentColor = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Icon(Icons.Filled.Cameraswitch, contentDescription = "Flip camera")
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

// Small helper kept local to avoid importing an extra typography accessor style; mirrors
// MaterialTheme.typography.labelSmall to keep the banner text compact.
private fun MaterialTheme.styleSmall() = this.typography.labelSmall
