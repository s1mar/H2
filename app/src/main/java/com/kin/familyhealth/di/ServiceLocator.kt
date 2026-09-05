package com.kin.familyhealth.di

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.kin.familyhealth.call.CallSessionHolder
import com.kin.familyhealth.call.reachIn
import com.kin.familyhealth.core.SignalingClient
import com.kin.familyhealth.core.SignalMessage
import com.kin.familyhealth.core.VitalsSync
import com.kin.familyhealth.data.settings.SettingsRepository
import com.kin.familyhealth.onboarding.Pairing
import com.kin.familyhealth.sync.FirebaseSignalingClient
import com.kin.familyhealth.sync.FirebaseVitalsSync
import com.kin.familyhealth.sync.PairingService
import com.kin.familyhealth.vitals.data.HealthConnectReader
import com.kin.familyhealth.vitals.model.Vitals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * COMMANDER-owned integration/wiring layer. Constructs the concrete
 * implementations produced by the feature agents and hands them to the UI
 * against the locked interfaces. If Firebase isn't initialised (e.g. the
 * placeholder google-services.json), offline-safe no-op fallbacks are returned
 * so the app still launches and renders instead of crashing.
 */
object ServiceLocator {

    private fun firebaseReady(context: Context): Boolean =
        runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)

    /** Current signed-in uid, or null if Firebase/auth is unavailable. */
    fun myUid(): String? = runCatching { Firebase.auth.currentUser?.uid }.getOrNull()

    fun settings(context: Context): SettingsRepository =
        SettingsRepository(context.applicationContext)

    fun healthPermissions(context: Context): Set<String> =
        HealthConnectReader(context.applicationContext).permissions

    @Volatile private var vitalsSync: VitalsSync? = null
    fun vitalsSync(context: Context): VitalsSync = vitalsSync ?: synchronized(this) {
        vitalsSync ?: run {
            val app = context.applicationContext
            val impl: VitalsSync = if (firebaseReady(app)) {
                FirebaseVitalsSync(Firebase.firestore, settings(app), ::myUid)
            } else {
                NoopVitalsSync
            }
            vitalsSync = impl
            impl
        }
    }

    @Volatile private var signaling: SignalingClient? = null
    fun signalingClient(context: Context): SignalingClient = signaling ?: synchronized(this) {
        signaling ?: run {
            val app = context.applicationContext
            if (firebaseReady(app)) {
                FirebaseSignalingClient(Firebase.firestore, ::myUid).also { signaling = it }
            } else {
                // Deliberately NOT cached: if Firebase wasn't ready at this instant, the next
                // call re-checks and can self-heal to the real client instead of being stuck
                // on a silent no-op forever.
                NoopSignalingClient
            }
        }
    }

    /** Adapts the sync PairingService to the onboarding Pairing seam. */
    fun pairing(context: Context): Pairing {
        val app = context.applicationContext
        val svc = PairingService(FirebaseAuth.getInstance(), Firebase.firestore, settings(app))
        return object : Pairing {
            override suspend fun signInAnonymously(): String = svc.signInAnonymously()
            override suspend fun pairWith(partnerUid: String) = svc.pairWith(partnerUid)
            override suspend fun registerFcmToken(uid: String) = svc.registerFcmToken(uid)
        }
    }

    /** Called once at startup so incoming/outgoing calls have a signaling client. */
    fun installCallSignaling(context: Context) {
        CallSessionHolder.signalingClient = signalingClient(context)
    }

    /**
     * Re-register the current FCM token on every launch. onNewToken() drops a rotated
     * token if it fires before sign-in; without this, that phone could silently become
     * unreachable for emergency wake-pushes. Idempotent merge write; cheap.
     */
    fun refreshFcmTokenIfSignedIn(context: Context) {
        val app = context.applicationContext
        if (!firebaseReady(app)) return
        val uid = myUid() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                PairingService(FirebaseAuth.getInstance(), Firebase.firestore, settings(app))
                    .registerFcmToken(uid)
            }
        }
    }

    /**
     * Dashboard "Reach in" action: resolve the paired partner + a deterministic
     * room id, then start the outgoing emergency call.
     */
    fun startReachIn(context: Context) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val partnerUid = runCatching { settings(app).partnerUid.first() }.getOrNull() ?: return@launch
            val room = roomFor(myUid(), partnerUid)
            reachIn(app, partnerUid, room, signalingClient(app))
        }
    }

    /**
     * Per-call room id. Both uids come first (sorted, so the Firestore rule
     * `request.auth.uid in room.split('_')` still passes), followed by a timestamp so
     * EVERY call gets a fresh signaling room. A fixed room would replay the previous
     * call's offer/answer/ICE and its HANGUP into the next call, killing it instantly.
     * The callee never derives this; it receives the room in the wake-push payload.
     */
    fun roomFor(a: String?, b: String): String =
        (listOfNotNull(a, b).sorted() + System.currentTimeMillis().toString()).joinToString("_")
}

/** Offline fallback: keeps the dashboard alive with no partner data. */
private object NoopVitalsSync : VitalsSync {
    override suspend fun push(mine: Vitals) {}
    override fun partnerVitals(): Flow<Vitals?> = flowOf(null)
}

/** Offline fallback: no signaling until real Firebase config is present. */
private object NoopSignalingClient : SignalingClient {
    override suspend fun startEmergencyCall(toUid: String, room: String) {}
    override suspend fun send(toUid: String, message: SignalMessage) {}
    override fun incoming(room: String): Flow<SignalMessage> = emptyFlow()
    override fun close(room: String) {}
}
