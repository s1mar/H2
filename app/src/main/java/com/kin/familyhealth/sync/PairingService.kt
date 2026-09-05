package com.kin.familyhealth.sync

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.kin.familyhealth.core.PairingBackendNotReadyException
import com.kin.familyhealth.core.PairingBlockedByStalePartnerException
import com.kin.familyhealth.core.PairingUnknownCodeException
import com.google.firebase.messaging.FirebaseMessaging
import com.kin.familyhealth.data.settings.SettingsRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "PairingService"

/** Upper bound for the two mutual pairing writes; beyond this we report failure. */
private const val PAIR_TIMEOUT_MS = 20_000L

/**
 * Local await() for a Play Services [Task], so this module doesn't need the
 * separate `kotlinx-coroutines-play-services` artifact (not declared in
 * app/build.gradle.kts) just for `.await()`.
 */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result -> cont.resume(result) }
    addOnFailureListener { error -> cont.resumeWithException(error) }
    addOnCanceledListener { cont.resumeWithException(java.util.concurrent.CancellationException("Task cancelled")) }
}

/**
 * AGENT-SYNC helper for the pairing/auth flow (consumed by AGENT-ONBOARD).
 *
 * Auth note: this is a privately sideloaded 2-user app (see ARCHITECTURE.md),
 * so anonymous Firebase Auth is sufficient — each phone gets one stable
 * anonymous uid, and pairing is just an explicit mutual exchange of those
 * uids. There is no real identity/credential system, by design.
 *
 * @param auth FirebaseAuth instance (pass `Firebase.auth`).
 * @param firestore Firestore instance (pass `Firebase.firestore`).
 * @param settingsRepository stores the paired partner's uid locally.
 */
class PairingService(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val settingsRepository: SettingsRepository,
) {

    /** Signs in anonymously if not already signed in; returns the uid. */
    suspend fun signInAnonymously(): String {
        auth.currentUser?.uid?.let { return it }
        return try {
            val result = auth.signInAnonymously().await()
            result.user?.uid ?: throw IllegalStateException("Anonymous sign-in returned no user")
        } catch (t: Throwable) {
            Log.w(TAG, "signInAnonymously() failed", t)
            throw t
        }
    }

    /** Fetches the current FCM token and stores it on `users/{uid}.fcmToken`. */
    suspend fun registerFcmToken(uid: String) {
        try {
            val token = FirebaseMessaging.getInstance().token.await()
            firestore.collection("users").document(uid)
                .set(mapOf("fcmToken" to token), SetOptions.merge())
                .await()
            // Presence marker (no sensitive data): lets the pairing screen verify that a
            // typed code belongs to a real Kin phone. Written every launch so it
            // self-heals if the rules weren't published yet at first sign-in.
            firestore.collection("presence").document(uid)
                .set(mapOf("seenAt" to FieldValue.serverTimestamp()), SetOptions.merge())
                .await()
        } catch (t: Throwable) {
            // Guard: no network / placeholder config must not crash onboarding.
            Log.w(TAG, "registerFcmToken() failed", t)
        }
    }

    /**
     * Writes a mutual pairing doc (`pairings/{myUid}` and `pairings/{partnerUid}`
     * each pointing at the other) and persists [partnerUid] locally so
     * [SettingsRepository.partnerUid] resolves it for the sync/call layers.
     */
    suspend fun pairWith(partnerUid: String) {
        val myUid = auth.currentUser?.uid
            ?: throw IllegalStateException("pairWith() called before sign-in")
        // Do NOT swallow failures. A pairing that never reached the backend must surface
        // as an error; otherwise the UI says "paired" while the partner can never read our
        // vitals. Bounded so an offline phone fails fast instead of hanging on "Pairing...".
        withTimeout(PAIR_TIMEOUT_MS) {
            // 1) Is this a real Kin phone's code (and not our own)? Every phone writes
            //    presence/{uid} at sign-in and on each launch. A mistyped code must fail
            //    loudly here rather than "pair" silently with nobody.
            if (partnerUid == myUid) throw PairingUnknownCodeException()
            val known = try {
                firestore.collection("presence").document(partnerUid).get().await().exists()
            } catch (e: FirebaseFirestoreException) {
                if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    throw PairingBackendNotReadyException()
                }
                throw e
            }
            if (!known) throw PairingUnknownCodeException()

            // 2) Our own pointer. Under the published rules a signed-in user may always
            //    write their own doc, so PERMISSION_DENIED here means the rules were never
            //    published -- report that, not "wrong code".
            try {
                firestore.collection("pairings").document(myUid)
                    .set(mapOf("partnerUid" to partnerUid), SetOptions.merge())
                    .await()
            } catch (e: FirebaseFirestoreException) {
                if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    throw PairingBackendNotReadyException()
                }
                throw e
            }

            // 3) The partner's pointer back to us.
            try {
                firestore.collection("pairings").document(partnerUid)
                    .set(mapOf("partnerUid" to myUid), SetOptions.merge())
                    .await()
            } catch (e: FirebaseFirestoreException) {
                // The rules refuse to overwrite a partner record that points at a DIFFERENT
                // uid -- typically OUR old identity from before a reinstall. Only the
                // partner can repair that from their phone; surface a specific error.
                if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    throw PairingBlockedByStalePartnerException()
                }
                throw e
            }
        }
        // Only persist locally once BOTH mutual writes are confirmed.
        settingsRepository.setPartnerUid(partnerUid)
    }
}
