package com.kin.familyhealth.core

import com.kin.familyhealth.vitals.model.Vitals
import kotlinx.coroutines.flow.Flow

/**
 * COMMANDER-owned integration contract between AGENT-VITALS and AGENT-SYNC.
 *
 * AGENT-SYNC implements this over Firestore (`partners/{uid}/latest`):
 *  - [push] writes MY latest snapshot for my partner to read.
 *  - [partnerVitals] streams the PARTNER's latest snapshot for the dashboard.
 * AGENT-VITALS produces [Vitals] from Health Connect and renders the dashboard
 * off [partnerVitals]. Neither agent redefines this interface.
 */
interface VitalsSync {
    suspend fun push(mine: Vitals)
    fun partnerVitals(): Flow<Vitals?>
}
