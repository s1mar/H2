/**
 * Kin — emergency-push relay Cloud Function.
 *
 * Client apps cannot send FCM messages to another device; only a trusted
 * server can. FirebaseSignalingClient.startEmergencyCall() writes a request
 * doc to `outbound_pushes/{id}`; this function relays it as a high-priority
 * data-only FCM message that wakes the callee's KinMessagingService, which
 * calls CallLauncher.startIncomingCall(...).
 *
 * Field names/values MUST match app core/Constants.kt:
 *   KEY_TYPE="type", KEY_CALLER_ID="callerId", KEY_ROOM="room",
 *   TYPE_EMERGENCY_CALL="EMERGENCY_CALL".
 */
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();
const db = getFirestore();

exports.relayEmergencyPush = onDocumentCreated("outbound_pushes/{pushId}", async (event) => {
  const snap = event.data;
  if (!snap) return;
  const data = snap.data() || {};

  if (data.type !== "EMERGENCY_CALL") {
    await snap.ref.delete().catch(() => {});
    return;
  }

  const toUid = data.toUid;
  if (!toUid) { await snap.ref.delete().catch(() => {}); return; }

  const userDoc = await db.collection("users").doc(toUid).get();
  const token = userDoc.get("fcmToken");
  if (!token) {
    await snap.ref.set({ error: "no_fcm_token", processedAt: Date.now() }, { merge: true });
    return;
  }

  try {
    await getMessaging().send({
      token,
      android: { priority: "high" },
      data: {
        type: "EMERGENCY_CALL",
        callerId: String(data.callerId || ""),
        room: String(data.room || ""),
      },
    });
    // Clear the request so it isn't reprocessed.
    await snap.ref.delete().catch(() => {});
  } catch (err) {
    await snap.ref.set(
      { error: String(err && err.message || err), processedAt: Date.now() },
      { merge: true },
    );
  }
});
