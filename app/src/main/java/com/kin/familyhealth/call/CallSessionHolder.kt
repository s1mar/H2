package com.kin.familyhealth.call

import android.content.Context
import com.kin.familyhealth.call.webrtc.WebRtcSession
import com.kin.familyhealth.core.SignalingClient

/**
 * Process-wide holder that bridges CallForegroundService (which owns the [WebRtcSession]
 * lifecycle) and CallActivity (which needs to attach renderers / drive controls) without a
 * bound-service round trip. Also the single injection point for the [SignalingClient]
 * implementation that AGENT-SYNC provides — see the integration note below.
 *
 * ── INTEGRATION (commander) ──────────────────────────────────────────────────────────
 * AGENT-CALL depends only on `core.SignalingClient` (the interface). At integration time,
 * wire the concrete implementation once, e.g. from `KinApp.onCreate()` or wherever
 * AGENT-SYNC constructs its Firestore/FCM-backed client:
 *
 *     CallSessionHolder.signalingClient = FirebaseSignalingClient(...)
 *
 * Do this before any call can start (app startup is early enough). If it's still null when
 * a call tries to start, [ensureSession] throws IllegalStateException with a clear message
 * rather than silently no-op'ing.
 */
object CallSessionHolder {

    /** Injected once by the commander at integration time (see class doc). */
    var signalingClient: SignalingClient? = null

    var session: WebRtcSession? = null
        private set

    /** Metadata about the call currently backing [session], set by CallForegroundService. */
    var callerId: String? = null
        private set
    var room: String? = null
        private set
    var isIncoming: Boolean = false
        private set

    /**
     * Create (or return the existing) [WebRtcSession] for this call. Only one call is
     * supported at a time — starting a new room disposes any previous session first.
     */
    fun ensureSession(context: Context, room: String, peerUid: String, incoming: Boolean): WebRtcSession {
        val existing = session
        // Reuse only a LIVE session for the same room; a disposed one must be rebuilt.
        if (existing != null && !existing.isDisposed && this.room == room) return existing

        existing?.dispose()

        val signaling = signalingClient
            ?: error(
                "CallSessionHolder.signalingClient is not set. The commander must inject " +
                    "an AGENT-SYNC SignalingClient implementation (e.g. in KinApp.onCreate()) " +
                    "before any call can start."
            )

        val newSession = WebRtcSession(
            appContext = context.applicationContext,
            signaling = signaling,
            room = room,
            peerUid = peerUid,
        )
        session = newSession
        this.room = room
        this.callerId = peerUid
        this.isIncoming = incoming
        return newSession
    }

    /** Ends and releases the current session, if any. */
    fun endCall() {
        session?.hangUp()
        clear()
    }

    /** Drops references without notifying the peer (session must already be disposed). */
    fun clear() {
        session = null
        callerId = null
        room = null
        isIncoming = false
    }
}
