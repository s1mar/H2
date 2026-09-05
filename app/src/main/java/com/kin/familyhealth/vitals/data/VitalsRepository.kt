package com.kin.familyhealth.vitals.data

import android.content.Context
import com.kin.familyhealth.vitals.model.Vitals

/**
 * AGENT-VITALS: thin repository wrapping [HealthConnectReader].
 *
 * Kept separate from the reader so ViewModels/screens depend on a small
 * surface (and so a fake can be swapped in for tests without touching
 * the Health Connect SDK types).
 */
class VitalsRepository(private val reader: HealthConnectReader) {

    constructor(context: Context) : this(HealthConnectReader(context))

    val permissions: Set<String> get() = reader.permissions

    fun availability(): HealthConnectAvailability = reader.availability()

    suspend fun hasAllPermissions(): Boolean = reader.hasAllPermissions()

    /** Reads my own latest vitals snapshot from Health Connect. */
    suspend fun readMine(uid: String): Vitals = reader.readLatest(uid)
}
