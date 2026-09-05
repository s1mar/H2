package com.kin.familyhealth.vitals.model

import java.io.Serializable

/**
 * FOUNDATION-owned shared contract: a serializable vitals snapshot.
 * AGENT-VITALS produces these from Health Connect; AGENT-SYNC pushes/reads
 * them to/from Firestore at `partners/{uid}/latest`.
 *
 * Do not redefine this class elsewhere — feature agents consume it as-is.
 */
data class Vitals(
    val uid: String,
    val timestampEpochMs: Long,
    val heartRateBpm: Int? = null,
    val restingHrBpm: Int? = null,
    val steps: Int? = null,
    val sleepMinutes: Int? = null,
    val spo2Percent: Double? = null,
    val batteryPct: Int? = null,
) : Serializable
