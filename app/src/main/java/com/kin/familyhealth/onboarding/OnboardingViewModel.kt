package com.kin.familyhealth.onboarding

import android.content.Context
import androidx.health.connect.client.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kin.familyhealth.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * OWNED BY AGENT-ONBOARD.
 *
 * Local seam onto AGENT-SYNC's real pairing/auth code so this package compiles in
 * isolation. At integration the commander should adapt `sync.PairingService`
 * (suspend fun signInAnonymously(): String, suspend fun pairWith(partnerUid: String),
 * suspend fun registerFcmToken(uid: String)) to this interface -- the method
 * signatures below were written to match it exactly, so a trivial wrapper /
 * direct implementation is enough.
 */
interface Pairing {
    suspend fun signInAnonymously(): String
    suspend fun pairWith(partnerUid: String)
    suspend fun registerFcmToken(uid: String)
}

/**
 * No-op fallback used as a default so `EntryScreen(onFinished = ...)` keeps compiling
 * and previewing on its own before the commander wires the real PairingService.
 * NEVER wire this into the shipped app -- it fakes success without doing anything.
 */
class NoopPairing : Pairing {
    override suspend fun signInAnonymously(): String = "PREVIEW-UID-0000"
    override suspend fun pairWith(partnerUid: String) { /* no-op stub */ }
    override suspend fun registerFcmToken(uid: String) { /* no-op stub */ }
}

/**
 * Local seam onto AGENT-VITALS's HealthConnectReader so this package doesn't hard
 * depend on it. At integration, pass `healthConnectReader.permissions` (a
 * `Set<String>`) into [EntryScreen] / [OnboardingViewModel.Factory] instead of the
 * [HealthConnectPermissions.DEFAULT] fallback below.
 */
object HealthConnectPermissions {
    /**
     * Mirrors the Health Connect read scopes named in ARCHITECTURE.md /
     * AndroidManifest.xml (READ_HEART_RATE, READ_STEPS, READ_SLEEP,
     * READ_RESTING_HEART_RATE, READ_OXYGEN_SATURATION). Used only until
     * AGENT-VITALS's real permission set is threaded through.
     */
    val DEFAULT: Set<String> = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
    )
}

enum class OnboardingStep {
    WELCOME,
    RUNTIME_PERMISSIONS,
    HEALTH_CONNECT,
    SPECIAL_ACCESS,
    PAIRING,
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val myUid: String? = null,
    val isSigningIn: Boolean = false,
    val partnerUidInput: String = "",
    val isPairing: Boolean = false,
    val pairError: String? = null,
    val paired: Boolean = false,
)

/**
 * Holds onboarding flow state. Talks to the FOUNDATION-owned [SettingsRepository]
 * and to the local [Pairing] seam (see above) -- never to a concrete
 * `sync.PairingService` directly, so this file compiles without depending on
 * AGENT-SYNC's package.
 */
class OnboardingViewModel(
    private val settingsRepository: SettingsRepository,
    private val pairing: Pairing,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun goToStep(step: OnboardingStep) {
        _uiState.value = _uiState.value.copy(step = step)
    }

    fun nextStep() {
        val order = OnboardingStep.values()
        val idx = order.indexOf(_uiState.value.step)
        if (idx < order.lastIndex) goToStep(order[idx + 1])
    }

    fun previousStep() {
        val order = OnboardingStep.values()
        val idx = order.indexOf(_uiState.value.step)
        if (idx > 0) goToStep(order[idx - 1])
    }

    fun signInIfNeeded() {
        if (_uiState.value.myUid != null || _uiState.value.isSigningIn) return
        _uiState.value = _uiState.value.copy(isSigningIn = true)
        viewModelScope.launch {
            runCatching { pairing.signInAnonymously() }
                .onSuccess { uid ->
                    _uiState.value = _uiState.value.copy(myUid = uid, isSigningIn = false)
                    runCatching { pairing.registerFcmToken(uid) }
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isSigningIn = false,
                        pairError = "Could not sign in. Check your connection and try again.",
                    )
                }
        }
    }

    fun onPartnerUidChanged(value: String) {
        _uiState.value = _uiState.value.copy(partnerUidInput = value, pairError = null)
    }

    fun submitPairing() {
        val partnerUid = _uiState.value.partnerUidInput.trim()
        if (partnerUid.isEmpty()) {
            _uiState.value = _uiState.value.copy(pairError = "Enter your partner's code first.")
            return
        }
        _uiState.value = _uiState.value.copy(isPairing = true, pairError = null)
        viewModelScope.launch {
            runCatching {
                pairing.pairWith(partnerUid)
                settingsRepository.setPartnerUid(partnerUid)
            }.onSuccess {
                _uiState.value = _uiState.value.copy(isPairing = false, paired = true)
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    isPairing = false,
                    pairError = "Couldn't pair with that code. Double-check it and try again.",
                )
            }
        }
    }

    /** Context-based constructor-injection factory, per AGENT-ONBOARD scope rules. */
    class Factory(
        private val context: Context,
        private val pairing: Pairing,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val repo = SettingsRepository(context.applicationContext)
            return OnboardingViewModel(repo, pairing) as T
        }
    }
}
