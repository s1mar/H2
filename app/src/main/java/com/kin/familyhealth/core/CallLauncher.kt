package com.kin.familyhealth.core

import android.content.Context
import android.content.Intent

/**
 * COMMANDER-owned integration contract for starting the call foreground service.
 *
 * AGENT-SYNC (KinMessagingService) calls [startIncomingCall] when a
 * TYPE_EMERGENCY_CALL push arrives. The dashboard/onboarding UI calls
 * [startOutgoingCall] when the user taps "reach in". AGENT-CALL reads these
 * extras in CallForegroundService/CallActivity. The service class is resolved
 * reflectively so this file has no compile dependency on the call package.
 */
object CallLauncher {
    const val EXTRA_CALLER_ID = "extra_caller_id"
    const val EXTRA_ROOM = "extra_room"
    const val EXTRA_IS_INCOMING = "extra_is_incoming"
    const val SERVICE_CLASS = "com.kin.familyhealth.call.CallForegroundService"

    fun startIncomingCall(context: Context, callerId: String, room: String) =
        start(context, callerId, room, incoming = true)

    fun startOutgoingCall(context: Context, partnerUid: String, room: String) =
        start(context, partnerUid, room, incoming = false)

    private fun start(context: Context, callerId: String, room: String, incoming: Boolean) {
        val intent = Intent().apply {
            setClassName(context, SERVICE_CLASS)
            putExtra(EXTRA_CALLER_ID, callerId)
            putExtra(EXTRA_ROOM, room)
            putExtra(EXTRA_IS_INCOMING, incoming)
        }
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }
}
