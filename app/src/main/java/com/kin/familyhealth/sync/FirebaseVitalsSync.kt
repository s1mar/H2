package com.kin.familyhealth.sync

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.kin.familyhealth.core.VitalsSync
import com.kin.familyhealth.data.settings.SettingsRepository
import com.kin.familyhealth.vitals.model.Vitals
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

private const val TAG = "FirebaseVitalsSync"
private const val COLLECTION_PARTNERS = "partners"
private const val DOC_LATEST = "latest"

/**
 * AGENT-SYNC implementation of [VitalsSync] over Firestore.
 *
 * Schema: `partners/{uid}/latest` — a single document per uid holding that
 * uid's most recent [Vitals] snapshot (field names match the Vitals
 * properties exactly).
 *
 * @param firestore Firestore instance (pass `Firebase.firestore`).
 * @param settingsRepository used to resolve the paired partner's uid.
 * @param myUid supplies the signed-in user's uid (e.g. `{ Firebase.auth.currentUser?.uid }`).
 */
class FirebaseVitalsSync(
    private val firestore: FirebaseFirestore,
    private val settingsRepository: SettingsRepository,
    private val myUid: () -> String?,
) : VitalsSync {

    override suspend fun push(mine: Vitals) {
        val uid = myUid() ?: run {
            Log.w(TAG, "push() skipped: no signed-in uid")
            return
        }
        try {
            val doc = mapOf(
                "uid" to mine.uid,
                "timestampEpochMs" to mine.timestampEpochMs,
                "heartRateBpm" to mine.heartRateBpm,
                "restingHrBpm" to mine.restingHrBpm,
                "steps" to mine.steps,
                "sleepMinutes" to mine.sleepMinutes,
                "spo2Percent" to mine.spo2Percent,
                "batteryPct" to mine.batteryPct,
            )
            firestore.collection(COLLECTION_PARTNERS).document(uid)
                .collection(DOC_LATEST).document(DOC_LATEST)
                .set(doc)
        } catch (t: Throwable) {
            // Guard: placeholder google-services.json / no network must not crash the caller.
            Log.w(TAG, "push() failed", t)
        }
    }

    override fun partnerVitals(): Flow<Vitals?> = callbackFlow {
        val partnerUid = try {
            settingsRepository.partnerUid.first()
        } catch (t: Throwable) {
            Log.w(TAG, "partnerVitals() could not read partnerUid", t)
            null
        }
        if (partnerUid.isNullOrBlank()) {
            trySend(null)
            close()
            return@callbackFlow
        }

        val registration = try {
            firestore.collection(COLLECTION_PARTNERS).document(partnerUid)
                .collection(DOC_LATEST).document(DOC_LATEST)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "partnerVitals() listener error", error)
                        trySend(null)
                        return@addSnapshotListener
                    }
                    if (snapshot == null || !snapshot.exists()) {
                        trySend(null)
                        return@addSnapshotListener
                    }
                    val vitals = runCatching {
                        Vitals(
                            uid = snapshot.getString("uid") ?: partnerUid,
                            timestampEpochMs = snapshot.getLong("timestampEpochMs") ?: 0L,
                            heartRateBpm = snapshot.getLong("heartRateBpm")?.toInt(),
                            restingHrBpm = snapshot.getLong("restingHrBpm")?.toInt(),
                            steps = snapshot.getLong("steps")?.toInt(),
                            sleepMinutes = snapshot.getLong("sleepMinutes")?.toInt(),
                            spo2Percent = snapshot.getDouble("spo2Percent"),
                            batteryPct = snapshot.getLong("batteryPct")?.toInt(),
                        )
                    }.getOrNull()
                    trySend(vitals)
                }
        } catch (t: Throwable) {
            // Guard: Firebase misconfigured (placeholder google-services.json) — emit empty flow.
            Log.w(TAG, "partnerVitals() failed to attach listener", t)
            trySend(null)
            close()
            return@callbackFlow
        }

        awaitClose { registration.remove() }
    }
}
