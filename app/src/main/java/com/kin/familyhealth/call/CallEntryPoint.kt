package com.kin.familyhealth.call

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * Nav-route entry point for `call/{callerId}` (see ARCHITECTURE.md "Shared contracts" and
 * MainActivity's NavGraph). This lets the call screen render even when reached via
 * in-app navigation rather than the [CallForegroundService] full-screen-intent path
 * (e.g. tapping a "return to call" notification, or a future "recent calls" entry).
 *
 * It delegates to the exact same [CallScreen] composable [CallActivity] uses, reading the
 * live session out of [CallSessionHolder] — it does not create a session itself. If no
 * session is active for this caller (e.g. deep-linked with nothing in progress), it shows
 * a simple placeholder rather than crashing.
 */
@Composable
fun EntryScreen(callerId: String) {
    val session = CallSessionHolder.session
    val room = CallSessionHolder.room
    val context = LocalContext.current

    if (session != null && room != null) {
        CallScreen(
            callerId = CallSessionHolder.callerId ?: callerId,
            isIncoming = CallSessionHolder.isIncoming,
            session = session,
            // Same full teardown as CallActivity: stops the foreground service (and its
            // notification + wake lock), not just the WebRTC session.
            onHangUp = { CallForegroundService.stop(context) }
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No active call with $callerId")
        }
    }
}
