package com.kin.familyhealth.sync

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.kin.familyhealth.core.SignalMessage
import com.kin.familyhealth.core.SignalType
import com.kin.familyhealth.core.SignalingClient
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "FirebaseSignalingClient"
private const val COLLECTION_SIGNALING = "signaling"
private const val SUBCOLLECTION_MESSAGES = "messages"
private const val COLLECTION_OUTBOUND_PUSHES = "outbound_pushes"

/**
 * AGENT-SYNC implementation of [SignalingClient] over Firestore.
 *
 * Schema:
 *  - `signaling/{room}/messages/{autoId}` — one doc per [SignalMessage] sent into a
 *    room (offer/answer/ice/hangup), fields: type, fromUid, room, sdp, candidate,
 *    createdAt (server timestamp, used only for ordering).
 *  - `outbound_pushes/{autoId}` — a relay request a Cloud Function watches and
 *    turns into an FCM push (see SERVER-SIDE comment in [startEmergencyCall]).
 *
 * @param firestore Firestore instance (pass `Firebase.firestore`).
 * @param myUid supplies the signed-in user's uid, to identify/filter "my own" messages.
 */
class FirebaseSignalingClient(
    private val firestore: FirebaseFirestore,
    private val myUid: () -> String?,
) : SignalingClient {

    private val listeners = ConcurrentHashMap<String, ListenerRegistration>()

    override suspend fun startEmergencyCall(toUid: String, room: String) {
        val callerId = myUid() ?: run {
            Log.w(TAG, "startEmergencyCall() skipped: no signed-in uid")
            return
        }
        try {
            // Create/mark the room with a call-invite doc the callee's signaling
            // listener (once it opens the room) can also see.
            firestore.collection(COLLECTION_SIGNALING).document(room)
                .set(mapOf("room" to room, "callerId" to callerId, "toUid" to toUid))

            // SERVER-SIDE: a Cloud Function must relay this as an FCM push.
            // A client app cannot deliver FCM to another device directly — it can
            // only write a request document. A Firestore-triggered Cloud Function
            // (onCreate of outbound_pushes/{id}) must read this doc, send a
            // high-priority FCM data message to `toUid`'s registered token(s) at
            // `users/{toUid}.fcmToken`, with data = {type: EMERGENCY_CALL,
            // callerId, room}, and then delete/mark the doc processed.
            // See sync/README_signaling.md for the exact contract.
            firestore.collection(COLLECTION_OUTBOUND_PUSHES).add(
                mapOf(
                    "toUid" to toUid,
                    "type" to "EMERGENCY_CALL",
                    "room" to room,
                    "callerId" to callerId,
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "startEmergencyCall() failed", t)
        }
    }

    override suspend fun send(toUid: String, message: SignalMessage) {
        try {
            val doc = mapOf(
                "type" to message.type.name,
                "fromUid" to message.fromUid,
                "room" to message.room,
                "sdp" to message.sdp,
                "candidate" to message.candidate,
                "toUid" to toUid,
                "createdAt" to FieldValue.serverTimestamp(),
            )
            firestore.collection(COLLECTION_SIGNALING).document(message.room)
                .collection(SUBCOLLECTION_MESSAGES)
                .add(doc)
        } catch (t: Throwable) {
            Log.w(TAG, "send() failed", t)
        }
    }

    override fun incoming(room: String): Flow<SignalMessage> = callbackFlow {
        val myself = myUid()
        val registration = try {
            firestore.collection(COLLECTION_SIGNALING).document(room)
                .collection(SUBCOLLECTION_MESSAGES)
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "incoming() listener error", error)
                        return@addSnapshotListener
                    }
                    val changes = snapshot?.documentChanges ?: return@addSnapshotListener
                    for (change in changes) {
                        val data = change.document
                        val fromUid = data.getString("fromUid") ?: continue
                        if (fromUid == myself) continue // filter out my own messages
                        val typeName = data.getString("type") ?: continue
                        val type = runCatching { SignalType.valueOf(typeName) }.getOrNull() ?: continue
                        trySend(
                            SignalMessage(
                                type = type,
                                fromUid = fromUid,
                                room = data.getString("room") ?: room,
                                sdp = data.getString("sdp"),
                                candidate = data.getString("candidate"),
                            )
                        )
                    }
                }
        } catch (t: Throwable) {
            // Guard: Firebase misconfigured — emit an empty flow rather than crash.
            Log.w(TAG, "incoming() failed to attach listener", t)
            close()
            return@callbackFlow
        }
        listeners[room] = registration
        awaitClose {
            registration.remove()
            listeners.remove(room)
        }
    }

    override fun close(room: String) {
        listeners.remove(room)?.remove()
    }
}
