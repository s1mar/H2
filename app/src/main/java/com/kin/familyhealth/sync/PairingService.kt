package com.kin.familyhealth.sync

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.kin.familyhealth.data.settings.SettingsRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "PairingService"

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
        try {
            firestore.collection("pairings").document(myUid)
                .set(mapOf("partnerUid" to partnerUid), SetOptions.merge())
                .await()
            firestore.collection("pairings").document(partnerUid)
                .set(mapOf("partnerUid" to myUid), SetOptions.merge())
                .await()
        } catch (t: Throwable) {
            Log.w(TAG, "pairWith() failed to write pairing docs", t)
        }
        settingsRepository.setPartnerUid(partnerUid)
    }
}
