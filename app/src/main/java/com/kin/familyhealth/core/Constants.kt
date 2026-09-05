package com.kin.familyhealth.core

/**
 * FOUNDATION-owned shared contract. Feature agents consume these values —
 * do not redefine or duplicate them elsewhere.
 */
object Constants {

    const val APP_PACKAGE = "com.kin.familyhealth"

    // Notification channel ids
    const val CHANNEL_CALL = "kin_channel_call"
    const val CHANNEL_ALERT = "kin_channel_alert"
    const val CHANNEL_SYNC = "kin_channel_sync"

    // FCM data message keys
    const val KEY_TYPE = "type"
    const val KEY_CALLER_ID = "callerId"
    const val KEY_SDP = "sdp"
    const val KEY_CANDIDATE = "candidate"
    const val KEY_ROOM = "room"

    /**
     * Serverless wake path: the caller writes incoming_calls/{calleeUid}; the callee's
     * StandbyService listens on its own doc and starts the call from it. Works with no
     * Cloud Function / billing. FCM (if the relay is ever deployed) is a bonus.
     */
    const val COLLECTION_INCOMING_CALLS = "incoming_calls"

    // FCM data message-type values
    const val TYPE_EMERGENCY_CALL = "EMERGENCY_CALL"
    const val TYPE_SIGNAL = "SIGNAL"
    const val TYPE_ALERT = "ALERT"
}
