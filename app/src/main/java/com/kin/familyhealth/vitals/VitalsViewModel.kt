package com.kin.familyhealth.vitals

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kin.familyhealth.core.VitalsSync
import com.kin.familyhealth.data.settings.SettingsRepository
import com.kin.familyhealth.vitals.data.HealthConnectAvailability
import com.kin.familyhealth.vitals.data.VitalsRepository
import com.kin.familyhealth.vitals.model.Vitals
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** UI-facing status for a vitals snapshot section (partner card or "how you're doing"). */
enum class VitalsLoadState {
    LOADING,
    LOADED,
    NO_PERMISSION,
    NO_PARTNER,
    ERROR,
}

data class DashboardUiState(
    val myVitals: Vitals? = null,
    val myState: VitalsLoadState = VitalsLoadState.LOADING,
    val partnerVitals: Vitals? = null,
    val partnerState: VitalsLoadState = VitalsLoadState.LOADING,
)

/**
 * AGENT-VITALS: drives the dashboard screen.
 *
 * - Streams the partner's latest snapshot from the injected [VitalsSync].
 * - Periodically reads MY latest snapshot from Health Connect via
 *   [VitalsRepository] and pushes it through [vitalsSync].push(mine).
 *
 * Plain constructor injection — no Hilt. Build via [VitalsViewModel.Factory]
 * from a [Context] plus the real [VitalsSync] implementation (supplied by the
 * commander from the sync package at integration time).
 */
class VitalsViewModel(
    private val vitalsSync: VitalsSync,
    private val vitalsRepository: VitalsRepository,
    private val settingsRepository: SettingsRepository,
    /**
     * This device's own uid, used to stamp/push MY [Vitals] snapshots.
     * ASSUMPTION: no shared "my uid" contract exists yet in core/ or
     * SettingsRepository, so the commander supplies it at wiring time
     * (e.g. FirebaseAuth.getInstance().currentUser?.uid). Null disables
     * the periodic self-read/push loop but partner vitals still stream.
     */
    private val myUid: String?,
    private val pushIntervalMs: Long = DEFAULT_PUSH_INTERVAL_MS,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        observePartnerVitals()
        observePartnerConfigured()
        startSelfReadPushLoop()
    }

    private fun observePartnerConfigured() {
        viewModelScope.launch {
            settingsRepository.partnerUid.collect { partnerUid ->
                if (partnerUid.isNullOrBlank() && _uiState.value.partnerVitals == null) {
                    _uiState.value = _uiState.value.copy(partnerState = VitalsLoadState.NO_PARTNER)
                }
            }
        }
    }

    private fun observePartnerVitals() {
        viewModelScope.launch {
            vitalsSync.partnerVitals()
                .catch { _uiState.value = _uiState.value.copy(partnerState = VitalsLoadState.ERROR) }
                .collect { vitals ->
                    _uiState.value = _uiState.value.copy(
                        partnerVitals = vitals,
                        partnerState = if (vitals != null) VitalsLoadState.LOADED else VitalsLoadState.NO_PARTNER,
                    )
                }
        }
    }

    private fun startSelfReadPushLoop() {
        viewModelScope.launch {
            while (true) {
                refreshMine()
                delay(pushIntervalMs)
            }
        }
    }

    /** Reads my latest Health Connect snapshot, updates UI state, and pushes it via sync. */
    suspend fun refreshMine() {
        val uid = myUid
        if (uid == null) {
            _uiState.value = _uiState.value.copy(myState = VitalsLoadState.ERROR)
            return
        }
        if (vitalsRepository.availability() !is HealthConnectAvailability.Available) {
            _uiState.value = _uiState.value.copy(myState = VitalsLoadState.NO_PERMISSION)
            return
        }
        if (!vitalsRepository.hasAllPermissions()) {
            _uiState.value = _uiState.value.copy(myState = VitalsLoadState.NO_PERMISSION)
            return
        }
        runCatching { vitalsRepository.readMine(uid) }
            .onSuccess { mine ->
                _uiState.value = _uiState.value.copy(myVitals = mine, myState = VitalsLoadState.LOADED)
                runCatching { vitalsSync.push(mine) }
            }
            .onFailure {
                _uiState.value = _uiState.value.copy(myState = VitalsLoadState.ERROR)
            }
    }

    /**
     * Factory for plain (non-Hilt) construction.
     *
     * The commander supplies the real [VitalsSync] implementation from the
     * sync package; this agent depends only on the [VitalsSync] interface.
     */
    class Factory(
        private val context: Context,
        private val vitalsSync: VitalsSync,
        private val myUid: String?,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val appContext = context.applicationContext
            @Suppress("UNCHECKED_CAST")
            return VitalsViewModel(
                vitalsSync = vitalsSync,
                vitalsRepository = VitalsRepository(appContext),
                settingsRepository = SettingsRepository(appContext),
                myUid = myUid,
            ) as T
        }
    }

    companion object {
        const val DEFAULT_PUSH_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
    }
}
