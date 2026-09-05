package com.kin.familyhealth.core

import kotlinx.coroutines.flow.Flow

/**
 * COMMANDER-owned integration contract between AGENT-SYNC and AGENT-CALL.
 *
 * AGENT-SYNC implements this (Firestore signaling docs at `signaling rooms`
 * plus a high-priority FCM push to wake the callee). AGENT-CALL consumes it
 * from the WebRTC layer. Neither agent redefines these types.
 */
enum class SignalType { OFFER, ANSWER, ICE, HANGUP }

data class SignalMessage(
    val type: SignalType,
    val fromUid: String,
    val room: String,
    val sdp: String? = null,       // for OFFER/ANSWER
    val candidate: String? = null, // JSON-encoded ICE candidate for ICE
)

interface SignalingClient {
    /** Fire the emergency-call wake push + create the room, so the partner's phone rings. */
    suspend fun startEmergencyCall(toUid: String, room: String)

    /** Send an SDP offer/answer, an ICE candidate, or a hangup into the room. */
    suspend fun send(toUid: String, message: SignalMessage)

    /** Stream of incoming signals for a room (offer/answer/ice/hangup from the peer). */
    fun incoming(room: String): Flow<SignalMessage>

    /** Stop listening / release Firestore listeners for a room. */
    fun close(room: String)
}
