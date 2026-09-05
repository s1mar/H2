package com.kin.familyhealth.call

import android.content.Context
import com.kin.familyhealth.core.CallLauncher
import com.kin.familyhealth.core.SignalingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Helper for the dashboard's "Reach in" button (AGENT-VITALS owns the button; this is the
 * function it calls). Kicks off an outgoing emergency call to the paired partner.
 *
 * Signature (for the commander/AGENT-VITALS to wire up):
 *
 *     fun reachIn(context: Context, partnerUid: String, room: String, signaling: SignalingClient)
 *
 * - [context]: any Context; used to start the foreground service (applicationContext used
 *   internally, so an Activity context is fine to pass but never retained).
 * - [partnerUid]: the paired partner's uid (from `SettingsRepository.partnerUid`).
 * - [room]: a fresh room id for this call (e.g. a UUID or `"${myUid}_${System.currentTimeMillis()}"`)
 *   — caller-generated because [SignalingClient.startEmergencyCall] takes it as a parameter.
 * - [signaling]: the AGENT-SYNC [SignalingClient] implementation. This is also stored into
 *   [CallSessionHolder.signalingClient] here (if not already set) so [CallForegroundService]
 *   can pick it up — callers do not need to wire that separately, though the commander may
 *   prefer to set [CallSessionHolder.signalingClient] once at app startup instead (either
 *   is fine; the assignment here is idempotent).
 *
 * What it does:
 *  1. Ensures [CallSessionHolder.signalingClient] is populated.
 *  2. Fires [SignalingClient.startEmergencyCall] to wake the partner's phone (creates the
 *     signaling room + sends the high-priority push — AGENT-SYNC's job).
 *  3. Calls [CallLauncher.startOutgoingCall] to start [CallForegroundService] locally,
 *     which will create the [com.kin.familyhealth.call.webrtc.WebRtcSession] as caller,
 *     open the camera/mic, and launch [CallActivity].
 */
fun reachIn(context: Context, partnerUid: String, room: String, signaling: SignalingClient) {
    if (CallSessionHolder.signalingClient == null) {
        CallSessionHolder.signalingClient = signaling
    }

    // Fire-and-forget: wakes the partner's device. CallForegroundService.startAsCaller()
    // does not depend on this completing first — ICE/SDP will simply wait in Firestore
    // until the callee's listener attaches.
    CoroutineScope(Dispatchers.IO).launch {
        runCatching { signaling.startEmergencyCall(partnerUid, room) }
    }

    CallLauncher.startOutgoingCall(context, partnerUid, room)
}
