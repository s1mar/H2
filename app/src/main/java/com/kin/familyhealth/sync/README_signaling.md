# Signaling / emergency-push server relay

Client apps cannot deliver FCM messages to another device directly — only a
trusted server (a Cloud Function with the Admin SDK / server key) can call the
FCM send API. `FirebaseSignalingClient.startEmergencyCall()` therefore only
*requests* a push by writing a Firestore document; a Cloud Function must relay
it as an actual FCM data message.

## Required Cloud Function

**Trigger:** Firestore `onCreate` on `outbound_pushes/{pushId}`.

**Read from the triggering document (`outbound_pushes/{pushId}`):**
```json
{
  "toUid": "<partner's uid>",
  "type": "EMERGENCY_CALL",
  "room": "<room id>",
  "callerId": "<caller's uid>"
}
```

**Function logic:**
1. Look up the recipient's FCM token at `users/{toUid}.fcmToken`.
2. Send a high-priority **data-only** FCM message to that token:
   ```json
   {
     "token": "<fcmToken>",
     "android": { "priority": "high" },
     "data": {
       "type": "EMERGENCY_CALL",
       "callerId": "<callerId>",
       "room": "<room>"
     }
   }
   ```
   Field names must match `core/Constants.kt`: `KEY_TYPE`, `KEY_CALLER_ID`,
   `KEY_ROOM`, and value `TYPE_EMERGENCY_CALL`. This lands in
   `KinMessagingService.onMessageReceived()` on the callee's phone, which
   calls `CallLauncher.startIncomingCall(...)`.
3. On success, delete (or mark `processed: true` on) the `outbound_pushes/{pushId}`
   document so the relay doesn't retry it.
4. On failure (e.g. stale token), log it — optionally write an error field back
   onto the doc for diagnostics.

## Example (Node.js / firebase-functions v2, for reference only — not part of
## this Android module; nothing under `sync/` depends on it)

```js
exports.relayEmergencyPush = onDocumentCreated("outbound_pushes/{pushId}", async (event) => {
  const data = event.data.data();
  if (data.type !== "EMERGENCY_CALL") return;
  const userDoc = await db.collection("users").doc(data.toUid).get();
  const token = userDoc.get("fcmToken");
  if (!token) return;
  await admin.messaging().send({
    token,
    android: { priority: "high" },
    data: { type: "EMERGENCY_CALL", callerId: data.callerId, room: data.room },
  });
  await event.data.ref.delete();
});
```

This function is **not** implemented in this repo (AGENT-SYNC's scope is
`sync/` only, and Cloud Functions live outside the Android module). Someone
with Firebase project deploy access must add it under a `functions/` Cloud
Functions project and deploy it separately.
